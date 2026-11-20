import {initializeApp} from 'firebase-admin/app';
import {getFirestore, FieldValue} from 'firebase-admin/firestore';
import {getStorage} from 'firebase-admin/storage';
import {getMessaging} from 'firebase-admin/messaging';
import {onDocumentUpdated} from 'firebase-functions/v2/firestore';
import {onSchedule} from 'firebase-functions/v2/scheduler';
import {readyTransition, expiredDocument} from './policy.mjs';
initializeApp();
const db = getFirestore();
const region = 'asia-southeast1';

export const pickupReady = onDocumentUpdated({document:'orders/{orderId}', region, retry:true}, async event => {
  if (!event.data || !readyTransition(event.data.before.data(), event.data.after.data())) return;
  const id = event.params.orderId;
  const record = db.collection('notificationEvents').doc(event.id);
  const now = Date.now();
  const acquired = await db.runTransaction(async tx => {
    const old = (await tx.get(record)).data();
    if (old?.sentAt) return false;
    if (old?.leaseUntil > now) throw new Error('Pickup notification is still being processed; retry after the lease.');
    tx.set(record, {leaseUntil:now + 60000, orderId:id});
    return true;
  });
  if (!acquired) return;
  try {
    const order = (await event.data.after.ref.get()).data();
    if (order?.status === 'Ready for Pickup') {
      const devices = await db.collection('users').doc(order.ownerId).collection('devices').get();
      const usable = devices.docs.filter(d => typeof d.data().token === 'string');
      for (let start = 0; start < usable.length; start += 500) {
        const batch = usable.slice(start, start+500);
        const result = await getMessaging().sendEachForMulticast({tokens:batch.map(d=>d.data().token),
          data:{type:'ready',ownerId:order.ownerId,orderId:id},
          android:{priority:'high',ttl:86400000,collapseKey:`pickup-${id}`}});
        let retry = false;
        await Promise.all(result.responses.map(async (r,i) => {
          if (r.success) return;
          if (['messaging/registration-token-not-registered','messaging/invalid-registration-token'].includes(r.error?.code)) await batch[i].ref.delete();
          else retry = true;
        }));
        if (retry) throw new Error('Some pickup alerts could not be delivered; retrying.');
      }
    }
    await record.set({sentAt:Date.now(),leaseUntil:0,orderId:id,expiresAt:FieldValue.serverTimestamp()});
  } catch (error) { await record.set({leaseUntil:0},{merge:true}); throw error; }
});

export const removeCompletedPdfs = onSchedule({schedule:'every day 02:00', timeZone:'Asia/Manila', region, timeoutSeconds:540}, async () => {
  const settings = (await db.doc('shopSettings/current').get()).data();
  const days = settings?.retentionDays ?? 0;
  if (![7,14,30].includes(days)) return; // No automatic deletion unless an administrator opts in.
  const now = Date.now();
  let cursor;
  do {
    let query = db.collection('orders').where('status','==','Completed').orderBy('__name__').limit(100);
    if (cursor) query = query.startAfter(cursor);
    const page = await query.get();
    if (page.empty) break;
    const latestDays=(await db.doc('shopSettings/current').get()).data()?.retentionDays;
    if (latestDays!==days) return;
    for (const snapshot of page.docs) {
      const order = (await snapshot.ref.get()).data();
      if (!order || !expiredDocument(order, days, now)) continue;
      // Completed is terminal; clients cannot change the path, quote, or completion timestamp.
      // Missing objects count as removed so a retry after a partial failure is safe.
      await getStorage().bucket().file(order.storagePath).delete({ignoreNotFound:true});
      await snapshot.ref.update({documentDeletedAt:Date.now()});
    }
    cursor = page.docs.at(-1);
    if (page.size < 100) break;
  } while (true);
});
