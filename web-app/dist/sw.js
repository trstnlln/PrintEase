// Only public application assets are cached. Private API requests are never cached.
const CACHE='printease-bed513bbec044d2c',ASSETS=["/assets/PDFJS-LICENSE.txt","/assets/app.js","/assets/app.js.LEGAL.txt","/assets/logo.png","/assets/nano-print-alangilan.jpg","/assets/pdf.mjs","/assets/pdf.worker.mjs","/assets/theme.js","/assets/touch-icon.png","/index.html","/manifest.webmanifest","/styles.css"];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(ASSETS)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key.startsWith('printease-')&&key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
 const url=new URL(event.request.url),path=url.pathname==='/'?'/index.html':url.pathname;
 if(event.request.method!=='GET'||url.origin!==self.location.origin||!ASSETS.includes(path))return;
 event.respondWith(fetch(event.request,{cache:'no-cache'}).then(response=>{if(response.ok){const copy=response.clone();event.waitUntil(caches.open(CACHE).then(cache=>cache.put(path,copy)));}return response;}).catch(()=>caches.match(path)));
});

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
 const content = {
  ready: ['Your prints are ready', 'Open PrintEase for pickup details.'],
  quote: ['Approve your print quote', 'Open PrintEase to review the shop’s price.'],
  correction: ['The shop requested a correction', 'Open PrintEase to see what needs to change.'],
  test: ['PrintEase alerts are on', 'You can receive order updates on this device.']
 };
 if(!Object.hasOwn(content,message.type)||typeof message.ownerId!=='string'||await pushOwner()!==message.ownerId)return;
 if(message.type!=='test'&&!/^[A-Za-z0-9_-]{1,128}$/.test(message.orderId||''))return;
 const [title, body] = content[message.type];
 await self.registration.showNotification(title,{
  body,
  icon:'/assets/touch-icon.png',badge:'/assets/touch-icon.png',tag:message.type==='test'?'printease-test':'printease-'+message.type+'-'+message.orderId,
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
