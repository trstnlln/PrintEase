import * as db from './db.mjs';

const hash=async text=>Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',new TextEncoder().encode(text))),b=>b.toString(16).padStart(2,'0')).join('');
const keyBytes=value=>Uint8Array.from(atob(value.replace(/-/g,'+').replace(/_/g,'/')),c=>c.charCodeAt(0));
export function pushSupported(){return !!(globalThis.isSecureContext&&globalThis.Notification&&navigator.serviceWorker&&globalThis.PushManager);}
export async function pushRequest(user,path,body,method='POST'){
 if(!user)throw new Error('Sign in to manage pickup alerts.');
 const response=await fetch('/api/push/'+path,{method,headers:{Authorization:'Bearer '+await user.getIdToken(),...(body?{'Content-Type':'application/json'}:{})},...(body?{body:JSON.stringify(body)}:{}),cache:'no-store',signal:AbortSignal.timeout(20000)});
 const data=await response.json();if(!response.ok)throw new Error(data.error?.message||'Pickup alerts could not update. Try again.');return data;
}
export function pickupAlertMessage(result) {
 if (result.sent || result.already) {
  if (result.failed || result.expired || result.invalid) return 'Pickup alert accepted for at least one device. Some devices could not be reached.';
  if (result.untracked) return 'Pickup alert accepted. PrintEase could not record the delivery.';
  return result.sent ? 'Pickup alert sent.' : 'The pickup alert was already sent.';
 }
 if (result.failed) return 'The notification service could not send the alert. The customer can still check Orders.';
 if (result.expired || result.invalid) return 'Ask the customer to re-enable pickup alerts in Account. They can still check Orders.';
 return 'The customer has no device with pickup alerts enabled. They can still check Orders.';
}
export async function clearLocalAlerts(){
 await db.remove('settings','push-owner');
 const registration=await navigator.serviceWorker?.getRegistration();
 for(const notification of await registration?.getNotifications?.()||[])notification.close();
}
export async function pushState(uid){
 if(!pushSupported())return {supported:false,enabled:false};
 const registration=await navigator.serviceWorker.getRegistration(),subscription=await registration?.pushManager.getSubscription();
 const owner=await db.get('settings','push-owner');
 return {supported:true,enabled:Notification.permission==='granted'&&!!subscription&&owner?.uid===uid};
}
export async function reconcilePushUser(uid){
 const owner=await db.get('settings','push-owner');
 if(owner&&owner.uid!==uid)await disablePush(null);
 return pushState(uid);
}
// Call directly inside a click handler: iPhone requires the permission prompt to start
// during the user gesture, before network or service-worker awaits.
export function enablePush(user){
 if(!pushSupported())return Promise.reject(new Error('On iPhone, add PrintEase to your Home Screen in Safari, then open it there to enable alerts. Use a browser that supports notifications.'));
 const permission=Notification.requestPermission();
 return (async()=>{
  if(await permission!=='granted')throw new Error('Allow notifications in your browser settings, then try again.');
  if(!user)throw new Error('Sign in before enabling alerts.');
  const response=await fetch('/api/push/config',{cache:'no-store',signal:AbortSignal.timeout(15000)}),config=await response.json();
  if(!response.ok||!config.enabled)throw new Error('Background alerts are not available yet. Keep PrintEase open for updates.');
  const registration=await navigator.serviceWorker.ready;
  let subscription=await registration.pushManager.getSubscription();
  if(subscription){
   const current=new Uint8Array(subscription.options?.applicationServerKey||[]),expected=keyBytes(config.publicKey);
   if(current.length&&!(current.length===expected.length&&current.every((v,i)=>v===expected[i]))){await subscription.unsubscribe();subscription=null;}
  }
  subscription ||= await registration.pushManager.subscribe({userVisibleOnly:true,applicationServerKey:keyBytes(config.publicKey)});
  await pushRequest(user,'subscriptions',subscription.toJSON());
  await db.put('settings',{id:'push-owner',uid:user.uid});
  return {supported:true,enabled:true};
 })();
}
export async function disablePush(user){
 // Remove local ownership before any network request, including offline sign-out.
 await clearLocalAlerts();
 const registration=await navigator.serviceWorker?.getRegistration(),subscription=await registration?.pushManager.getSubscription();
 if(!subscription)return;
 const id=await hash(subscription.endpoint);
 await subscription.unsubscribe();
 try{await pushRequest(user,'subscriptions/'+id,null,'DELETE');}catch{/* No local notifications can display after opt-out. */}
}
