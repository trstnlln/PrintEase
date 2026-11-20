const { test, before, after } = require('node:test');
const fs = require('node:fs');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const { doc, setDoc, getDoc, updateDoc, runTransaction } = require('firebase/firestore');
const { ref, uploadBytes, getBytes, deleteObject } = require('firebase/storage');
let env;
before(async () => {
  env = await initializeTestEnvironment({projectId: 'demo-printease', firestore: {rules: fs.readFileSync('firebase/firestore.rules','utf8')}, storage: {rules: fs.readFileSync('firebase/storage.rules','utf8')}});
});
after(async () => {if(env) await env.cleanup();});
const student = () => env.authenticatedContext('student');
const admin = () => env.authenticatedContext('admin', {admin:true});
const order = (id) => ({ownerId:'student',studentName:'Alex',fileName:'Report.pdf',storagePath:`documents/student/${id}.pdf`,options:{paper:'A4',pages:3,copies:2,color:false,duplex:true},estimatedCost:12,createdAt:Date.now(),status:'Pending',queue:null,queueDay:null,note:''});
test('students read only their own orders; admin can read all', async () => {
  const db = student().firestore();
  await assertSucceeds(setDoc(doc(db,'orders/private'), order('private')));
  await assertSucceeds(getDoc(doc(db,'orders/private')));
  await assertFails(getDoc(doc(env.authenticatedContext('other').firestore(),'orders/private')));
  await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'orders/private')));
  await assertSucceeds(getDoc(doc(admin().firestore(),'orders/private')));
});
test('students cannot alter estimates, impersonate users or set order status', async () => {
  const db = student().firestore();
  await assertFails(setDoc(doc(db,'orders/bad-price'), {...order('bad-price'),estimatedCost:1}));
  await assertFails(setDoc(doc(db,'orders/bad-user'), {...order('bad-user'),ownerId:'other'}));
  await assertFails(setDoc(doc(db,'orders/bad-status'), {...order('bad-status'),status:'Accepted',queue:1}));
  await assertSucceeds(setDoc(doc(db,'orders/locked'), order('locked')));
  await assertFails(updateDoc(doc(db,'orders/locked'), {status:'Printing'}));
});
test('admin must follow workflow and cannot change price', async () => {
  await setDoc(doc(student().firestore(),'orders/workflow'),order('workflow'));
  const db = admin().firestore(), target = doc(db,'orders/workflow');
  await assertFails(updateDoc(target,{status:'Completed'}));
  await assertSucceeds(runTransaction(db, async tx => {
    const counter = doc(db,'counters/2026-11-09');
    const snap = await tx.get(counter); const number = (snap.data()?.last ?? 0) + 1;
    tx.set(counter,{last:number,orderId:'workflow'});
    tx.update(target,{status:'Accepted',queue:number,queueDay:'2026-11-09'});
  }));
  await assertFails(updateDoc(target,{status:'Printing',estimatedCost:0}));
  await assertSucceeds(updateDoc(target,{status:'Printing'}));
  await assertSucceeds(updateDoc(target,{status:'Ready for Pickup'}));
  await assertSucceeds(updateDoc(target,{status:'Completed'}));
  await assertFails(updateDoc(target,{status:'Pending'}));
});
test('private PDFs enforce ownership, size and MIME type', async () => {
  const data = new TextEncoder().encode('%PDF-1.4\nTest');
  const target = ref(student().storage(),'documents/student/upload.pdf');
  await assertSucceeds(uploadBytes(target,data,{contentType:'application/pdf'}));
  await assertSucceeds(getBytes(target));
  await assertSucceeds(getBytes(ref(admin().storage(),'documents/student/upload.pdf')));
  await assertFails(getBytes(ref(env.authenticatedContext('other').storage(),'documents/student/upload.pdf')));
  await assertFails(uploadBytes(ref(student().storage(),'documents/other/forged.pdf'),data,{contentType:'application/pdf'}));
  await assertFails(uploadBytes(ref(student().storage(),'documents/student/wrong.pdf'),data,{contentType:'text/plain'}));
  await assertFails(uploadBytes(ref(student().storage(),'documents/student/large.pdf'),new Uint8Array(5*1024*1024+1),{contentType:'application/pdf'}));
});

test('retry can inspect missing IDs without reading another student order', async () => {
  await assertSucceeds(getDoc(doc(student().firestore(),'orders/new-id')));
});
test('students may cancel only their own pending requests', async () => {
  const db = student().firestore(), target = doc(db,'orders/cancel');
  await setDoc(target,order('cancel'));
  await assertFails(updateDoc(doc(env.authenticatedContext('other').firestore(),'orders/cancel'),{status:'Cancelled'}));
  await assertSucceeds(updateDoc(target,{status:'Cancelled',note:''}));
  await assertFails(updateDoc(target,{status:'Pending'}));
});
test('counter reuse cannot assign a duplicate number to another order', async () => {
  await setDoc(doc(student().firestore(),'orders/duplicate'),order('duplicate'));
  await assertFails(updateDoc(doc(admin().firestore(),'orders/duplicate'),{status:'Accepted',queue:1,queueDay:'2026-11-09'}));
});
test('concurrent admin transactions allocate distinct queue numbers', async () => {
  const ids = ['concurrent-a','concurrent-b','concurrent-c'];
  await Promise.all(ids.map(id=>setDoc(doc(student().firestore(),'orders/'+id),order(id))));
  const db = admin().firestore();
  await Promise.all(ids.map(id=>runTransaction(db,async tx=>{
    const counter=doc(db,'counters/2026-11-14'),target=doc(db,'orders/'+id);
    await tx.get(target);
    const snap=await tx.get(counter);const n=(snap.data()?.last ?? 0)+1;
    tx.set(counter,{last:n,orderId:id});tx.update(target,{status:'Accepted',queue:n,queueDay:'2026-11-14'});
  })));
  const numbers=await Promise.all(ids.map(async id=>(await getDoc(doc(db,'orders/'+id))).data().queue));
  require('node:assert/strict').equal(new Set(numbers).size,3);
});

test('submitted PDFs cannot be deleted by students', async () => {
  const path='documents/student/no-delete.pdf';
  await uploadBytes(ref(student().storage(),path),new TextEncoder().encode('%PDF-test'),{contentType:'application/pdf'});
  await assertFails(deleteObject(ref(student().storage(),path)));
  await assertSucceeds(deleteObject(ref(admin().storage(),path)));
});

const settings = (version=1) => ({name:'Nearby print shop',address:'Near BSU Alangilan campus',pickupHours:'Confirm pickup hours with the shop',acceptingOrders:true,rates:{bw:4,color:12,legalExtra:2},retentionDays:0,version});
test('page selections are priced by selected pages; overlapping or forged ranges are rejected',async()=>{
 const db=student().firestore(),base=order('ranges');
 const options={...base.options,pages:10,pageRanges:[{start:1,end:3},{start:5,end:5}],selectedPages:4};
 const good={...base,options,estimatedCost:16,pricing:{bw:2,color:10,legalExtra:1},updatedAt:Date.now(),completedAt:null,documentDeletedAt:null};
 await assertSucceeds(setDoc(doc(db,'orders/ranges'),good)).catch(e=>{e.message='Valid two-range order failed: '+e.message;throw e;});
 const maximum={...good,storagePath:'documents/student/five-ranges.pdf',options:{...options,pages:20,pageRanges:Array.from({length:5},(_,i)=>({start:i*2+1,end:i*2+1})),selectedPages:5},estimatedCost:20};
 await assertSucceeds(setDoc(doc(db,'orders/five-ranges'),maximum)).catch(e=>{e.message='Valid five-range order failed: '+e.message;throw e;});
 await assertFails(setDoc(doc(db,'orders/six-ranges'),{...maximum,storagePath:'documents/student/six-ranges.pdf',options:{...maximum.options,pages:22,pageRanges:[...maximum.options.pageRanges,{start:21,end:21}],selectedPages:6},estimatedCost:24}));
 await assertFails(setDoc(doc(db,'orders/extra-range-key'),{...maximum,storagePath:'documents/student/extra-range-key.pdf',options:{...maximum.options,pageRanges:[{start:1,end:1,extra:true}],selectedPages:1},estimatedCost:4}));
 for(const [id,changes] of [['forged-count',{selectedPages:1}],['overlap',{pageRanges:[{start:1,end:3},{start:3,end:5}]}],['outside',{pageRanges:[{start:1,end:11}]}],['negative',{pageRanges:[{start:0,end:3}]}],['missing-ranges',{pageRanges:[]}],['fractional',{pageRanges:[{start:1.5,end:3}]}]]) {
  await assertFails(setDoc(doc(db,'orders/'+id),{...good,storagePath:`documents/student/${id}.pdf`,options:{...options,...changes}}));
 }
 // A huge span cannot compensate an invalid overlapping/negative span in the count.
 await assertFails(setDoc(doc(db,'orders/compensating-span'),{...good,storagePath:'documents/student/compensating-span.pdf',options:{...options,pageRanges:[{start:1,end:100001},{start:1,end:1}],selectedPages:1},estimatedCost:4}));
 await assertFails(setDoc(doc(db,'orders/negative-compensation'),{...good,storagePath:'documents/student/negative-compensation.pdf',options:{...options,pageRanges:[{start:-100000,end:-100000},{start:-100000,end:1}],selectedPages:1},estimatedCost:4}));
 await assertFails(updateDoc(doc(db,'orders/ranges'),{options:{...options,selectedPages:1},estimatedCost:4}));
});
test('only admins edit shop settings; rate changes preserve old quotes and pause new requests',async()=>{
 const adb=admin().firestore(),sdb=student().firestore(),target=doc(adb,'shopSettings/current');
 await assertFails(setDoc(doc(sdb,'shopSettings/current'),settings()));
 await assertSucceeds(setDoc(target,settings()));
 await assertFails(setDoc(target,settings())); // optimistic version check
 const old=await getDoc(doc(adb,'orders/private'));
 require('node:assert/strict').equal(old.data().estimatedCost,12);
 await assertFails(setDoc(doc(sdb,'orders/old-rate'),order('old-rate')));
 const priced={...order('new-rate'),pricing:settings().rates,estimatedCost:24};
 await assertSucceeds(setDoc(doc(sdb,'orders/new-rate'),priced));
 await assertFails(setDoc(doc(sdb,'orders/forged-rate'),{...priced,storagePath:'documents/student/forged-rate.pdf',pricing:{bw:1,color:1,legalExtra:0},estimatedCost:6}));
 await assertSucceeds(setDoc(target,{...settings(2),acceptingOrders:false,retentionDays:7}));
 await assertFails(setDoc(doc(sdb,'orders/paused'),{...priced,storagePath:'documents/student/paused.pdf'}));
 await assertFails(setDoc(target,{...settings(3),retentionDays:1}));
 await assertSucceeds(setDoc(target,{...settings(3),acceptingOrders:true}));
});
test('notification tokens belong to their signed-in owner; students cannot mark files removed',async()=>{
 const db=student().firestore(),target=doc(db,'users/student/devices/phone');
 await assertSucceeds(setDoc(target,{token:'a-valid-looking-test-device-token',updatedAt:Date.now()}));
 await assertFails(getDoc(doc(env.authenticatedContext('other').firestore(),'users/student/devices/phone')));
 await assertFails(setDoc(doc(db,'users/other/devices/phone'),{token:'a-valid-looking-test-device-token',updatedAt:Date.now()}));
 await assertFails(updateDoc(doc(db,'orders/private'),{documentDeletedAt:Date.now()}));
});
test('hosted PDFs bind immutable hashes and paths to the student',async()=>{
 const hash='a'.repeat(64), db=student().firestore();
 const saved={...order('hosted'),documentStore:'hosted',fileHash:hash,storagePath:`hosted/student/${hash}.pdf`,pricing:settings().rates,estimatedCost:24};
 await assertSucceeds(setDoc(doc(db,'orders/hosted'),saved));
 const maximum={...saved,options:{...saved.options,pages:1000,pageRanges:Array.from({length:5},(_,i)=>({start:i*200+1,end:(i+1)*200-1})),selectedPages:995},estimatedCost:7960,updatedAt:Date.now(),completedAt:null,documentDeletedAt:null};
 await assertSucceeds(setDoc(doc(db,'orders/hosted-max-ranges'),maximum));
 await assertFails(setDoc(doc(db,'orders/hosted-max-forged'),{...maximum,options:{...maximum.options,selectedPages:1},estimatedCost:8}));
 await assertFails(setDoc(doc(db,'orders/bad-hosted-owner'),{...saved,storagePath:`hosted/other/${hash}.pdf`}));
 await assertFails(setDoc(doc(db,'orders/bad-hosted-hash'),{...saved,fileHash:'wrong'}));
 await assertFails(updateDoc(doc(db,'orders/hosted'),{fileHash:'b'.repeat(64),storagePath:`hosted/student/${'b'.repeat(64)}.pdf`}));
});
test('only the console can grant administrator documents; students cannot promote themselves',async()=>{
 const db=student().firestore();
 await assertSucceeds(getDoc(doc(db,'admins/student')));
 await assertFails(setDoc(doc(db,'admins/student'),{enabled:true}));
 await assertFails(getDoc(doc(db,'admins/other')));
 await env.withSecurityRulesDisabled(async context=>setDoc(doc(context.firestore(),'admins/console-admin'),{enabled:true}));
 await assertSucceeds(getDoc(doc(env.authenticatedContext('console-admin').firestore(),'orders/private')));
});

test('pickup estimates belong to administrators and cannot modify immutable order details',async()=>{
 const id='eta-request',sdb=student().firestore(),adb=admin().firestore();
 const saved={...order(id),pricing:settings().rates,estimatedCost:24};
 await assertSucceeds(setDoc(doc(sdb,'orders/'+id),saved));
 const time=Date.now()+30*60000;
 await assertFails(updateDoc(doc(sdb,'orders/'+id),{expectedReadyAt:time,updatedAt:Date.now()}));
 await assertSucceeds(updateDoc(doc(adb,'orders/'+id),{expectedReadyAt:time,updatedAt:Date.now()}));
 await assertFails(updateDoc(doc(adb,'orders/'+id),{expectedReadyAt:time,estimatedCost:1,updatedAt:Date.now()}));
 await assertFails(updateDoc(doc(adb,'orders/'+id),{expectedReadyAt:Date.now()+8*86400000,updatedAt:Date.now()}));
 await assertSucceeds(updateDoc(doc(adb,'orders/'+id),{expectedReadyAt:null,updatedAt:Date.now()}));
 await assertFails(setDoc(doc(sdb,'orders/student-eta'),{...saved,storagePath:'documents/student/student-eta.pdf',expectedReadyAt:time}));
});
test('shop contacts reject unsafe links and price confirmation is explicit',async()=>{
 const db=admin().firestore(),target=doc(db,'shopSettings/current');
 const current=(await getDoc(target)).data();
 const next={...current,version:current.version+1,contactEmail:'nanoprint.alangilan@gmail.com',facebookUrl:'https://www.facebook.com/nano.example',ratesConfirmed:true};
 await assertFails(setDoc(target,{...next,facebookUrl:'javascript:alert(1)'}));
 await assertFails(setDoc(target,{...next,facebookUrl:'https://facebook.com.evil.example/page'}));
 await assertSucceeds(setDoc(target,next));
});
test('browser push subscriptions are private and other students cannot register devices for an owner',async()=>{
 const id='c'.repeat(64),value={endpoint:'https://fcm.googleapis.com/fcm/send/example',keys:{p256dh:'a'.repeat(87),auth:'b'.repeat(22)},updatedAt:Date.now()};
 const path='users/student/pushSubscriptions/'+id;
 await assertSucceeds(setDoc(doc(student().firestore(),path),value));
 await assertFails(getDoc(doc(env.authenticatedContext('other').firestore(),path)));
 await assertSucceeds(getDoc(doc(admin().firestore(),path)));
 await assertFails(setDoc(doc(env.authenticatedContext('other').firestore(),path),value));
 await assertFails(setDoc(doc(student().firestore(),'users/student/pushSubscriptions/'+'d'.repeat(64)),{...value,keys:{p256dh:'bad',auth:'bad'}}));
 const receipt='orders/private/pushDeliveries/'+id;
 await assertFails(setDoc(doc(student().firestore(),receipt),{state:'sent',updatedAt:Date.now()}));
 await assertSucceeds(setDoc(doc(admin().firestore(),receipt),{state:'sent',updatedAt:Date.now()}));
});

test('review gates acceptance, binds approval to a quote version, and allows a bounded correction', async () => {
 const id = 'quote-review', hash = 'f'.repeat(64), newer = 'e'.repeat(64), path = 'orders/' + id;
 const sdb = student().firestore(), adb = admin().firestore(), other = env.authenticatedContext('other').firestore();
 const submitted = { stage:'submitted', version:0, addons:['lamination'], note:'', quote:null };
 const quoted = { ...submitted, stage:'quoted', version:1, note:'Includes lamination', quote:35 };
 const approved = { ...quoted, stage:'approved', version:2 };
 const files = fileHash => ({ documentStore:'hosted', fileHash, storagePath:`hosted/student/${id}/${fileHash}.pdf` });
 const maximum = { ...order(id), ...files(hash), pricing:settings().rates, review:submitted,
   options: { ...order(id).options, pages:1000, pageRanges:Array.from({length:5},(_,i)=>({start:i*200+1,end:(i+1)*200-1})), selectedPages:995 },
   estimatedCost:7960 };
 await assertSucceeds(getDoc(doc(sdb,'capabilities/print-review-v1')));
 await assertFails(getDoc(doc(env.unauthenticatedContext().firestore(),'capabilities/print-review-v1')));
 await assertSucceeds(setDoc(doc(sdb,path), maximum));
 await assertFails(updateDoc(doc(sdb,path), { review:quoted }));
 await assertSucceeds(updateDoc(doc(adb,path), { review:quoted, updatedAt:Date.now() }));
 const accept = () => runTransaction(adb, async tx => {
   const counter = doc(adb,'counters/2026-11-28');
   tx.set(counter, { last:1, orderId:id });
   tx.update(doc(adb,path), { status:'Accepted', queue:1, queueDay:'2026-11-28' });
 });
 await assertFails(accept());
 await assertFails(updateDoc(doc(other,path), { review:approved }));
 await assertFails(updateDoc(doc(sdb,path), { review:{...approved, quote:1} }));
 await assertSucceeds(updateDoc(doc(sdb,path), { review:approved }));
 const correction = { ...submitted, stage:'correction', version:3, note:'Use the correct PDF' };
 await assertSucceeds(updateDoc(doc(adb,path), { review:correction }));
 await assertFails(accept());
 await assertFails(updateDoc(doc(sdb,path), { review:approved }));
 const revised = { ...submitted, stage:'revised', version:4 };
 await assertFails(updateDoc(doc(sdb,path), { review:revised, ...files(newer), estimatedCost:1 }));
 await assertFails(updateDoc(doc(sdb,path), { review:revised, ...files(newer), storagePath:`hosted/other/${id}/${newer}.pdf` }));
 await assertSucceeds(updateDoc(doc(sdb,path), { review:revised, ...files(newer), options:maximum.options, estimatedCost:7960, updatedAt:Date.now() }));
 await assertSucceeds(updateDoc(doc(adb,path), { review:{...quoted,version:5} }));
 await assertSucceeds(updateDoc(doc(sdb,path), { review:{...approved,version:6} }));
 await assertSucceeds(accept());
});

test('staff can mark only completed, expired per-order PDFs as removed', async () => {
 const adb = admin().firestore(), sdb = student().firestore(), old = Date.now() - 9*86400000;
 const hash = 'a'.repeat(64), scoped = id => `hosted/student/${id}/${hash}.pdf`;
 await env.withSecurityRulesDisabled(async context => {
   const db = context.firestore();
   await setDoc(doc(db,'shopSettings/current'), {...settings(7),retentionDays:7});
   for (const id of ['expired','fresh','shared','still-printing']) {
     await setDoc(doc(db,'orders/'+id), {...order(id), documentStore:'hosted', fileHash:hash,
       storagePath:id === 'shared' ? `hosted/student/${hash}.pdf` : scoped(id),
       status:id === 'still-printing' ? 'Printing' : 'Completed', completedAt:id === 'fresh' ? Date.now() : old });
   }
 });
 await assertFails(updateDoc(doc(sdb,'orders/expired'), {documentDeletedAt:Date.now()}));
 for (const id of ['fresh','shared','still-printing']) await assertFails(updateDoc(doc(adb,'orders/'+id), {documentDeletedAt:Date.now()}));
 await assertFails(updateDoc(doc(adb,'orders/expired'), {documentDeletedAt:Date.now(),fileName:'new'}));
 await assertSucceeds(updateDoc(doc(adb,'orders/expired'), {documentDeletedAt:Date.now()}));
});
