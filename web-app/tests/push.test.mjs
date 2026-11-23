import test from 'node:test';
import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import vm from 'node:vm';
import 'fake-indexeddb/auto';
import * as db from '../src/db.mjs';
import {createWorker} from '../worker/index.mjs';
import {allowedPushEndpoint,validSubscription} from '../worker/push.mjs';

const origin='https://test.printease.example',projectId='demo-printease';
const token=uid=>'e30.'+btoa(JSON.stringify({sub:uid,aud:projectId,iss:`https://securetoken.google.com/${projectId}`,exp:9999999999}))+'.signature';
const b64=value=>Buffer.from(value).toString('base64url');
async function fixture(){
 const keys=await crypto.subtle.generateKey({name:'ECDSA',namedCurve:'P-256'},true,['sign','verify']),jwk=await crypto.subtle.exportKey('jwk',keys.privateKey);
 const device=await crypto.subtle.generateKey({name:'ECDH',namedCurve:'P-256'},true,['deriveBits']);
 const subscription={endpoint:'https://web.push.apple.com/device',keys:{p256dh:b64(await crypto.subtle.exportKey('raw',device.publicKey)),auth:b64(crypto.getRandomValues(new Uint8Array(16)))}};
 const env={PUSH_VAPID_PUBLIC_KEY:b64(await crypto.subtle.exportKey('raw',keys.publicKey)),PUSH_VAPID_PRIVATE_KEY:jwk.d};
 const state={status:'Ready for Pickup',documents:new Map(),sent:[],lookups:0,providerStatus:201,providerReason:null,receiptStatus:null};
 const worker=createWorker({}, {origin,projectId,fetchImpl:async(url,init={})=>{
  if(url.includes('accounts:lookup')){state.lookups++;const uid=JSON.parse(atob(JSON.parse(init.body).idToken.split('.')[1])).sub;return Response.json({users:[{localId:uid}]});}
  if(url===subscription.endpoint){
   if(init.redirect !== 'manual') throw new TypeError('Workers requires manual redirects for push delivery');
   state.sent.push(init);
   return state.providerReason
    ? Response.json({reason:state.providerReason},{status:state.providerStatus})
    : new Response(null,{status:state.providerStatus});
  }
  const path=url.split('/documents/')[1];
  if(path==='admins/admin')return Response.json({fields:{enabled:{booleanValue:true}}});
  if(path?.startsWith('admins/'))return new Response('{}',{status:404});
  if(path==='orders/job')return Response.json({fields:{ownerId:{stringValue:'student'},status:{stringValue:state.status}}});
  if(path?.startsWith('users/student/pushSubscriptions?'))return Response.json({documents:[...state.documents.entries()].filter(([p])=>p.startsWith('users/student/pushSubscriptions/')).map(([p,d])=>({name:p,...d}))});
  if(init.method==='PATCH'&&path.includes('/pushDeliveries/')&&state.receiptStatus)return new Response('{}',{status:state.receiptStatus});
  if(init.method==='PATCH'){const doc=JSON.parse(init.body);state.documents.set(path,doc);return Response.json(doc);}
  if(init.method==='DELETE'){state.documents.delete(path);return new Response('{}');}
  return state.documents.has(path)?Response.json(state.documents.get(path)):new Response('{}',{status:404});
 }});
 const call=(path,{uid='student',method='POST',body,auth=true}={})=>worker.fetch(new Request(origin+'/api/push/'+path,{method,headers:{...(auth?{Authorization:'Bearer '+token(uid)}:{}),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{})}),env);
 return {state,subscription,env,call};
}
test('push endpoints reject private hosts, deceptive suffixes and credentials',async()=>{
 for(const endpoint of ['http://fcm.googleapis.com/device','https://127.0.0.1/push','https://fcm.googleapis.com.evil.example/device','https://user@web.push.apple.com/device','https://web.push.apple.com:8443/device'])assert.equal(allowedPushEndpoint(endpoint),false,endpoint);
 assert.equal(allowedPushEndpoint('https://updates.push.services.mozilla.com/device'),true);
 const f=await fixture();assert.equal(validSubscription(f.subscription),true);assert.equal(validSubscription({...f.subscription,keys:{...f.subscription.keys,auth:'tiny'}}),false);
});
test('subscriptions require authentication and bind to the token owner; public config omits the secret',async()=>{
 const f=await fixture(),config=await (await f.call('config',{method:'GET',auth:false})).json();
 assert.deepEqual(config,{enabled:true,publicKey:f.env.PUSH_VAPID_PUBLIC_KEY});assert.equal(JSON.stringify(config).includes(f.env.PUSH_VAPID_PRIVATE_KEY),false);
 assert.equal((await f.call('subscriptions',{body:f.subscription,auth:false})).status,401);
 assert.equal((await f.call('subscriptions',{body:{...f.subscription,endpoint:'https://internal.example'}})).status,400);
 const saved=await (await f.call('subscriptions',{body:f.subscription})).json();assert.match(saved.id,/^[a-f0-9]{64}$/);assert.ok(f.state.documents.has('users/student/pushSubscriptions/'+saved.id));
});
test('only administrators notify ready orders, using encrypted payloads and delivery receipts',async()=>{
 const f=await fixture();await f.call('subscriptions',{body:f.subscription});
 assert.equal((await f.call('notify/job')).status,403);
 f.state.status='Printing';assert.equal((await f.call('notify/job',{uid:'admin'})).status,409);assert.equal(f.state.sent.length,0);
 f.state.status='Ready for Pickup';const sent=await (await f.call('notify/job',{uid:'admin'})).json();assert.equal(sent.sent,1);
 assert.equal(f.state.sent[0].headers['content-encoding'],'aes128gcm');assert.match(f.state.sent[0].headers.authorization,/^vapid /);assert.ok(f.state.sent[0].body.byteLength>100&&f.state.sent[0].body.byteLength<1024);assert.equal(f.state.sent[0].headers['content-length'],undefined);
 assert.equal(new TextDecoder().decode(f.state.sent[0].body).includes('student'),false);
 const repeated=await (await f.call('notify/job',{uid:'admin'})).json();assert.equal(repeated.already,1);assert.equal(f.state.sent.length,1);
});
test('failed delivery stays retryable and expired devices are removed',async()=>{
 const f=await fixture();await f.call('subscriptions',{body:f.subscription});f.state.providerStatus=503;
 assert.equal((await (await f.call('notify/job',{uid:'admin'})).json()).failed,1);
 f.state.providerStatus=201;assert.equal((await (await f.call('notify/job',{uid:'admin'})).json()).sent,1);
 const g=await fixture();await g.call('subscriptions',{body:g.subscription});g.state.providerStatus=410;await g.call('test');assert.equal(g.state.documents.size,0);
});
test('a provider redirect fails without following the response', async () => {
 const f = await fixture();
 await f.call('subscriptions',{body:f.subscription});
 f.state.providerStatus=302;
 const result=await (await f.call('notify/job',{uid:'admin'})).json();
 assert.equal(result.failed,1);
 assert.equal(result.sent,0);
 assert.equal(f.state.sent.length,1);
 assert.equal(f.state.sent[0].redirect,'manual');
});

test('an accepted provider response is counted as sent even if the receipt cannot be stored', async () => {
 const f = await fixture();
 await f.call('subscriptions',{body:f.subscription});
 f.state.receiptStatus = 403;
 const result = await (await f.call('notify/job',{uid:'admin'})).json();
 assert.equal(result.sent,1);
 assert.equal(result.untracked,1);
 assert.equal(result.failed,0);
});

test('an expired or mismatched subscription prompts the student to reconnect', async () => {
 const { pickupAlertMessage } = await import('../src/push.mjs');
 const f = await fixture();
 await f.call('subscriptions',{body:f.subscription});
 f.state.providerStatus=403;
 f.state.providerReason='VapidPkHashMismatch';
 const mismatch=await (await f.call('notify/job',{uid:'admin'})).json();
 assert.equal(mismatch.invalid,1);
 assert.match(pickupAlertMessage(mismatch),/re-enable pickup alerts/);
 f.state.providerStatus=410;
 f.state.providerReason=null;
 const expired=await (await f.call('notify/job',{uid:'admin'})).json();
 assert.equal(expired.expired,1);
 assert.equal(expired.failed,0);
 assert.equal(f.state.documents.size,0);
 assert.match(pickupAlertMessage(expired),/re-enable pickup alerts/);
});

test('service worker displays generic alerts only for the current opted-in account',async()=>{
 const listeners={},shown=[];const self={addEventListener:(type,fn)=>listeners[type]=fn,location:{origin},registration:{showNotification:async(...args)=>shown.push(args)}};
 vm.runInNewContext(await readFile(new URL('../src/push-worker.js',import.meta.url),'utf8'),{self,indexedDB,URL});
 await db.put('settings',{id:'push-owner',uid:'student'});
 async function push(message){let pending;listeners.push({data:{json:()=>message},waitUntil:value=>pending=value});await pending;}
 await push({type:'ready',ownerId:'other',orderId:'job'});assert.equal(shown.length,0);
 await push({type:'ready',ownerId:'student',orderId:'job'});assert.equal(shown.length,1);assert.equal(shown[0][1].data.path,'/#detail/job');
 await db.remove('settings','push-owner');await push({type:'ready',ownerId:'student',orderId:'job'});assert.equal(shown.length,1);
});

test('browser permission starts from the user gesture and account switching removes local alerts',async t=>{
 const {enablePush,pushState,reconcilePushUser}=await import('../src/push.mjs'),f=await fixture();let requested=false,active=null,unsubscribed=false,closed=0;
 const saved=new Map();for(const name of ['navigator','Notification','PushManager','isSecureContext','fetch'])saved.set(name,Object.getOwnPropertyDescriptor(globalThis,name));
 t.after(()=>{for(const [name,descriptor] of saved)if(descriptor)Object.defineProperty(globalThis,name,descriptor);else delete globalThis[name];});
 const registration={getNotifications:async()=>[{close:()=>closed++}],pushManager:{getSubscription:async()=>active,subscribe:async()=>active={endpoint:f.subscription.endpoint,options:{},toJSON:()=>f.subscription,unsubscribe:async()=>{unsubscribed=true;active=null;return true;}}}};
 Object.defineProperty(globalThis,'navigator',{configurable:true,value:{serviceWorker:{ready:Promise.resolve(registration),getRegistration:async()=>registration}}});
 Object.defineProperty(globalThis,'isSecureContext',{configurable:true,value:true});Object.defineProperty(globalThis,'PushManager',{configurable:true,value:class {}});
 Object.defineProperty(globalThis,'Notification',{configurable:true,value:{permission:'granted',requestPermission:()=>{requested=true;return Promise.resolve('granted');}}});
 Object.defineProperty(globalThis,'fetch',{configurable:true,value:async(url)=>{assert.equal(requested,true);return Response.json(url.endsWith('/config')?{enabled:true,publicKey:f.env.PUSH_VAPID_PUBLIC_KEY}:{enabled:true});}});
 const pending=enablePush({uid:'student',getIdToken:async()=>token('student')});assert.equal(requested,true);await pending;
 assert.equal((await pushState('student')).enabled,true);await reconcilePushUser('different-user');assert.equal(unsubscribed,true);assert.equal(closed,1);assert.equal(await db.get('settings','push-owner'),undefined);
});
