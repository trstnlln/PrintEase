const { test, before, after } = require('node:test');
const fs = require('node:fs');
const { initializeTestEnvironment, assertSucceeds, assertFails } = require('@firebase/rules-unit-testing');
const { doc, setDoc, getDoc, updateDoc, runTransaction } = require('firebase/firestore');
const { ref, uploadBytes, getBytes } = require('firebase/storage');
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
    tx.set(counter,{last:number});
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
