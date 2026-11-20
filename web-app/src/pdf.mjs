import {MAX_BYTES,validatePdf} from './model.mjs';
let library;
async function engine(){
 if(!library)library=import('pdfjs-dist/legacy/build/pdf.mjs').then(pdf=>{
  const node=typeof process!=='undefined'&&!!process.versions?.node;
  if(!node)pdf.GlobalWorkerOptions.workerSrc=new URL('../assets/pdf.worker.mjs',import.meta.url).href;
  return pdf;
 }).catch(()=>{library=null;const error=new Error('The PDF reader could not load. Reload PrintEase and choose the PDF again.');error.code='document/reader-unavailable';throw error;});
 return library;
}
export async function inspectPdf(file){
 validatePdf(file);let buffer;
 try{buffer=await file.arrayBuffer();}catch{throw new Error('The PDF could not be opened from Files. Download it to your iPhone, then choose it again.');}
 const bytes=new Uint8Array(buffer);
 if(bytes.length>MAX_BYTES)throw new Error('Choose a PDF no larger than 5 MB.');
 if(new TextDecoder().decode(bytes.slice(0,5))!=='%PDF-')throw new Error('Choose a valid PDF document.');
 const sha=Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256',bytes))).map(n=>n.toString(16).padStart(2,'0')).join('');
 const pdf=await engine();let doc;
 try{
  doc=await pdf.getDocument({data:bytes,isEvalSupported:false}).promise;
  if(doc.numPages<1||doc.numPages>1000)throw new Error('Choose a PDF with 1–1,000 pages.');
  return{name:file.name.slice(0,255),pages:doc.numPages,bytes:file.size,sha256:sha};
 }catch(error){
  if(error.name==='PasswordException')throw new Error('Use an unlocked PDF. Password-protected documents are not supported.');
  if(error.message?.includes('1–1,000'))throw error;
  throw new Error('This PDF could not be read. Choose an unlocked, undamaged PDF.');
 }finally{if(doc)await doc.destroy().catch(()=>{});}
}
export async function openPdf(bytes){if(bytes.byteLength>MAX_BYTES)throw new Error('This PDF exceeds 5 MB.');return(await engine()).getDocument({data:bytes.slice(),isEvalSupported:false}).promise;}
export async function renderPage(doc,page,canvas){const pdfPage=await doc.getPage(page),base=pdfPage.getViewport({scale:1}),scale=Math.min(2,900/base.width),viewport=pdfPage.getViewport({scale});canvas.width=viewport.width;canvas.height=viewport.height;await pdfPage.render({canvasContext:canvas.getContext('2d'),viewport}).promise;}
