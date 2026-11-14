// Cache public application assets only. Never cache Firebase requests or document data.
const CACHE='printease-3e32b535f85c86fb';
const ASSETS=["/assets/PDFJS-LICENSE.txt","/assets/app.js","/assets/app.js.LEGAL.txt","/assets/logo.png","/assets/pdf.mjs","/assets/pdf.worker.mjs","/assets/touch-icon.png","/index.html","/manifest.webmanifest","/styles.css"];
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(ASSETS)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('printease-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{const url=new URL(event.request.url);if(event.request.method!=='GET'||url.origin!==self.location.origin)return;const path=url.pathname==='/'?'/index.html':url.pathname;if(!ASSETS.includes(path))return;event.respondWith(caches.open(CACHE).then(cache=>cache.match(path).then(hit=>hit||fetch(event.request))));});
