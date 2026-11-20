import {buildPushPayload} from '@block65/webcrypto-web-push';

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
 async function send(user,env,item,message,receipt){
  if(!/^[a-f0-9]{64}$/.test(item.id)||!validSubscription(item.subscription))return 'skipped';
  if(receipt){const saved=await firestore(user,receipt);if(saved.response.ok&&documentValue(saved.data).state==='sent')return 'already';if(!saved.response.ok&&saved.response.status!==404)throw new Error('Alert receipt could not be checked.');}
  const vapid={subject:env.PUSH_VAPID_SUBJECT||origin,publicKey:env.PUSH_VAPID_PUBLIC_KEY,privateKey:env.PUSH_VAPID_PRIVATE_KEY};
  const payload=await buildPushPayload({data:JSON.stringify(message),options:{ttl:3600,urgency:'high',topic:(await hash(message.orderId||'test')).slice(0,24)}},{...item.subscription,expirationTime:null},vapid);
  const response=await fetchImpl(item.subscription.endpoint,{...payload,signal:AbortSignal.timeout(15000),redirect:'error'});
  if(response.status===404||response.status===410){await firestore(user,'users/'+encodeURIComponent(message.ownerId)+'/pushSubscriptions/'+item.id,{method:'DELETE'});return 'expired';}
  if(!response.ok)throw new Error('The push provider did not accept the alert.');
  if(receipt)await save(user,receipt,{state:'sent',updatedAt:now()});
  return 'sent';
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
  const notify=/^\/api\/push\/notify\/([A-Za-z0-9_-]{1,128})$/.exec(path);
  if(notify&&request.method==='POST'){
   if(!await administrator(user))fail(403,'push/access-denied','Only the shop can send pickup alerts.');
   const {response,data}=await firestore(user,'orders/'+encodeURIComponent(notify[1]));
   if(!response.ok)fail(404,'push/order-missing','The request is unavailable. Refresh the queue.');
   const order=documentValue(data);
   if(order.status!=='Ready for Pickup'||typeof order.ownerId!=='string'||!order.ownerId||/[\/\\]/.test(order.ownerId))fail(409,'push/not-ready','Pickup alerts are sent only for requests marked Ready for Pickup.');
   const devices=await subscriptions(user,order.ownerId);
   const message={type:'ready',ownerId:order.ownerId,orderId:notify[1]};
   const results=await Promise.allSettled(devices.map(item=>send(user,env,item,message,'orders/'+notify[1]+'/pushDeliveries/'+item.id)));
   return json({sent:results.filter(r=>r.status==='fulfilled'&&r.value==='sent').length,already:results.filter(r=>r.status==='fulfilled'&&r.value==='already').length,failed:results.filter(r=>r.status==='rejected').length,devices:devices.length});
  }
  if(path==='/api/push/test'&&request.method==='POST'){
   const devices=await subscriptions(user,user.uid);
   const results=await Promise.allSettled(devices.map(item=>send(user,env,item,{type:'test',ownerId:user.uid},null)));
   return json({sent:results.filter(r=>r.status==='fulfilled'&&r.value==='sent').length,failed:results.filter(r=>r.status==='rejected').length,devices:devices.length});
  }
  fail(404,'push/not-found','This notification action is unavailable.');
 };
}
