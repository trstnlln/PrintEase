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

test('file selection uses signatures and image PDFs preserve page proportions',async()=>{
 const {fileKind,imagePdf,preparePrintFile}=await import('../src/print-file.mjs');
 const {openPdf}=await import('../src/pdf.mjs');
 assert.equal(fileKind(new Uint8Array([137,80,78,71,13,10,26,10])),'PNG');
 assert.equal(fileKind(new Uint8Array([255,216,255,224])),'JPG');
 const file = new File([imagePdf(new Uint8Array([255,216,255,217]),600,300)],'Photo.pdf');
 const selected = await preparePrintFile(file);
 const doc = await openPdf(new Uint8Array(await selected.file.arrayBuffer()));
 try {
   assert.equal(doc.numPages,1);
   const page=(await doc.getPage(1)).getViewport({scale:1});
   assert.equal(page.width,288);
   assert.equal(page.height,144);
 } finally { await doc.destroy(); }
 await assert.rejects(preparePrintFile(new File(['Office bytes'],'Report.docx')),/Export Word, PowerPoint, or Excel/);
});

test('a custom image size is embedded into the PDF and resizes from the original proportions',async()=>{
 const {imagePdf,preparePrintFile,readImageSize,resizePrintImage}=await import('../src/print-file.mjs');
 const original=await preparePrintFile(new File([imagePdf(new Uint8Array([255,216,255,217]),600,300)], 'Photo.pdf'));
 const smaller=await resizePrintImage(original.file,original.pdf,37);
 assert.equal(smaller.pdf.imagePercent,37);
 assert.equal(await readImageSize(smaller.file),37);
 const bytes=await smaller.file.text();
 assert.match(bytes,/\/MediaBox \[0 0 288.00 144.00\]/);
 assert.match(bytes,/q 106.560 0 0 53.280 90.720 45.360 cm \/Photo Do Q/);
 assert.notEqual(smaller.pdf.sha256,original.pdf.sha256);
 const restored=await resizePrintImage(smaller.file,smaller.pdf,100);
 assert.equal(restored.pdf.sha256,original.pdf.sha256);
 await assert.rejects(resizePrintImage(original.file,original.pdf,101),/1 to 100%/);
});
