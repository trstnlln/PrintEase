import test from 'node:test';
import assert from 'node:assert/strict';
import {createWorker} from '../worker/index.mjs';
import {samplePDF} from '../src/demo.mjs';
import {MemoryBucket} from './fixtures/memory-bucket.mjs';
const SITE='https://test.printease.example',projectId='demo-printease';
const token=(uid,extra={})=>btoa(JSON.stringify({alg:'RS256'}))+'.'+btoa(JSON.stringify({sub:uid,aud:projectId,iss:`https://securetoken.google.com/${projectId}`,exp:9999999999,...extra}))+'.signature';
async function setup(){
 const bucket=new MemoryBucket(),bytes=samplePDF(2),hash=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes)),n=>n.toString(16).padStart(2,'0')).join('');
 const state={denyToken:false,disabled:false,validSince:0,order:null,service:null,settings:null,queryRows:null,roles:new Set(),referenced:false,denyQuery:false,lookups:0};
 const worker=createWorker({}, {projectId,apiKey:'test-key',origin:SITE,fetchImpl:async(url,init)=>{
  if(url.includes('accounts:lookup')){state.lookups++;const {idToken}=JSON.parse(init.body);if(state.denyToken)return new Response('{}',{status:400});const uid=JSON.parse(atob(idToken.split('.')[1])).sub;return Response.json({users:[{localId:uid,disabled:state.disabled,validSince:state.validSince}]});}
  if(url.endsWith('/documents:runQuery')){if(state.queryRows)return Response.json(state.queryRows);if(state.denyQuery)return new Response('{}',{status:403});return Response.json(state.referenced?[{document:{name:'bound'}}]:[{readTime:'now'}]);}
  if(url.includes('/documents/orders/'))return state.order?Response.json(state.order):new Response('{}',{status:404});
  if(url.includes('/documents/shopSettings/current'))return state.settings?Response.json(state.settings):new Response('{}',{status:404});
  if(url.includes('/documents/serviceRequests/')){
   if(init.method==='PATCH' && state.service) Object.assign(state.service.fields, JSON.parse(init.body).fields);
   return state.service?Response.json(state.service):new Response('{}',{status:404});
  }
  if(url.includes('/documents/admins/')){const uid=url.split('/').at(-1);return state.roles.has(uid)?Response.json({fields:{enabled:{booleanValue:true}}}):new Response('{}',{status:404});}
  throw new Error('Unexpected backend request');
 }});
 const call=(path,{uid='student',method='GET',body,headers={},jwt=token(uid)}={})=>worker.fetch(new Request(SITE+path,{method,headers:{...(jwt?{Authorization:'Bearer '+jwt}:{}),...(body?{'Content-Type':'application/pdf'}:{}),...headers},body}),{BUCKET:bucket});
 const order=owner=>({fields:{ownerId:{stringValue:owner},storagePath:{stringValue:`hosted/${owner}/${hash}.pdf`},fileHash:{stringValue:hash},documentStore:{stringValue:'hosted'}}});
 return {bucket,bytes,hash,state,call,order,worker};
}
test('private uploads require a live Firebase-verified token, not just decoded claims',async()=>{
 const s=await setup();
 assert.equal((await s.call('/api/uploads/'+s.hash,{jwt:null})).status,401);
 assert.equal((await s.call('/api/uploads/'+s.hash,{jwt:token('student',{aud:'other-project'})})).status,401);
 s.state.disabled=true;assert.equal((await s.call('/api/uploads/'+s.hash)).status,401);s.state.disabled=false;
 s.state.validSince=100;assert.equal((await s.call('/api/uploads/'+s.hash,{jwt:token('student',{auth_time:99})})).status,401);s.state.validSince=0;
 s.state.denyToken=true;assert.equal((await s.call('/api/uploads/'+s.hash)).status,401);assert.equal(s.state.lookups,3);
});
test('upload hashes are verified and retries do not overwrite immutable PDF bytes',async()=>{
 const s=await setup(),path='/api/uploads/'+s.hash;
 assert.equal((await s.call(path,{method:'PUT',body:s.bytes})).status,200);
 assert.equal((await s.call(path,{method:'PUT',body:s.bytes})).status,200);assert.equal(s.bucket.puts,1);
 const read=await s.call(path);assert.equal(read.headers.get('Cache-Control'),'private, no-store');assert.deepEqual(new Uint8Array(await read.arrayBuffer()),s.bytes);
 assert.equal((await s.call(path,{uid:'other'})).status,404);
 assert.equal((await s.call('/api/uploads/'+'a'.repeat(64),{method:'PUT',body:s.bytes})).status,409);
});
test('upload size, MIME type and PDF signature are bounded at the server',async()=>{
 const s=await setup(),path='/api/uploads/'+s.hash;
 assert.equal((await s.call(path,{method:'PUT',body:new Uint8Array(15*1024*1024+1)})).status,413);
 assert.equal((await s.call(path,{method:'PUT',body:s.bytes,headers:{'Content-Length':String(15*1024*1024+1)}})).status,413);
 assert.equal((await s.call(path,{method:'PUT',body:s.bytes,headers:{'Content-Length':String(s.bytes.length+1)}})).status,400);
 assert.equal((await s.call(path,{method:'PUT',body:s.bytes,headers:{'Content-Type':'text/plain'}})).status,415);
 assert.equal((await s.call(path,{method:'PUT',body:new TextEncoder().encode('not a PDF')})).status,415);
 assert.equal(s.bucket.objects.size,0);
});
test('the hosted service accepts and returns a private 15 MB PDF', async () => {
 const s = await setup(), bytes = new Uint8Array(15 * 1024 * 1024);
 bytes.set(s.bytes);
 const hash = Buffer.from(await crypto.subtle.digest('SHA-256', bytes)).toString('hex');
 const id = 'service-12345678-1234-1234-1234-123456789012';
 const upload = await s.call('/api/uploads/' + id + '/' + hash, {
  method: 'PUT', body: bytes, headers: { 'Content-Length': String(bytes.length) }
 });
 assert.equal(upload.status, 200);
 assert.equal((await upload.json()).bytes, bytes.length);
 s.state.service = { fields: {
  ownerId: { stringValue: 'student' },
  design: { mapValue: { fields: { fileHash: { stringValue: hash } } } },
 } };
 const download = await s.call('/api/service-documents/' + id);
 assert.equal(download.status, 200);
 const downloaded = new Uint8Array(await download.arrayBuffer());
 assert.equal(downloaded.byteLength, bytes.length);
 assert.equal(Buffer.from(await crypto.subtle.digest('SHA-256', downloaded)).toString('hex'), hash);
});
test('order PDFs are private even if the upstream database returns another student record',async()=>{
 const s=await setup();await s.call('/api/uploads/'+s.hash,{method:'PUT',body:s.bytes});s.state.order=s.order('student');
 assert.equal((await s.call('/api/documents/request')).status,200);
 assert.equal((await s.call('/api/documents/request',{uid:'other'})).status,403);
 s.state.roles.add('shop-admin');assert.equal((await s.call('/api/documents/request',{uid:'shop-admin'})).status,200);
 assert.equal((await s.call('/api/documents/request',{uid:'claim-admin',jwt:token('claim-admin',{admin:true})})).status,200);
 s.state.order.fields.storagePath.stringValue='hosted/other/'+s.hash+'.pdf';assert.equal((await s.call('/api/documents/request')).status,409);
});
test('the PDF API never deletes uploaded bytes, including after an uncertain order save',async()=>{
 const s=await setup(),path='/api/uploads/'+s.hash;await s.call(path,{method:'PUT',body:s.bytes});
 assert.equal((await s.call(path,{method:'DELETE'})).status,404);assert.equal(s.bucket.objects.size,1);
});
test('public status does not expose PDFs and cross-origin access is rejected',async()=>{
 const s=await setup();
 const status=await s.worker.fetch(new Request(SITE+'/api/status'),{});assert.deepEqual(await status.json(),{ready:false,documentStore:'hosted',maxBytes:15*1024*1024});
 assert.equal((await s.call('/api/uploads/'+s.hash,{headers:{Origin:'https://another.example'}})).status,403);
});

test('an interrupted batch skips private PDFs the server already received',async()=>{
 const { HostedDocuments }=await import('../src/documents.mjs');
 const { uploadRequestFiles }=await import('../src/attachment.mjs');
 const s=await setup(), sent=[], second=samplePDF(3);
 const firstHash=s.hash, secondHash=Buffer.from(await crypto.subtle.digest('SHA-256',second)).toString('hex');
 const draft={pdf:{name:'Notes.pdf',sha256:firstHash},file:s.bytes,
   attachments:[{pdf:{name:'Slides.pdf',sha256:secondHash},file:second}]};
 let fail=true;
 const uploader=new HostedDocuments({baseUrl:SITE,xhr:null,request:(url,init)=>{
   const path=new URL(url).pathname;
   if(init.method==='PUT') {
     sent.push(path.split('/').at(-1));
     if(path.endsWith(secondHash)&&fail) return Promise.resolve(Response.json({}, {status:503}));
   }
   return s.worker.fetch(new Request(url,init),{BUCKET:s.bucket});
 }});
 const user={uid:'student',getIdToken:async()=>token('student')}, orderId='draft-batch';
 const updates=[];
 await assert.rejects(uploadRequestFiles(uploader,user,draft,(fraction,file)=>updates.push(file),orderId),/File 2 of 2 \(Slides.pdf\)/);
 fail=false;
 await uploadRequestFiles(uploader,user,draft,()=>{},orderId);
 assert.deepEqual(sent,[firstHash,secondHash,secondHash]);
 assert.ok(updates.some(file=>file.index===0&&file.fraction===.97));
 const path='/api/uploads/'+orderId+'/'+firstHash;
 assert.equal((await s.call(path,{method:'HEAD',jwt:null})).status,401);
 assert.equal((await s.call(path,{method:'HEAD',uid:'other'})).status,404);
 assert.equal((await s.call(path,{method:'HEAD'})).status,204);
});

test('a service design is downloadable only by its customer and authorized shop staff', async () => {
 const s = await setup();
 const id = 'service-11223344-1122-1122-1122-112233445566';
 const upload = '/api/uploads/' + id + '/' + s.hash;
 const download = '/api/service-documents/' + id;
 assert.equal((await s.call(upload, { method: 'PUT', body: s.bytes })).status, 200);
 s.state.service = { fields: {
  ownerId: { stringValue: 'student' },
  design: { mapValue: { fields: { fileHash: { stringValue: s.hash } } } },
 } };
 assert.equal((await s.call(download, { jwt: null })).status, 401);
 assert.equal((await s.call(download, { uid: 'another-customer' })).status, 403);
 assert.equal((await s.call(download)).status, 200);
 s.state.roles.add('shop-admin');
 const file = await s.call(download, { uid: 'shop-admin' });
 assert.equal(file.status, 200);
 assert.equal(file.headers.get('Cache-Control'), 'private, no-store');
 assert.deepEqual(new Uint8Array(await file.arrayBuffer()), s.bytes);
 assert.equal((await s.call(upload, { method: 'PUT', body: s.bytes })).status, 409);
});

test('service retention deletes both private sides for expired cancelled requests', async () => {
 const s = await setup();
 const id = 'service-32145678-1234-1234-1234-123456789012';
 const other = samplePDF(1);
 const backHash = Buffer.from(await crypto.subtle.digest('SHA-256', other)).toString('hex');
 for (const [hash, body] of [[s.hash,s.bytes],[backHash,other]]) {
  assert.equal((await s.call('/api/uploads/' + id + '/' + hash, { method: 'PUT', body })).status, 200);
 }
 s.state.service = { name: 'projects/' + projectId + '/databases/(default)/documents/serviceRequests/' + id, fields: {
  ownerId: { stringValue: 'student' }, status: { stringValue: 'Cancelled' },
  updatedAt: { timestampValue: new Date().toISOString() },
  design: { mapValue: { fields: { fileHash: { stringValue: s.hash } } } },
  designBack: { mapValue: { fields: { fileHash: { stringValue: backHash } } } },
 } };
 s.state.settings = { fields: { retentionDays: { integerValue: '7' } } };
 s.state.queryRows = [{ document: s.state.service }];
 const pdf = await s.call('/api/service-documents/' + id + '?side=back');
 assert.deepEqual(new Uint8Array(await pdf.arrayBuffer()), other);
 const sweep = '/api/document-retention?kind=serviceRequests';
 assert.equal((await s.call(sweep, { method: 'POST' })).status, 403);
 s.state.roles.add('shop-admin');
 const run = () => s.call(sweep, { method: 'POST', uid: 'shop-admin' }).then(response => response.json());
 assert.equal((await run()).removed, 0);
 s.state.service.fields.updatedAt.timestampValue = new Date(Date.now() - 8 * 86400000).toISOString();
 assert.equal((await run()).removed, 1);
 assert.equal(s.bucket.objects.size, 0);
 assert.ok(s.state.service.fields.documentDeletedAt);
 assert.equal((await s.call('/api/service-documents/' + id)).status, 410);
 assert.equal((await run()).removed, 0);
});
