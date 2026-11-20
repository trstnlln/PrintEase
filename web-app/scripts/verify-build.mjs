import assert from 'node:assert/strict';
import {readFile} from 'node:fs/promises';
import worker from '../dist/server/index.js';
for(const path of ['/','/assets/app.js','/assets/pdf.mjs','/assets/pdf.worker.mjs','/styles.css','/sw.js']){
 const response=await worker.fetch(new Request('https://preview.example'+path),{});
 assert.equal(response.status,200,path);assert.ok((await response.arrayBuffer()).byteLength>0,path);
}
const status=await worker.fetch(new Request('https://preview.example/api/status'),{});assert.equal((await status.json()).ready,false);
const privateResponse=await worker.fetch(new Request('https://preview.example/api/uploads/'+'a'.repeat(64)),{BUCKET:{}});
assert.equal(privateResponse.status,401);assert.equal(privateResponse.headers.get('Cache-Control'),'no-store');
const sw=await readFile('dist/sw.js','utf8');assert.ok(!sw.includes('"/api/'));console.log('Hosted Worker serves the real public build and protects document endpoints.');
