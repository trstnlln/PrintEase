import {build} from 'esbuild';
import {copyFile,writeFile,readdir,readFile,mkdir,stat,rm} from 'node:fs/promises';
import {createHash} from 'node:crypto';
await rm('dist/server',{recursive:true,force:true});
await mkdir('dist/assets',{recursive:true});
await copyFile('node_modules/pdfjs-dist/legacy/build/pdf.mjs','dist/assets/pdf.mjs');
await copyFile('node_modules/pdfjs-dist/legacy/build/pdf.worker.mjs','dist/assets/pdf.worker.mjs');
await copyFile('node_modules/pdfjs-dist/LICENSE','dist/assets/PDFJS-LICENSE.txt');
await build({entryPoints:['src/app.mjs'],bundle:true,minify:true,format:'esm',target:['safari16.4','chrome110'],outfile:'dist/assets/app.js',
 plugins:[{name:'pdf-engine-location',setup(builder){builder.onResolve({filter:/^pdfjs-dist\/legacy\/build\/pdf\.mjs$/},()=>({path:'../assets/pdf.mjs',external:true}));}}],
 legalComments:'linked',metafile:true}).then(result=>writeFile('build-meta.json',JSON.stringify(result.metafile,null,2)));
async function walk(directory){
 let files=[];for(const file of await readdir(directory)){
  if(file.startsWith('.')||file==='server')continue;
  const path=`${directory}/${file}`;if((await stat(path)).isDirectory())files.push(...await walk(path));else files.push(path);
 }return files;
}
let files=(await walk('dist')).filter(file=>!file.endsWith('/sw.js'));
const hash=createHash('sha256');for(const file of files.sort())hash.update(await readFile(file));
const version=hash.digest('hex').slice(0,16),urls=files.map(file=>'/'+file.slice(5));
await writeFile('dist/sw.js',`// Only public application assets are cached. Private API requests are never cached.
const CACHE='printease-${version}',ASSETS=${JSON.stringify(urls)};
self.addEventListener('install',event=>event.waitUntil(caches.open(CACHE).then(cache=>cache.addAll(ASSETS)).then(()=>self.skipWaiting())));
self.addEventListener('activate',event=>event.waitUntil(caches.keys().then(keys=>Promise.all(keys.filter(key=>key.startsWith('printease-')&&key!==CACHE).map(key=>caches.delete(key)))).then(()=>self.clients.claim())));
self.addEventListener('fetch',event=>{
 const url=new URL(event.request.url),path=url.pathname==='/'?'/index.html':url.pathname;
 if(event.request.method!=='GET'||url.origin!==self.location.origin||!ASSETS.includes(path))return;
 event.respondWith(fetch(event.request).then(response=>{if(response.ok){const copy=response.clone();event.waitUntil(caches.open(CACHE).then(cache=>cache.put(path,copy)));}return response;}).catch(()=>caches.match(path)));
});
`);
files=await walk('dist');
const types={html:'text/html; charset=utf-8',css:'text/css; charset=utf-8',js:'text/javascript; charset=utf-8',mjs:'text/javascript; charset=utf-8',json:'application/json',webmanifest:'application/manifest+json',png:'image/png',txt:'text/plain; charset=utf-8'};
const assets={};for(const file of files){const extension=file.split('.').at(-1);assets['/'+file.slice(5)]={type:types[extension]||'application/octet-stream',data:(await readFile(file)).toString('base64')};}
await mkdir('dist/server',{recursive:true});
await build({stdin:{contents:`import {createWorker} from './worker/index.mjs';export default createWorker(${JSON.stringify(assets)});`,resolveDir:process.cwd(),sourcefile:'hosted-entry.mjs'},bundle:true,format:'esm',platform:'browser',target:'es2022',minify:true,outfile:'dist/server/index.js'});
console.log(`Built PrintEase (${version}): web assets and authenticated hosted PDF Worker.`);
