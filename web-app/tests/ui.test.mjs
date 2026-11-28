import test from 'node:test';
import assert from 'node:assert/strict';
import {Window} from 'happy-dom';
import 'fake-indexeddb/auto';
import {webcrypto} from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {runInNewContext} from 'node:vm';
import {mountPreviewPages} from '../src/pdf-preview.mjs';
import * as db from '../src/db.mjs';
import {defaults} from '../src/model.mjs';
globalThis.__PE_TEST__=true;
const {PrintEase}=await import('../src/app.mjs');
async function setup(t){const window=new Window({url:'https://test.printease.example/'});for(const name of ['window','document','location','history','localStorage','navigator'])Object.defineProperty(globalThis,name,{configurable:true,value:window[name]});Object.defineProperty(globalThis,'crypto',{configurable:true,value:webcrypto});window.HTMLDialogElement.prototype.showModal=function(){this.open=true;};window.HTMLDialogElement.prototype.close=function(){this.open=false;};document.body.innerHTML='<div id="app"></div><div id="toast"></div><div id="modal-root"></div>';await db.transact(['orders','files','settings'],tx=>{for(const name of ['orders','files','settings'])tx.objectStore(name).clear();});const app=new PrintEase({demo:true});t.after(async()=>{app.close();await window.happyDOM.abort();});await app.start();return app;}
test('student can navigate from home to a guarded draft',async t=>{const app=await setup(t);await app.backend.login(false);assert.match(document.body.textContent,/Hello, Alex/);await app.onClick({target:document.querySelector('[data-action="new-request"]'),preventDefault(){}});assert.equal(app.s.route,'request');assert.equal(document.querySelector('[data-action="review"]').disabled,true);});
test('the customer Home puts the print action first and keeps existing drafts reachable',async t=>{
 const app=await setup(t);await app.backend.login(false);
 const firstAction=()=>document.querySelector('.customer-home .hero button');
 assert.equal(firstAction().textContent,'New print request');
 assert.equal(firstAction().dataset.action,'new-request');
 assert.ok(firstAction().classList.contains('lime'));
 const home = document.querySelector('.customer-home');
 const cards = Array.from(home.children);
 assert.ok(cards.indexOf(home.querySelector('.hero')) < cards.indexOf(home.querySelector('.shop-card')));
 assert.ok(cards.indexOf(home.querySelector('.shop-card')) < cards.indexOf(home.querySelector('.queue-status-card')));
 app.s.draft.pdf={name:'Draft.pdf',pages:1,bytes:100};
 app.s.settingsError='Shop details could not sync.';
 app.render();
 assert.equal(firstAction().textContent,'Continue your draft');
 assert.equal(firstAction().disabled,false);
 assert.equal(document.querySelector('.customer-home .pickup-card [data-action="order"]').textContent,'View request');
});
test('appearance follows the system until toggled and preserves the current screen', async t => {
 const app = await setup(t);
 const script = await readFile(new URL('../dist/assets/theme.js', import.meta.url), 'utf8');
 const meta = document.createElement('meta');
 meta.name = 'theme-color';
 document.head.append(meta);
 const media = new window.EventTarget();
 media.matches = true;
 window.matchMedia = () => media;
 runInNewContext(script, {window, document});
 assert.equal(document.documentElement.dataset.theme, 'dark');
 assert.equal(localStorage.getItem('pe_theme'), null);
 app.go('home');
 const toggle = () => document.querySelector('[data-theme-toggle]');
 assert.equal(toggle().getAttribute('aria-pressed'), 'true');
 const signIn = document.querySelector('[data-action="demo-student"]');
 toggle().click();
 assert.equal(document.documentElement.dataset.theme, 'light');
 assert.equal(meta.content, '#f7f7f5');
 assert.equal(localStorage.getItem('pe_theme'), 'light');
 assert.equal(toggle().getAttribute('aria-pressed'), 'false');
 assert.equal(signIn, document.querySelector('[data-action="demo-student"]'));
 media.dispatchEvent(new window.Event('change'));
 assert.equal(document.documentElement.dataset.theme, 'light');
 await app.backend.login(false);
 assert.ok(document.querySelector('.top-actions [data-theme-toggle]'));
 toggle().click();
 assert.equal(meta.content, '#17181a');
 assert.equal(localStorage.getItem('pe_theme'), 'dark');
 const restored = new Window({url: window.location.href});
 t.after(() => restored.happyDOM.abort());
 restored.localStorage.setItem('pe_theme', localStorage.getItem('pe_theme'));
 restored.matchMedia = () => new restored.EventTarget();
 runInNewContext(script, {window: restored, document: restored.document});
 assert.equal(restored.document.documentElement.dataset.theme, 'dark');
});
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
test('a stalled file read times out and lets the customer choose a file again', async t => {
 const app = await setup(t);
 await app.backend.login(false);
 app.go('request');
 t.mock.timers.enable({apis: ['setTimeout']});
 let reading;
 const started = new Promise(resolve => { reading = resolve; });
 const stalled = await selectedFile();
 stalled.name = 'Cloud slides.pdf';
 stalled.arrayBuffer = () => { reading(); return new Promise(() => {}); };
 const prepared = app.pick(stalled);
 await started;
 assert.match(document.querySelector('.upload [role="status"]').textContent, /Reading file · File 1 of 1/);
 t.mock.timers.tick(20_000);
 await prepared;
 assert.equal(app.s.busy, false);
 assert.match(document.querySelector('.request-error').textContent, /took too long.*Download it to your device/);
 assert.equal(document.getElementById('pdf-input').disabled, false);
 await app.pick(await selectedFile(2));
 assert.equal(app.s.draft.pdf.pages, 2);
});
test('canceling a slow replacement preserves the previous PDF even if the read finishes later', async t => {
 const app = await setup(t);
 await app.backend.login(false);
 app.go('request');
 await app.pick(await selectedFile(2));
 const initial = app.s.draft;
 const replacement = await selectedFile(4);
 const bytes = await replacement.arrayBuffer();
 let finish, reading;
 const started = new Promise(resolve => { reading = resolve; });
 replacement.arrayBuffer = () => new Promise(resolve => { finish = resolve; reading(); });
 const prepared = app.pick(replacement);
 await started;
 await app.onClick({target: document.querySelector('[data-action="cancel-preparation"]'), preventDefault() {}});
 await prepared;
 assert.equal(app.s.busy, false);
 assert.equal(app.s.draft, initial);
 assert.equal(document.querySelector('.request-error'), null);
 finish(bytes);
 await new Promise(resolve => setImmediate(resolve));
 assert.equal(app.s.draft, initial);
});
test('a stalled draft backup does not keep a ready PDF locked', async t => {
 const app = await setup(t);
 await app.backend.login(false);
 app.go('request');
 const database = await db.database();
 const original = database.transaction;
 t.mock.timers.enable({apis: ['setTimeout']});
 let saving, aborted = false;
 const started = new Promise(resolve => { saving = resolve; });
 database.transaction = function(stores, mode) {
  if (!stores.includes('files') || mode !== 'readwrite') return original.call(this, stores, mode);
  return {
   objectStore: () => ({put: saving}),
   abort() { aborted = true; this.onabort(); }
  };
 };
 t.after(() => { database.transaction = original; });
 const prepared = app.pick(await selectedFile());
 await started;
 t.mock.timers.tick(5_000);
 await prepared;
 assert.equal(aborted, true);
 assert.equal(app.s.busy, false);
 assert.match(document.querySelector('.draft-warning').textContent, /Keep this tab open/);
 assert.equal(document.querySelector('[data-action="review"]').disabled, false);
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

test("slides per side are reflected in the estimate, review and stored PDF", async t => {
 const app = await setup(t);
 await app.backend.login(false);
 app.go("request");
 await app.pick(await selectedFile(12));
 const id = app.s.draft.id;
 const layout = document.getElementById("slides-per-side");
 assert.deepEqual([...layout.options].map(option => Number(option.value)), [1,2,4,6,9]);
 layout.value = '4';
 app.onInput({target:layout});
 assert.equal(document.getElementById('summary-total').textContent, '₱6.00');
 assert.match(document.getElementById('summary-formula').textContent, /3 printed sides/);
 app.review();
 assert.match(document.querySelector("#modal-root").textContent, /Slides per side4 · 3 printed sides per copy/);
 await app.onClick({target:document.querySelector('[data-action="confirm"]'),preventDefault(){}});
 const order = app.s.orders.find(order => order.id === id);
 assert.equal(order.options.pages, 3);
 assert.equal(order.estimatedCost, 6);
 assert.equal(order.fileName, 'iPhone (4 slides per side).pdf');
 const {openPdf} = await import('../src/pdf.mjs');
 const doc = await openPdf(await app.backend.bytes(order));
 try { assert.equal(doc.numPages, 3); } finally { await doc.destroy(); }
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
 const file = await selectedFile(3);
 file.name = "Cover.pdf";
 await app.pickFiles([file], true);
 input("file-0-paper","A3");
 input("file-0-slidesPerSide","2");
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
 assert.equal(saved.attachments[0].options.pages,2);
 assert.equal(saved.attachments[0].fileName,"Cover (2 slides per side).pdf");
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
 const first=await selectedFile(2),second=await selectedFile(3),third=await selectedFile(4);
 first.name='Report.pdf';second.name='Notes.pdf';third.name='Cover.pdf';
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
 const cards = [...document.querySelectorAll('.file-options')];
 const names = ['Report.pdf', 'Notes.pdf', 'Cover.pdf'];
 assert.deepEqual(cards.map(card => card.firstElementChild.querySelector('h3').textContent), names);
 assert.deepEqual(cards.map(card => document.getElementById(card.getAttribute('aria-labelledby')).textContent), names);
 assert.deepEqual(cards.map(card => [...card.querySelectorAll('[data-print-field]')].map(field => field.dataset.printField)),
   Array(3).fill(['ranges', 'paper', 'slidesPerSide', 'actualSize', 'copies', 'color', 'duplex']));
 assert.deepEqual(cards.map(card => card.querySelector('input[type=number]').id), ['copies', 'file-0-copies', 'file-1-copies']);
 for (const [id, value] of [['copies', '2'], ['file-0-copies', '3'], ['file-1-copies', '4']]) {
  const target = document.getElementById(id);
  target.value = value;
  app.onInput({target});
 }
 for (const id of ['file-0-color', 'file-1-duplex']) {
  const target = document.getElementById(id);
  target.checked = true;
  app.onInput({target});
 }
 const edit = (id, value) => {
  const target = document.getElementById(id);
  assert.ok(target, id);
  target.value = value;
  app.onInput({target});
 };
 assert.ok(document.getElementById('page-ranges').disabled);
 assert.ok(document.getElementById('file-0-ranges').disabled);
 assert.ok(document.getElementById('file-1-ranges').disabled);
 edit('file-1-paper', 'Custom');
 assert.ok(document.querySelector('[data-action="review"]').disabled);
 edit('file-1-paperWidth', '200');
 edit('file-1-paperHeight', '330');
 app.review();
 const reviewFiles = [...document.querySelectorAll('.review-request-files section')];
 assert.deepEqual(reviewFiles.map(file => file.querySelector('h3').textContent), names);
 assert.equal(document.activeElement, reviewFiles[0].querySelector('h3'));
 assert.deepEqual(reviewFiles.map(file => file.querySelector('dd').textContent),
   ['All 2 pages', 'All 3 pages', 'All 4 pages']);
 assert.deepEqual(reviewFiles.map(file => [...file.querySelectorAll('dd')].slice(2, 5).map(option => option.textContent)), [
  ['2', 'Black & white', 'Single-sided'],
  ['3', 'Color', 'Single-sided'],
  ['4', 'Black & white', 'Double-sided']
 ]);
 await app.onClick({target:document.querySelector('[data-action="confirm"]'),preventDefault(){}});
 const submitted = app.s.orders.find(order => order.fileName === names[0]);
 assert.equal(submitted.options.copies, 2);
 assert.equal(submitted.attachments[1].options.paper, 'Custom 200x330 mm');
 assert.deepEqual(submitted.attachments.map(({fileName,options}) => [fileName,options.copies,options.color,options.duplex]), [
  ['Notes.pdf',3,true,false], ['Cover.pdf',4,false,true]
 ]);
 app.go('orders');
 const shortcut=document.querySelector('.compact-action');
 assert.equal(shortcut.getAttribute('aria-label'),'New print request');
 assert.equal(shortcut.querySelectorAll('svg').length,1);
});
test('remove buttons promote the next file with its own settings and clear the last file', async t => {
 const app = await setup(t);
 await app.backend.login(false);
 app.go('request');
 const first = await selectedFile(1), second = await selectedFile(2), third = await selectedFile(3);
 first.name = 'Report.pdf';
 second.name = 'Notes.pdf';
 third.name = 'Cover.pdf';
 await app.pickFiles([first, second, third]);
 const copies = document.getElementById('file-0-copies');
 copies.value = '7';
 app.onInput({target: copies});
 const paper = document.getElementById('file-0-paper');
 paper.value = 'A3';
 app.onInput({target: paper});
 const remove = index => app.onClick({
  target: document.getElementById('remove-print-file-' + index),
  preventDefault() {}
 });
 assert.equal(document.getElementById('remove-print-file-0').getAttribute('aria-label'), 'Remove Report.pdf');
 await remove(0);
 assert.equal(app.s.draft.pdf.name, 'Notes.pdf');
 assert.equal(app.s.draft.options.pages, 2);
 assert.equal(document.getElementById('copies').value, '7');
 assert.equal(app.s.draft.options.paper, 'A3');
 assert.equal(document.querySelector('#paper-size option[selected]').value, 'A3');
 assert.match(document.getElementById('print-file-2').textContent, /Cover/);
 assert.equal((await db.get('settings', app.draftKey)).value.pdf.name, 'Notes.pdf');
 assert.equal((await db.get('files', app.draftKey)).blob.size, app.s.draft.file.size);
 await remove(1);
 assert.equal(document.querySelectorAll('.file-options [data-action="remove-print-file"]').length, 1);
 assert.ok(!document.getElementById('page-ranges').disabled);
 await remove(0);
 assert.equal(app.s.draft.pdf, null);
 assert.equal(await db.get('files', app.draftKey), undefined);
 assert.ok(document.querySelector('[data-action="review"]').disabled);
 assert.ok(document.getElementById('pdf-input').multiple);
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
test('PDF preview scrolls through pages, jumps to a page, and releases distant canvases', async t => {
 const window = new Window();
 t.after(() => window.happyDOM.abort());
 const area = window.document.createElement('div');
 window.document.body.append(area);
 Object.defineProperty(area, 'clientHeight', {value: 400});
 Object.defineProperty(area, 'scrollHeight', {value: 4800});
 let current;
 const preview = mountPreviewPages(area, {
  pages: 8,
  initialPage: 1,
  async renderPage(page, canvas) {
   await Promise.resolve();
   canvas.width = 420;
   canvas.height = 600;
   return page === 8;
  },
  onPage(page, clipped) { current = {page, clipped}; }
 });
 t.after(() => preview.dispose());
 for (const [index, sheet] of [...area.children].entries()) {
  Object.defineProperty(sheet, 'offsetTop', {value: index * 600});
 }
 const settle = () => new Promise(resolve => setImmediate(resolve));
 await settle();
 assert.equal(area.children.length, 8);
 assert.equal(area.querySelector('[data-page="1"] canvas').getAttribute('aria-label'), 'Page 1 of 8');
 area.scrollTop = 600;
 area.dispatchEvent(new window.Event('scroll'));
 await settle();
 assert.equal(current.page, 2);
 assert.ok(area.querySelector('[data-page="2"] canvas'));
 preview.jump(8);
 await settle();
 assert.equal(area.scrollTop, 4200);
 assert.deepEqual(current, {page: 8, clipped: true});
 assert.equal(area.querySelector('[data-page="1"] canvas'), null);
 preview.dispose();
 area.scrollTop = 0;
 area.dispatchEvent(new window.Event('scroll'));
 assert.equal(current.page, 8);
});
test('shop print opens the PC print dialog with selected pages and keeps Download PDF available', async t => {
 const app = await setup(t);
 await app.backend.login(true);
 const order = app.s.orders[0];
 order.options = {
  ...order.options, paper: 'Letter', pages: 6, copies: 2, color: false, duplex: true,
  pageRanges: [{start:2,end:3},{start:5,end:5}]
 };
 app.go('detail', order.id);
 let release;
 app.backend.bytes = () => new Promise(resolve => { release = resolve; });
 const rendered = [];
 const context = {
  fillRect() {}, save() {}, beginPath() {}, rect() {}, clip() {}, restore() {},
  strokeRect() { assert.fail('Margin guides should not print'); }
 };
 window.HTMLCanvasElement.prototype.getContext = () => context;
 window.HTMLCanvasElement.prototype.toBlob = callback => callback(new Blob(['page'], {type:'image/jpeg'}));
 window.HTMLImageElement.prototype.decode = async () => {};
 let printCalls = 0;
 window.print = () => {
  printCalls++;
  const job = document.getElementById('print-job');
  assert.deepEqual([...job.querySelectorAll('img')].map(image => image.alt), ['PDF page 2','PDF page 3','PDF page 5']);
  assert.match(document.head.querySelector('style[media="print"]').textContent, /size: 612pt 792pt/);
  window.dispatchEvent(new window.Event('afterprint'));
 };
 const loading = app.onClick({target:document.querySelector('[data-action="order-preview"]'),preventDefault(){}});
 const print = document.querySelector('[data-action="preview-print"]');
 assert.equal(print.disabled, true);
 assert.equal(document.getElementById('pdf-download').textContent.trim(), 'Download PDF');
 app.preview.doc = {
  numPages: 6,
  async getPage(number) {
   return {
    getViewport: () => ({width: 612, height: 792}),
    render() { rendered.push(number); return {promise: Promise.resolve(), cancel() {}}; }
   };
  },
  async destroy() {}
 };
 print.disabled = false;
 await app.onClick({target:print,preventDefault(){}});
 assert.equal(printCalls, 1);
 assert.deepEqual(rendered, [2,3,5]);
 assert.match(document.getElementById('print-job-status').textContent, /2 copies/);
 assert.equal(document.getElementById('print-job'), null);
 assert.equal(order.printedFiles?.length ?? 0, 0);
 app.closeModal();
 release(new Uint8Array());
 await loading;
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
