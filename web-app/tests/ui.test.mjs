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
test('the student greeting reads a surname-first profile without its separating comma',async t=>{
 const app=await setup(t);await app.backend.login(false);
 app.s.session.name='Villena, Ana Marie';app.render();
 assert.equal(document.querySelector('h1').textContent,'Hello, Ana.');
 app.s.session.name='Ana Villena, Jr.';app.render();
 assert.equal(document.querySelector('h1').textContent,'Hello, Ana.');
});
test('invalid copies disable submission and updates do not replace the focused field',async t=>{const app=await setup(t);await app.backend.login(false);app.s.draft.pdf={name:'Two.pdf',pages:2,bytes:100};app.s.draft.options={...defaults(),pages:2};app.go('request');const input=document.getElementById('copies');input.focus();input.value='0';app.onInput({target:input});assert.equal(document.querySelector('[data-action="review"]').disabled,true);assert.equal(document.getElementById('copies'),input);input.value='2';app.onInput({target:input});assert.equal(document.getElementById('summary-total').textContent,'₱8.00');assert.equal(document.querySelector('[data-action="review"]').disabled,false);});
test('confirmation submits one request and updates the student list',async t=>{const app=await setup(t);await app.backend.login(false);app.s.draft={id:'ui-order',pdf:{name:'Report.pdf',pages:2,bytes:100},file:new Blob(['%PDF-example']),options:{...defaults(),pages:2},rawCopies:'1'};app.go('request');app.review();assert.match(document.getElementById('modal-root').textContent,/₱4.00/);await app.onClick({target:document.querySelector('[data-action="confirm"]'),preventDefault(){}});assert.equal(app.s.route,'orders');assert.equal(app.s.orders.filter(o=>o.id==='ui-order').length,1);assert.match(document.body.textContent,/Report.pdf/);assert.match(document.body.textContent,/Request ID ui-order/);});
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
 const app=await setup(t);await app.backend.login(false);
 assert.equal(document.querySelector('.shop-logo').getAttribute('src'),'/assets/nano-print-alangilan.jpg');
 app.s.settings.name='<img id="shop-xss" src=x>';app.render();
 assert.equal(document.querySelector('.shop-logo'),null);assert.equal(document.getElementById('shop-xss'),null);assert.doesNotMatch(document.body.textContent,/Campus Print Shop|campus print shop/);assert.match(document.body.textContent,/near campus/);
});
async function selectedFile(pages=2){const {samplePDF}=await import('../src/demo.mjs');const file=new Blob([samplePDF(pages)],{type:'application/pdf'});file.name='iPhone.pdf';return file;}
test('returning from the phone file picker preserves the signed-in account and selected PDF',async t=>{
 const app=await setup(t);await app.backend.login(false);app.go('request');const uid=app.s.session.uid;let refreshes=0;
 app.backend.refresh=async()=>{refreshes++;app.s.session=null;app.s.route='home';};
 const pending=app.pick(await selectedFile(2));window.dispatchEvent(new window.Event('focus'));await pending;
 assert.equal(refreshes,0);assert.equal(app.s.session.uid,uid);assert.equal(app.s.route,'request');assert.equal(app.s.draft.pdf.pages,2);assert.equal(app.s.draft.pdf.name,'iPhone.pdf');
 assert.match(document.body.textContent,/File ready to preview/);assert.equal(document.querySelector('[data-action="review"]').disabled,false);
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

test('pickup estimates are admin-only, survive refresh, and preserve quoted costs',async t=>{
 const app=await setup(t);await app.backend.login(false);await assert.rejects(()=>app.backend.setPickupTime('sample-1042',Date.now()+60000),/Only the shop/);
 await app.backend.login(true);const total=app.s.orders.find(o=>o.id==='sample-1042').estimatedCost;await app.backend.setPickupTime('sample-1042',Date.now()+1800000);
 await app.backend.login(false);app.go('detail','sample-1042');assert.match(document.body.textContent,/Estimated ready/);assert.equal(app.s.orders.find(o=>o.id==='sample-1042').estimatedCost,total);
});
test('staff handover requires a matching collection code before completing the request',async t=>{
 const app=await setup(t);await app.backend.login(true);await app.backend.transition('sample-1042','Ready for Pickup');app.go('detail','sample-1042');app.status('Completed');
 const input=document.getElementById('collection-code');input.value='wrong';app.onInput({target:input});assert.equal(document.querySelector('[data-action="collect-confirm"]').disabled,true);
 input.value='LE1042';app.onInput({target:input});assert.equal(document.querySelector('[data-action="collect-confirm"]').disabled,false);
 await app.onClick({target:document.querySelector('[data-action="collect-confirm"]'),preventDefault(){}});assert.equal(app.s.orders.find(o=>o.id==='sample-1042').status,'Completed');
});
test('repeat printing prepares a fresh draft using current prices without submitting it',async t=>{
 const app=await setup(t);await app.backend.login(false);await app.backend.transition('sample-1043','Cancelled');const order=app.s.orders.find(o=>o.id==='sample-1043'),before=app.s.orders.length;
 await app.backend.login(true);await app.backend.saveSettings({...app.s.settings,rates:{bw:5,color:15,legalExtra:2}});await app.backend.login(false);
 await app.prepareReorder(order);app.render();assert.equal(app.s.route,'request');assert.notEqual(app.s.draft.id,order.id);assert.equal(app.s.orders.length,before);assert.equal(app.s.draft.pdf.pages,4);assert.equal(document.getElementById('summary-total').textContent,'₱60.00');
});
test('a failed repeat download preserves the unfinished draft',async t=>{
 const app=await setup(t);await app.backend.login(false);await app.pick(await selectedFile(2));const id=app.s.draft.id;app.backend.bytes=async()=>{throw new Error('Offline');};
 await assert.rejects(()=>app.prepareReorder(app.s.orders[0]),/Offline/);assert.equal(app.s.draft.id,id);assert.equal(app.s.draft.pdf.pages,2);
});

test("confirming rates keeps requests paused", async t => {
  const app = await setup(t);
  await app.backend.login(true);
  await app.backend.saveSettings({ ...app.s.settings, acceptingOrders:false });
  app.go("settings");
  const checkbox = document.getElementById("shop-ratesConfirmed");
  checkbox.checked = true;
  app.onInput({ target:checkbox });
  await app.onSubmit({ target:document.getElementById("shop-form"), preventDefault() {} });
  assert.equal(app.s.settings.ratesConfirmed, true);
  assert.equal(app.s.settings.acceptingOrders, false);
});

test("a quoted request shows the confirmed amount and approval action", async t => {
  const app = await setup(t);
  await app.backend.login(true);
  await app.backend.reviewOrder("sample-1043", "quote", -1, { quote:45, note:"Quote from the shop" });
  await app.backend.login(false);
  app.go("detail", "sample-1043");
  assert.match(document.querySelector(".review-panel").textContent, /₱45\.00/);
  await app.onClick({ target:document.querySelector('[data-action=approve-quote]'), preventDefault() {} });
  await app.onClick({ target:document.querySelector('[data-action=confirm]'), preventDefault() {} });
  assert.equal(app.s.orders.find(o=>o.id==="sample-1043").review.stage, "approved");
});

test("custom sizes and additional files use a shop quote and keep separate print choices", async t => {
 const app = await setup(t);
 await app.backend.login(false);
 app.go("request");
 await app.pick(await selectedFile(2));
 const input = (id,value) => {
  const target = document.getElementById(id);
  assert.ok(target, id);
  target.value = value;
  app.onInput({target});
 };
 input("paper-size","Custom");
 assert.equal(document.querySelector('[data-action="review"]').disabled,true);
 input("paper-width","203");
 input("paper-height","330");
 assert.equal(document.getElementById("summary-paper").textContent,"203 × 330 mm (custom)");
 assert.equal(document.getElementById("summary-total").textContent,"Shop quote");
 const file = await selectedFile(1);
 file.name = "Cover.pdf";
 await app.pickFiles([file], true);
 input("file-0-paper","A3");
 input("file-0-copies","0");
 assert.equal(document.querySelector('[data-action="review"]').disabled,true);
 input("file-0-copies","2");
 const id = app.s.draft.id;
 app.review();
 assert.match(document.querySelector("#modal-root").textContent,/Cover\.pdf/);
 await app.onClick({target:document.querySelector('[data-action="confirm"]'),preventDefault(){}});
 const saved = app.s.orders.find(order=>order.id===id);
 assert.equal(saved.options.paper,"Custom 203x330 mm");
 assert.equal(saved.attachments[0].options.paper,"A3");
 assert.equal(saved.attachments[0].options.copies,2);
 assert.equal(saved.review.stage,"submitted");
});

test("shop queue separates orders waiting on the student from work staff can act on", async t => {
 const app = await setup(t);
 await app.backend.login(true);
 await app.backend.reviewOrder("sample-1043","quote",-1,{quote:42});
 app.go("orders");
 assert.ok(document.querySelector('[aria-label="Waiting for response"] [data-id="sample-1043"]'));
 assert.ok(document.querySelector('[aria-label="Printing"] [data-id="sample-1042"]'));
});

test('customers see aggregate workload while the header syncs without a refresh icon',async t=>{
 const app=await setup(t);await app.backend.login(false);
 const queue=document.querySelector('.queue-status-card');
 assert.match(queue.textContent,/Shop queue/);
 assert.match(queue.textContent,/0 waiting/);
 assert.match(queue.textContent,/1 printing/);
 assert.equal(document.querySelector('.topbar [data-action="refresh"]'),null);
 app.go('request');
 assert.ok(document.querySelector('.queue-status-card'));
});
test('customer accounts omit a role badge and shop accounts keep their administrator label',async t=>{
 const app=await setup(t);await app.backend.login(false);app.go('account');
 const profile=()=>document.querySelector('.account-grid>section');
 assert.doesNotMatch(profile().textContent,/\bStudent\b|Shop administrator/);
 await app.backend.login(true);app.go('account');
 assert.match(profile().textContent,/Shop administrator/);
});
test('the top picker accepts a batch and keeps print settings close to each file',async t=>{
 const app=await setup(t);await app.backend.login(false);app.go('request');
 const picker=document.querySelector('#pdf-input');
 assert.equal(picker.multiple,true);
 const first=await selectedFile(2),second=await selectedFile(3),third=await selectedFile(1);
 second.name='Notes.pdf';third.name='Cover.pdf';
 Object.defineProperty(picker,'files',{value:[first,second]});
 await app.onInput({target:picker,type:'change'});
 assert.equal(app.s.draft.attachments[0].pdf.name,'Notes.pdf');
 const add=document.querySelector('#attachment-input');
 assert.equal(add.multiple,true);
 assert.equal(add.closest('.step'),document.querySelector('#pdf-input').closest('.step'));
 Object.defineProperty(add,'files',{value:[third]});
 await app.onInput({target:add,type:'change'});
 assert.equal(app.s.draft.attachments.length,2);
 assert.equal(document.querySelector('#attachment-input'),null);
 assert.doesNotMatch(document.querySelector('#main').textContent,/Additional files|Add another file/);
 app.go('orders');
 const shortcut=document.querySelector('.compact-action');
 assert.equal(shortcut.getAttribute('aria-label'),'New print request');
 assert.equal(shortcut.querySelectorAll('svg').length,1);
});
test('applying a custom image size saves the updated file and preserves the draft ID',async t=>{
 const app=await setup(t);await app.backend.login(false);app.go('request');
 const {imagePdf}=await import('../src/print-file.mjs');
 await app.pick(new File([imagePdf(new Uint8Array([255,216,255,217]),600,300)],'Photo.pdf'));
 const id=app.s.draft.id;
 document.querySelector('#image-size-0').value='37';
 await app.onClick({target:document.querySelector('[data-action="resize-image"]'),preventDefault(){}});
 assert.equal(app.s.draft.id,id);assert.equal(app.s.draft.pdf.imagePercent,37);
 const stored=await db.get('files',app.draftKey);
 assert.match(await stored.blob.text(),/%PrintEaseImage 37/);
});
test('paper stock is editable and blocks an unavailable size in an existing student draft',async t=>{
 const app=await setup(t);await app.backend.login(true);app.go('settings');
 const folio=document.querySelector('[data-stock="Folio"]');
 folio.value='out';app.onInput({target:folio});
 await app.onSubmit({target:document.getElementById('shop-form'),preventDefault(){}});
 assert.deepEqual(app.s.settings.paperStock.out,['Folio']);
 await app.backend.login(false);
 app.s.draft.pdf={name:'Lecture.pdf',pages:1,bytes:200};
 app.s.draft.options={...defaults(),paper:'Folio'};
 app.go('request');
 assert.match(document.body.textContent,/Out of stock. Choose another paper size/);
 assert.equal(document.querySelector('#paper-size option[value="A4"]').textContent,'A4');
 assert.match(document.querySelector('#paper-size optgroup:nth-child(2)').label,/Quote required/);
 assert.equal(document.querySelector('[data-action="review"]').disabled,true);
 const paper=document.querySelector('#paper-size');paper.value='Legal';app.onInput({target:paper});
 const scale=document.querySelector('#scale');scale.value='actual';app.onInput({target:scale});
 assert.equal(document.querySelector('#summary-scale').textContent,'Actual size');
 assert.equal(document.querySelector('[data-action="review"]').disabled,false);
});

test('staff checklist saves each printed file and unlocks pickup when complete',async t=>{
 const app=await setup(t);await app.backend.login(true);app.go('detail','sample-1042');
 const ready=()=>document.querySelector('[data-status="Ready for Pickup"]');
 assert.equal(ready().disabled,true);
 await app.onClick({target:document.querySelector('[data-action="mark-printed"]'),preventDefault(){}});
 assert.match(document.querySelector('.print-checklist').textContent,/1 of 1 printed/);
 assert.equal(ready().disabled,false);
 await app.onClick({target:document.querySelector('[data-action="mark-printed"]'),preventDefault(){}});
 assert.equal(ready().disabled,true);
});
