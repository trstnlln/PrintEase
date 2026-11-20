import test from 'node:test';
import assert from 'node:assert/strict';
import {inspectPdf} from '../src/pdf.mjs';
import {samplePDF} from '../src/demo.mjs';
test('the real PDF selection path reads page count and hash even when iPhone supplies no MIME type',async()=>{
 const file=new Blob([samplePDF(3)]);file.name='Three pages.pdf';const metadata=await inspectPdf(file);
 assert.equal(metadata.pages,3);assert.equal(metadata.name,file.name);assert.match(metadata.sha256,/^[a-f0-9]{64}$/);assert.equal(metadata.bytes,file.size);
});
test('an unreadable iCloud file gives an actionable error',async()=>{
 await assert.rejects(inspectPdf({size:100,name:'Cloud.pdf',arrayBuffer:async()=>{throw new Error('not downloaded');}}),/Download it to your iPhone/);
});
test('corrupt PDF bytes and oversized files cannot become a selected draft',async()=>{
 const file=new Blob(['%PDF-this is corrupt']);file.name='Broken.pdf';await assert.rejects(inspectPdf(file),/could not be read/);
 await assert.rejects(inspectPdf({size:5*1024*1024+1}),/no larger than 5 MB/);
});
