import {HostedDocuments} from '../src/documents.mjs';
import {createWorker} from '../worker/index.mjs';
import {MemoryBucket} from './fixtures/memory-bucket.mjs';
import test from 'node:test';
import assert from 'node:assert/strict';
import {connectAuthEmulator} from 'firebase/auth';
import {connectFirestoreEmulator,terminate} from 'firebase/firestore';
import {deleteApp} from 'firebase/app';
import {connectStorageEmulator} from 'firebase/storage';
import {FirebaseBackend} from '../src/firebase.mjs';
import {samplePDF} from '../src/demo.mjs';
import {defaults,defaultShop} from '../src/model.mjs';
Object.defineProperty(globalThis,'navigator',{configurable:true,value:{onLine:true}});
const delay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
async function until(fn){for(let i=0;i<160;i++){if(fn())return;await delay(25);}throw new Error('Expected Firebase state did not arrive.');}
test('web SDK shares private PDFs and queue updates through Firebase emulators',async t=>{
 const reset=await fetch('http://127.0.0.1:8080/emulator/v1/projects/demo-printease/databases/(default)/documents',{method:'DELETE'});assert.equal(reset.status,200);
let state;const snapshots=[],bucket=new MemoryBucket(),host='https://test.printease.example';
 const worker=createWorker({}, {projectId:'demo-printease',apiKey:'demo-fake-key',origin:host,
  identityBase:'http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1',firestoreBase:'http://127.0.0.1:8080/v1'});
 const documents=new HostedDocuments({baseUrl:host,xhr:null,request:(url,init)=>worker.fetch(new Request(url,init),{BUCKET:bucket})});

 const backend=new FirebaseBackend({projectId:'demo-printease',apiKey:'demo-fake-key',authDomain:'demo-printease.firebaseapp.com',storageBucket:'demo-printease.appspot.com',appId:'1:123:web:test'},value=>{state=value;snapshots.push(value);},documents);
 assert.equal(backend.app.options.projectId,'demo-printease');
 connectAuthEmulator(backend.auth,'http://127.0.0.1:9099',{disableWarnings:true});connectFirestoreEmulator(backend.db,'127.0.0.1',8080);connectStorageEmulator(backend.storage,'127.0.0.1',9199);
 t.after(async()=>{backend.close();await terminate(backend.db);await deleteApp(backend.app);});await backend.init();
 await backend.email('web-student@example.com','test-password-123','Web Student');await until(()=>state?.session);
 const student=state.session.uid,bytes=samplePDF(2),hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))).map(v=>v.toString(16).padStart(2,'0')).join('');
 const draft={id:'web-cloud-stable',pdf:{name:'Web report.pdf',pages:2,sha256:hash},file:new Blob([bytes],{type:'application/pdf'}),options:{...defaults(),pages:2,duplex:true}};
 await backend.submit(draft,()=>{});await backend.submit(draft,()=>{});await until(()=>state.orders.length===1);
 assert.equal(state.orders[0].documentStore,'hosted');assert.equal(state.orders[0].fileHash,hash);assert.equal(bucket.objects.size,1);
 const beforeRefresh=snapshots.length;await backend.refresh();await until(()=>state.orders.length===1);assert.ok(snapshots.slice(beforeRefresh).every(s=>s.session?.uid===student),'Refreshing the same account must not emit a sign-out');
 assert.equal(state.orders[0].estimatedCost,4);assert.equal(state.orders[0].ownerId,student);assert.deepEqual(await backend.bytes(state.orders[0]),bytes);await assert.rejects(backend.transition(draft.id,'Accepted'));
 await backend.signOut();await backend.email('web-admin@example.com','test-password-123','Web Admin');const adminId=backend.auth.currentUser.uid;
 const response=await fetch('http://127.0.0.1:9099/identitytoolkit.googleapis.com/v1/accounts:update?key=demo-fake-key',{method:'POST',headers:{'content-type':'application/json','authorization':'Bearer owner'},body:JSON.stringify({localId:adminId,customAttributes:JSON.stringify({admin:true})})});assert.equal(response.status,200);
 await backend.refresh();await until(()=>state.session?.admin&&state.orders.length===1);
 await backend.transition(draft.id,'Accepted');await until(()=>state.orders[0].status==='Accepted');assert.equal(state.orders[0].queue,1);assert.deepEqual(await backend.bytes(state.orders[0]),bytes);await backend.transition(draft.id,'Printing');await backend.transition(draft.id,'Ready for Pickup');
 await backend.signOut();await backend.email('web-student@example.com','test-password-123');await until(()=>state.orders[0]?.status==='Ready for Pickup');assert.equal(state.session.admin,false);assert.equal(state.orders.length,1);
 await backend.signOut();await backend.email('web-admin@example.com','test-password-123');await until(()=>state.session?.admin);
 await backend.saveSettings({...defaultShop(),rates:{bw:4,color:12,legalExtra:2}});await until(()=>state.settings.version===1);assert.equal(state.orders[0].estimatedCost,4);
 await backend.signOut();await backend.email('web-student@example.com','test-password-123');await until(()=>state.session?.uid===student&&state.settings.version===1);
 const selected={...draft,id:'web-selected',pdf:{...draft.pdf,name:'Selected.pdf'},options:{...defaults(),pages:2,pageRanges:[{start:2,end:2}]}};
 await backend.submit(selected,()=>{});await until(()=>state.orders.length===2);assert.equal(state.orders.find(o=>o.id==='web-selected').estimatedCost,4);assert.equal(state.orders.find(o=>o.id==='web-selected').options.selectedPages,1);
 await backend.signOut();await backend.email('web-other@example.com','test-password-123','Other Student');await until(()=>state.session?.uid!==student);await until(()=>state.orders.length===0);
// A console-created administrator document works without a service-account file or custom token minting.
 const consoleUid=backend.auth.currentUser.uid;
 const role=await fetch('http://127.0.0.1:8080/v1/projects/demo-printease/databases/(default)/documents/admins/'+consoleUid,{method:'PATCH',headers:{Authorization:'Bearer owner','Content-Type':'application/json'},body:JSON.stringify({fields:{enabled:{booleanValue:true}}})});assert.equal(role.status,200);
 await backend.refresh();await until(()=>state.session?.admin&&state.orders.length===2);
 assert.deepEqual(await backend.bytes(state.orders.find(o=>o.id==='web-cloud-stable')),bytes);
 const orphanToken=await backend.auth.currentUser.getIdToken();
 const wrongOrigin=await worker.fetch(new Request(host+'/api/uploads/'+hash,{headers:{Authorization:'Bearer '+orphanToken,Origin:'https://unrelated.example'}}),{BUCKET:bucket});assert.equal(wrongOrigin.status,403);
});
