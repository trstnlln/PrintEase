import test from 'node:test';
import assert from 'node:assert/strict';
import {Window} from 'happy-dom';
import 'fake-indexeddb/auto';
import {webcrypto} from 'node:crypto';
import * as db from '../src/db.mjs';
import {defaults} from '../src/model.mjs';
globalThis.__PE_TEST__=true;
const {PrintEase}=await import('../src/app.mjs');
async function setup(t){const window=new Window({url:'https://test.printease.example/'});for(const name of ['window','document','location','history','localStorage','navigator'])Object.defineProperty(globalThis,name,{configurable:true,value:window[name]});Object.defineProperty(globalThis,'crypto',{configurable:true,value:webcrypto});window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};window.HTMLDialogElement.prototype.close=function(){this.open=false;};document.body.innerHTML='<div id="app"></div><div id="toast"></div><div id="modal-root"></div>';await db.transact(['orders','files','settings'],tx=>{for(const name of ['orders','files','settings'])tx.objectStore(name).clear();});const app=new PrintEase({demo:true});t.after(async()=>{app.close();await window.happyDOM.abort();});await app.start();return app;}
test('student can navigate from home to a guarded draft',async t=>{const app=await setup(t);await app.backend.login(false);assert.match(document.body.textContent,/Hello, Alex/);await app.onClick({target:document.querySelector('[data-action="new-request"]'),preventDefault(){}});assert.equal(app.s.route,'request');assert.equal(document.querySelector('[data-action="review"]').disabled,true);});
test('invalid copies disable submission and updates do not replace the focused field',async t=>{const app=await setup(t);await app.backend.login(false);app.s.draft.pdf={name:'Two.pdf',pages:2,bytes:100};app.s.draft.options={...defaults(),pages:2};app.go('request');const input=document.getElementById('copies');input.focus();input.value='0';app.onInput({target:input});assert.equal(document.querySelector('[data-action="review"]').disabled,true);assert.equal(document.getElementById('copies'),input);input.value='2';app.onInput({target:input});assert.equal(document.getElementById('summary-total').textContent,'₱8.00');assert.equal(document.querySelector('[data-action="review"]').disabled,false);});
test('confirmation submits one request and updates the student list',async t=>{const app=await setup(t);await app.backend.login(false);app.s.draft={id:'ui-order',pdf:{name:'Report.pdf',pages:2,bytes:100},file:new Blob(['%PDF-example']),options:{...defaults(),pages:2},rawCopies:'1'};app.go('request');app.review();assert.match(document.getElementById('modal-root').textContent,/₱4.00/);await app.onClick({target:document.querySelector('[data-action="confirm"]'),preventDefault(){}});assert.equal(app.s.route,'orders');assert.equal(app.s.orders.filter(o=>o.id==='ui-order').length,1);assert.match(document.body.textContent,/Report.pdf/);});
test('admin can reject with a reason and the student sees it',async t=>{const app=await setup(t);await app.backend.login(true);app.go('detail','sample-1043');app.status('Rejected');const reason=document.getElementById('reject-reason');assert.equal(document.querySelector('[data-action="reject-confirm"]').disabled,true);reason.value='Please upload the final PDF.';app.onInput({target:reason});await app.onClick({target:document.querySelector('[data-action="reject-confirm"]'),preventDefault(){}});await app.backend.login(false);app.go('detail','sample-1043');assert.match(document.body.textContent,/Please upload the final PDF/);assert.match(document.body.textContent,/Rejected/);});
test('file and account names cannot inject HTML',async t=>{const app=await setup(t);await app.backend.login(false);app.s.orders[0].fileName='<img id="injected" src=x onerror=bad()>';app.render();assert.equal(document.querySelector('#injected'),null);assert.match(document.body.textContent,/<img id="injected"/);});
test('installation guide uses Safari share instructions and stays separate from Firebase auth',async t=>{const app=await setup(t);app.installHelp();assert.match(document.getElementById('modal-root').textContent,/Add to Home Screen/);assert.match(document.getElementById('modal-root').textContent,/Firebase sign-in is separate/);});

test('page selection updates the quote without replacing the focused field and rejects invalid ranges',async t=>{
 const app=await setup(t);await app.backend.login(false);app.s.draft.pdf={name:'Ten.pdf',pages:10,bytes:100};app.s.draft.options={...defaults(),pages:10};app.go('request');const input=document.getElementById('page-ranges');input.focus();input.value='1-3, 5';app.onInput({target:input});assert.equal(document.getElementById('summary-total').textContent,'₱8.00');assert.equal(document.getElementById('page-ranges'),input);input.value='11';app.onInput({target:input});assert.equal(document.querySelector('[data-action="review"]').disabled,true);assert.match(document.getElementById('range-help').textContent,/between 1 and 10/);
});
test('admin settings persist, price quotes remain stable, and paused requests cannot submit',async t=>{
 const app=await setup(t);await app.backend.login(true);app.go('settings');for(const [id,value] of [['shop-name','Nearby print shop'],['rate-bw','4']]){const input=document.getElementById(id);input.value=value;app.onInput({target:input});}await app.onSubmit({target:document.getElementById('shop-form'),preventDefault(){}});assert.equal(app.s.settings.rates.bw,4);assert.equal(app.s.route,'account');assert.match(document.body.textContent,/₱4.00/);
 await app.backend.login(false);assert.equal(app.s.orders.find(o=>o.id==='sample-1042').estimatedCost,48);app.s.draft.pdf={name:'Two.pdf',pages:2,bytes:100};app.s.draft.options={...defaults(),pages:2};app.go('request');assert.equal(document.getElementById('summary-total').textContent,'₱8.00');
 await app.backend.login(true);await app.backend.saveSettings({...app.s.settings,acceptingOrders:false});await app.backend.login(false);app.go('request');assert.equal(document.querySelector('[data-action="review"]').disabled,true);assert.match(document.body.textContent,/paused new requests/);
});
test('shop details are escaped and pickup guidance consistently refers to shops near campus',async t=>{
 const app=await setup(t);await app.backend.login(false);app.s.settings.name='<img id="shop-xss" src=x>';app.render();assert.equal(document.getElementById('shop-xss'),null);assert.doesNotMatch(document.body.textContent,/Campus Print Shop|campus print shop/);assert.match(document.body.textContent,/near campus/);
});
async function selectedFile(pages=2){const {samplePDF}=await import('../src/demo.mjs');const file=new Blob([samplePDF(pages)],{type:'application/pdf'});file.name='iPhone.pdf';return file;}
test('returning from the phone file picker preserves the signed-in account and selected PDF',async t=>{
 const app=await setup(t);await app.backend.login(false);app.go('request');const uid=app.s.session.uid;let refreshes=0;
 app.backend.refresh=async()=>{refreshes++;app.s.session=null;app.s.route='home';};
 const pending=app.pick(await selectedFile(2));window.dispatchEvent(new window.Event('focus'));await pending;
 assert.equal(refreshes,0);assert.equal(app.s.session.uid,uid);assert.equal(app.s.route,'request');assert.equal(app.s.draft.pdf.pages,2);assert.equal(app.s.draft.pdf.name,'iPhone.pdf');
 assert.match(document.body.textContent,/PDF selected/);assert.equal(document.querySelector('[data-action="review"]').disabled,false);
});
test('Safari autofilled credentials are read from the form before it is re-rendered',async t=>{
 const app=await setup(t);app.s.mode='live';app.s.auth.register=true;app.render();let received;
 app.backend.email=async(...args)=>{received=args;};
 document.getElementById('auth-name').value='Safari Student';document.getElementById('auth-email').value='safari@example.com';document.getElementById('auth-password').value='good-password-123';
 await app.onSubmit({target:document.getElementById('auth-form'),preventDefault(){}});
 assert.deepEqual(received,['safari@example.com','good-password-123','Safari Student']);
});
test('registration errors stay visible in the form and preserve the entered account details',async t=>{
 const app=await setup(t);app.s.mode='live';app.s.auth.register=true;app.render();
 app.backend.email=async()=>{throw Object.assign(new Error('disabled'),{code:'auth/operation-not-allowed'});};
 document.getElementById('auth-name').value='Safari Student';document.getElementById('auth-email').value='safari@example.com';document.getElementById('auth-password').value='good-password-123';
 await app.onSubmit({target:document.getElementById('auth-form'),preventDefault(){}});
 assert.match(document.querySelector('#auth-form [role="alert"]').textContent,/currently unavailable/);assert.equal(document.getElementById('auth-email').value,'safari@example.com');assert.equal(app.s.busy,false);
});
test('an invalid PDF displays a persistent message and keeps the previous valid selection',async t=>{
 const app=await setup(t);await app.backend.login(false);app.go('request');await app.pick(await selectedFile(2));const id=app.s.draft.id;
 const invalid=new Blob(['not a PDF file']);invalid.name='wrong.pdf';await app.pick(invalid);
 assert.equal(app.s.draft.id,id);assert.match(document.querySelector('.request-error').textContent,/valid PDF/);assert.equal(app.s.fileReading,false);
});
test('a local storage failure keeps a valid PDF selected in memory',async t=>{
 const app=await setup(t);await app.backend.login(false);app.go('request');const database=await db.database(),original=database.transaction;
 database.transaction=function(stores,...rest){if(Array.isArray(stores)&&stores.includes('files'))throw new Error('Quota exceeded');return original.call(this,stores,...rest);};
 try{await app.pick(await selectedFile(2));assert.equal(app.s.draft.pdf.pages,2);assert.match(document.querySelector('.draft-warning').textContent,/Keep this tab open/);assert.equal(document.querySelector('[data-action="review"]').disabled,false);}finally{database.transaction=original;}
});

test('Google profile photos appear in the header and Account with a safe initials fallback',async t=>{
 const app=await setup(t);await app.backend.login(false);
 app.s.session={...app.s.session,photoUrl:'https://lh3.googleusercontent.com/profile-photo',provider:'google'};app.render();
 assert.ok(document.querySelector('.top-actions .user-avatar img.profile-photo'));
 app.go('account');assert.ok(document.querySelector('.profile-avatar img.profile-photo'));
 const photo=document.querySelector('.profile-avatar img');photo.dispatchEvent(new window.Event('error'));
 assert.equal(document.querySelector('.profile-avatar img'),null);assert.match(document.querySelector('.profile-avatar').textContent,/A/);
});
test('a profile photo cannot inject markup or use an untrusted URL',async t=>{
 const app=await setup(t);await app.backend.login(false);
 for(const photoUrl of ['javascript:alert(1)','https://googleusercontent.com.evil.example/picture','https://user:password@lh3.googleusercontent.com/picture']){
  app.s.session={...app.s.session,photoUrl};app.go('account');assert.equal(document.querySelector('.profile-avatar img'),null);
 }
});
