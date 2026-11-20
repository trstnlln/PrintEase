async function pushOwner(){
 return new Promise(resolve=>{
  const request=indexedDB.open('printease-browser-v1',1);
  request.onupgradeneeded=()=>{for(const name of ['orders','files','settings'])if(!request.result.objectStoreNames.contains(name))request.result.createObjectStore(name,{keyPath:'id'});};
  request.onerror=()=>resolve(null);
  request.onsuccess=()=>{const database=request.result,transaction=database.transaction('settings','readonly'),read=transaction.objectStore('settings').get('push-owner');read.onsuccess=()=>resolve(read.result?.uid||null);read.onerror=()=>resolve(null);transaction.oncomplete=()=>database.close();};
 });
}
self.addEventListener('push',event=>event.waitUntil((async()=>{
 let message;try{message=event.data.json();}catch{return;}
 if(!['ready','test'].includes(message.type)||typeof message.ownerId!=='string'||await pushOwner()!==message.ownerId)return;
 if(message.type==='ready'&&!/^[A-Za-z0-9_-]{1,128}$/.test(message.orderId||''))return;
 await self.registration.showNotification(message.type==='test'?'PrintEase alerts are on':'Your prints are ready',{
  body:message.type==='test'?'You can receive pickup updates on this device.':'Open PrintEase for pickup details.',
  icon:'/assets/touch-icon.png',badge:'/assets/touch-icon.png',tag:message.type==='test'?'printease-test':'printease-ready-'+message.orderId,
  data:{path:message.type==='test'?'/#account':'/#detail/'+encodeURIComponent(message.orderId)}
 });
})()));
self.addEventListener('notificationclick',event=>{
 event.notification.close();
 const path=event.notification.data?.path;
 if(typeof path!=='string'||!/^\/#(?:account|detail\/[A-Za-z0-9_-]{1,128})$/.test(path))return;
 event.waitUntil((async()=>{
  const url=new URL(path,self.location.origin).href;
  const windows=await self.clients.matchAll({type:'window',includeUncontrolled:true});
  const existing=windows.find(client=>new URL(client.url).origin===self.location.origin);
  if(existing){await existing.navigate(url);await existing.focus();}else await self.clients.openWindow(url);
 })());
});
