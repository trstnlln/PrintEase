import {build} from 'esbuild';
import {copyFile,writeFile,readdir,readFile,mkdir,stat} from 'node:fs/promises';
import {createHash} from 'node:crypto';
await mkdir('dist/assets',{recursive:true});
await copyFile('node_modules/pdfjs-dist/legacy/build/pdf.mjs','dist/assets/pdf.mjs');
await copyFile('node_modules/pdfjs-dist/legacy/build/pdf.worker.mjs','dist/assets/pdf.worker.mjs');
await build({entryPoints:['src/app.mjs'],bundle:true,minify:true,format:'esm',target:['safari16.4','chrome110'],outfile:'dist/assets/app.js',external:['../assets/pdf.mjs'],legalComments:'linked',metafile:true}).then(async result=>writeFile('build-meta.json',JSON.stringify(result.metafile,null,2)));
async function walk(dir){let out=[];for(const file of await readdir(dir)){const path=`${dir}/${file}`;if((await stat(path)).isDirectory())out.push(...await walk(path));else if(!path.endsWith('/sw.js'))out.push(path);}return out;}
await copyFile('node_modules/pdfjs-dist/LICENSE','dist/assets/PDFJS-LICENSE.txt');
const files=await walk('dist');const hash=createHash('sha256');for(const f of files.sort())hash.update(await readFile(f));const version=hash.digest('hex').slice(0,16);
const assets=files.map(f=>'/'+f.slice(5));
await writeFile('dist/sw.js',`// Cache public application assets only. Never cache Firebase requests or document data.\nconst CACHE='printease-${version}';\nconst ASSETS=${JSON.stringify(assets)};\nself.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(ASSETS)).then(()=>self.skipWaiting())));\nself.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(k=>k.startsWith('printease-')&&k!==CACHE).map(k=>caches.delete(k)))).then(()=>self.clients.claim())));\nself.addEventListener('fetch',event=>{const url=new URL(event.request.url);if(event.request.method!=='GET'||url.origin!==self.location.origin)return;const path=url.pathname==='/'?'/index.html':url.pathname;if(!ASSETS.includes(path))return;event.respondWith(caches.open(CACHE).then(cache=>cache.match(path).then(hit=>hit||fetch(event.request))));});\n`);
console.log(`Built PrintEase Web (${version}), ${assets.length} cached public assets.`);
