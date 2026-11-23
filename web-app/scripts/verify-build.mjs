import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import worker from '../dist/server/index.js';
for(const path of ['/','/assets/app.js','/assets/pdf.mjs','/assets/pdf.worker.mjs','/styles.css','/sw.js']){
 const response=await worker.fetch(new Request('https://preview.example'+path),{});
 assert.equal(response.status,200,path);assert.ok((await response.arrayBuffer()).byteLength>0,path);
}
const page = await (await worker.fetch(new Request('https://preview.example/'),{})).text();
for (const path of ['/styles.css','/assets/app.js']) {
 const escaped = path.replaceAll('.', '\\.');
 const reference = page.match(new RegExp(`"${escaped}[?]v=[a-f0-9]{12}"`))?.[0]?.slice(1,-1);
 assert.ok(reference, path + ' must have a version in the application shell');
 assert.equal((await worker.fetch(new Request('https://preview.example'+reference),{})).status,200);
}
const status=await worker.fetch(new Request('https://preview.example/api/status'),{});assert.equal((await status.json()).ready,false);
const privateResponse=await worker.fetch(new Request('https://preview.example/api/uploads/'+'a'.repeat(64)),{BUCKET:{}});
assert.equal(privateResponse.status,401);assert.equal(privateResponse.headers.get('Cache-Control'),'no-store');
const sw=await readFile('dist/sw.js','utf8');assert.ok(!sw.includes('"/api/'));console.log('Hosted Worker serves the real public build and protects document endpoints.');
