import {encryptNotification, vapidHeaders} from '@block65/webcrypto-web-push';

const from64=value=>Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
const hash=async value=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(value))),b=>b.toString(16).padStart(2,'0')).join('');
export function allowedPushEndpoint(value){
 try{const u=new URL(value);return value.length<=2048&&u.protocol==='https:'&&!u.username&&!u.password&&(!u.port||u.port==='443')&&(
  u.hostname==='fcm.googleapis.com'||u.hostname==='web.push.apple.com'||u.hostname.endsWith('.push.services.mozilla.com')||u.hostname.endsWith('.notify.windows.com'));
 }catch{return false;}
}
export function validSubscription(value){
 try{return allowedPushEndpoint(value?.endpoint)&&/^[A-Za-z0-9_-]+$/.test(value.keys?.p256dh)&&/^[A-Za-z0-9_-]+$/.test(value.keys?.auth)&&from64(value.keys.p256dh).length===65&&from64(value.keys.p256dh)[0]===4&&from64(value.keys.auth).length===16;}catch{return false;}
}
const encode=value=>typeof value==='string'?{stringValue:value}:typeof value==='number'?{integerValue:String(value)}:{mapValue:{fields:Object.fromEntries(Object.entries(value).map(([key,item])=>[key,encode(item)]))}};
const decode=value=>value?.stringValue??(value?.integerValue!==undefined?Number(value.integerValue):value?.mapValue?Object.fromEntries(Object.entries(value.mapValue.fields||{}).map(([key,item])=>[key,decode(item)])):null);
const documentValue=doc=>Object.fromEntries(Object.entries(doc.fields||{}).map(([key,value])=>[key,decode(value)]));

export function createPushApi({authorize,administrator,firestore,json,fail,origin,now,fetchImpl}){
 async function body(request){
  if((request.headers.get('Content-Type')||'').split(';')[0]!=='application/json')fail(415,'push/invalid-request','Use a valid notification request.');
  if(!request.body)fail(400,'push/invalid-request','The notification request is empty.');
  const reader=request.body.getReader();let total=0;const chunks=[];
  try{while(true){const {done,value}=await reader.read();if(done)break;total+=value.length;if(total>8192){await reader.cancel();fail(413,'push/invalid-request','The notification request is too large.');}chunks.push(value);}}finally{reader.releaseLock();}
  const bytes=new Uint8Array(total);let offset=0;for(const chunk of chunks){bytes.set(chunk,offset);offset+=chunk.length;}
  try{return JSON.parse(new TextDecoder().decode(bytes));}catch{fail(400,'push/invalid-request','Use a valid notification request.');}
 }
 async function save(user,path,value){
  const {response}=await firestore(user,path,{method:'PATCH',body:JSON.stringify({fields:Object.fromEntries(Object.entries(value).map(([key,item])=>[key,encode(item)]))})});
  if(!response.ok)fail(503,'push/setup-needed','Background alerts are not enabled yet. Keep PrintEase open for updates and contact the shop.');
 }
 async function subscriptions(user,uid){
  const {response,data}=await firestore(user,'users/'+encodeURIComponent(uid)+'/pushSubscriptions?pageSize=10');
  if(!response.ok)fail(503,'push/setup-needed','The status is saved, but pickup alerts need setup.');
  return (data.documents||[]).map(doc=>({id:doc.name.split('/').at(-1),subscription:documentValue(doc)}));
 }
 function report(stage, provider, status, reason) {
  // Provider URLs and tokens identify individual devices. Log only safe diagnostic fields.
  console.warn('Pickup alert delivery issue', { stage, provider, status, reason });
 }
 function safeProviderReason(value) {
  const reasons = ['BadJwtToken', 'BadVapidToken', 'VapidPkHashMismatch', 'BadAuthorizationHeader',
    'BadWebPushRequest', 'BadTtl', 'PayloadTooLarge', 'BadUrgency', 'BadTopic', 'BadDeviceToken',
    'Unregistered', 'TooManyRequests', 'Forbidden', 'Unauthorized', 'InvalidRegistration'];
  return reasons.includes(value) ? value : undefined;
 }
 async function providerReason(response) {
  const reader = response.body?.getReader();
  if (!reader) return undefined;
  let size = 0, value = '';
  const decoder = new TextDecoder();
  try {
   while (true) {
    const chunk = await reader.read();
    if (chunk.done) break;
    size += chunk.value.byteLength;
    if (size > 2048) return undefined;
    value += decoder.decode(chunk.value, {stream: true});
   }
   return safeProviderReason(JSON.parse(value + decoder.decode())?.reason);
  } catch {
   return undefined;
  } finally {
   await reader.cancel().catch(() => {});
  }
 }
 async function send(user, env, item, message, receipt) {
  if (!/^[a-f0-9]{64}$/.test(item.id) || !validSubscription(item.subscription)) return 'invalid';
  const provider = new URL(item.subscription.endpoint).hostname;
  let stage = 'receipt-read';
  try {
   if (receipt) {
    const saved = await firestore(user, receipt);
    if (saved.response.ok && documentValue(saved.data).state === 'sent') return 'already';
    if (!saved.response.ok && saved.response.status !== 404) {
     report(stage, provider, saved.response.status);
     return 'failed';
    }
   }
   const vapid = {
    subject: env.PUSH_VAPID_SUBJECT || origin,
    publicKey: env.PUSH_VAPID_PUBLIC_KEY,
    privateKey: env.PUSH_VAPID_PRIVATE_KEY
   };
   stage = 'encryption';
   const [{ headers }, payload, topic] = await Promise.all([
    vapidHeaders(item.subscription, vapid),
    encryptNotification(item.subscription, new TextEncoder().encode(JSON.stringify(message)), { pad: false }),
    hash(message.orderId || 'test')
   ]);
   stage = 'transport';
   const response = await fetchImpl(item.subscription.endpoint, {
    method: 'POST',
    headers: {
     ...headers, ttl: '3600', urgency: 'high', topic: topic.slice(0, 24),
     'content-encoding': 'aes128gcm', 'content-type': 'application/octet-stream'
    },
    body: payload,
    signal: AbortSignal.timeout(15000),
    // Workers accepts "manual". Inspect the response without following any redirect.
    redirect: 'manual'
   });
   if (response.status >= 300 && response.status < 400) {
    report('provider-redirect', provider, response.status);
    return 'failed';
   }
   if (response.status === 404 || response.status === 410) {
    stage = 'subscription-cleanup';
    const removed = await firestore(user, 'users/' + encodeURIComponent(message.ownerId) + '/pushSubscriptions/' + item.id, { method: 'DELETE' });
    if (!removed.response.ok && removed.response.status !== 404) report(stage, provider, removed.response.status);
    return 'expired';
   }
   if (!response.ok) {
    const reason = await providerReason(response);
    report('provider', provider, response.status, reason);
    if (reason === 'VapidPkHashMismatch') return 'invalid';
    return 'failed';
   }
   if (receipt) {
    stage = 'receipt-write';
    await save(user, receipt, { state: 'sent', updatedAt: now() });
   }
   return 'sent';
  } catch (error) {
   const errorType = ['TypeError', 'DataError', 'NotSupportedError', 'OperationError', 'AbortError', 'TimeoutError'].includes(error?.name)
    ? error.name : undefined;
   report(stage, provider, undefined, errorType);
   if (stage === 'receipt-write') return 'sent-untracked';
   if (stage === 'subscription-cleanup') return 'expired';
   return 'failed';
  }
 }
 function summarize(results) {
  const count = (...values) => results.filter(result => values.includes(result)).length;
  return {
   sent: count('sent', 'sent-untracked'),
   untracked: count('sent-untracked'),
   already: count('already'),
   expired: count('expired'),
   invalid: count('invalid'),
   failed: count('failed'),
   devices: results.length
  };
 }
 return async function pushApi(request,env,path){
  const enabled=!!(env.PUSH_VAPID_PUBLIC_KEY&&env.PUSH_VAPID_PRIVATE_KEY);
  if(path==='/api/push/config'&&request.method==='GET')return json({enabled,publicKey:enabled?env.PUSH_VAPID_PUBLIC_KEY:null});
  const user=await authorize(request);
  const remove=/^\/api\/push\/subscriptions\/([a-f0-9]{64})$/.exec(path);
  if(remove&&request.method==='DELETE'){
   const {response}=await firestore(user,'users/'+encodeURIComponent(user.uid)+'/pushSubscriptions/'+remove[1],{method:'DELETE'});
   if(!response.ok&&response.status!==404)fail(503,'push/unsubscribe-failed','The server could not remove this device yet. Local alerts are off.');
   return json({removed:true});
  }
  if(!enabled)fail(503,'push/unavailable','Background alerts are not available yet. Keep PrintEase open for updates.');
  if(path==='/api/push/subscriptions'&&request.method==='POST'){
   const subscription=await body(request);
   if(!validSubscription(subscription))fail(400,'push/invalid-subscription','This browser returned an unsupported notification subscription.');
   const id=await hash(subscription.endpoint);
   await save(user,'users/'+encodeURIComponent(user.uid)+'/pushSubscriptions/'+id,{endpoint:subscription.endpoint,keys:{p256dh:subscription.keys.p256dh,auth:subscription.keys.auth},updatedAt:now()});
   return json({id,enabled:true});
  }
  const ready = /^\/api\/push\/notify\/([A-Za-z0-9_-]{1,128})$/.exec(path);
  const review = /^\/api\/push\/review\/([A-Za-z0-9_-]{1,128})\/(0|[1-9][0-9]{0,9})$/.exec(path);
  if ((ready || review) && request.method === 'POST') {
   if (!await administrator(user)) fail(403, 'push/access-denied', 'Only the shop can send order alerts.');
   const orderId = (ready || review)[1];
   const {response,data} = await firestore(user, 'orders/' + orderId);
   if (!response.ok) fail(404, 'push/order-missing', 'The request is unavailable. Refresh the queue.');
   const order = documentValue(data);
   if (typeof order.ownerId !== 'string' || !order.ownerId || /[\/\\]/.test(order.ownerId)) {
    fail(409, 'push/not-ready', 'This request no longer has an alert to send.');
   }
   let type = 'ready';
   if (ready && order.status !== 'Ready for Pickup') {
    fail(409, 'push/not-ready', 'Pickup alerts are sent only for requests marked Ready for Pickup.');
   }
   if (review) {
    const stage = order.review?.stage;
    if (order.status !== 'Pending' || order.review?.version !== Number(review[2]) || !['quoted','correction'].includes(stage)) {
     fail(409, 'push/not-ready', 'This quote or correction is no longer waiting for a response.');
    }
    type = stage === 'quoted' ? 'quote' : 'correction';
   }
   const devices = await subscriptions(user, order.ownerId);
   const message = { type, ownerId: order.ownerId, orderId };
   const results = await Promise.all(devices.map(async item => {
    const key = review ? await hash(item.id + ':' + type + ':' + review[2]) : item.id;
    return send(user, env, item, message, 'orders/' + orderId + '/pushDeliveries/' + key);
   }));
   return json(summarize(results));
  }
  if(path==='/api/push/test'&&request.method==='POST'){
   const devices=await subscriptions(user,user.uid);
   const results = await Promise.all(devices.map(item => send(user, env, item, { type: 'test', ownerId: user.uid }, null)));
   return json(summarize(results));
  }
  fail(404,'push/not-found','This notification action is unavailable.');
 };
}
