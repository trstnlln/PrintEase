import {MAX_BYTES,validatePdf} from './model.mjs';
import { abortable } from './abort.mjs';
let library;
async function engine(){
 if(!library)library=import('pdfjs-dist/legacy/build/pdf.mjs').then(pdf=>{
  const node=typeof process!=='undefined'&&!!process.versions?.node;
  if(!node)pdf.GlobalWorkerOptions.workerSrc=new URL('../assets/pdf.worker.mjs',import.meta.url).href;
  return pdf;
 }).catch(()=>{library=null;const error=new Error('The PDF reader could not load. Reload PrintEase and choose the PDF again.');error.code='document/reader-unavailable';throw error;});
 return library;
}
export async function inspectPdf(file, { signal, data } = {}) {
 validatePdf(file);
 let bytes = data;
 try {
  signal?.throwIfAborted();
  if (!bytes) bytes = new Uint8Array(await abortable(file.arrayBuffer(), signal));
 } catch {
  if (signal?.aborted) throw signal.reason;
  throw new Error('The PDF could not be opened from Files. Download it to your device, then choose it again.');
 }
 if(bytes.length>MAX_BYTES)throw new Error('Choose a PDF no larger than 5 MB.');
 if(new TextDecoder().decode(bytes.subarray(0,5))!=='%PDF-')throw new Error('Choose a valid PDF document.');
 const digest = await abortable(crypto.subtle.digest('SHA-256', bytes), signal);
 const sha = Array.from(new Uint8Array(digest), n => n.toString(16).padStart(2,'0')).join('');
 const pdf = await abortable(engine(), signal);
 let task;
 try {
  task = pdf.getDocument({ data: bytes, isEvalSupported: false });
  const doc = await abortable(task.promise, signal);
  if(doc.numPages<1||doc.numPages>1000)throw new Error('Choose a PDF with 1–1,000 pages.');
  return { name: file.name.slice(0,255), pages: doc.numPages, bytes: file.size, sha256: sha };
 } catch (error) {
  if (signal?.aborted) throw signal.reason;
  if(error.name==='PasswordException')throw new Error('Use an unlocked PDF. Password-protected documents are not supported.');
  if(error.message?.includes('1–1,000'))throw error;
  throw new Error('This PDF could not be read. Choose an unlocked, undamaged PDF.');
 } finally {
  if (task) {
   const cleanup = task.destroy().catch(() => {});
   if (!signal?.aborted) await abortable(cleanup, signal);
  }
 }
}
export async function openPdf(bytes){if(bytes.byteLength>MAX_BYTES)throw new Error('This PDF exceeds 5 MB.');return(await engine()).getDocument({data:bytes.slice(),isEvalSupported:false}).promise;}
export async function renderPage(doc,page,canvas){const pdfPage=await doc.getPage(page),base=pdfPage.getViewport({scale:1}),scale=Math.min(2,900/base.width),viewport=pdfPage.getViewport({scale});canvas.width=viewport.width;canvas.height=viewport.height;await pdfPage.render({canvasContext:canvas.getContext('2d'),viewport}).promise;}

export async function renderPrintPage(doc, number, canvas, options, {
 landscape, showMargins = true, dpi: printDpi, signal
} = {}) {
 signal?.throwIfAborted();
 const { printGeometry } = await import('./paper.mjs');
 const page = await abortable(doc.getPage(number), signal), source = page.getViewport({ scale: 1 });
 const geometry = printGeometry(options.paper, source.width, source.height, options.actualSize, landscape);
 const { sheetW, sheetH, margin, scale, x, y, clipped } = geometry;
 const dpi = printDpi ? Math.min(printDpi, 4400 / Math.max(sheetW, sheetH)) : Math.min(2, 1000 / Math.max(sheetW, sheetH));
 canvas.width = Math.ceil(sheetW * dpi);
 canvas.height = Math.ceil(sheetH * dpi);
 const ctx = canvas.getContext('2d');
 ctx.fillStyle = '#fff';
 ctx.fillRect(0, 0, canvas.width, canvas.height);
 ctx.save();
 try {
  ctx.beginPath();
  ctx.rect(margin * dpi, margin * dpi, (sheetW - 2 * margin) * dpi, (sheetH - 2 * margin) * dpi);
  ctx.clip();
  const task = page.render({ canvasContext: ctx, viewport: page.getViewport({ scale: scale * dpi }),
    transform: [1, 0, 0, 1, x * dpi, y * dpi] });
  const cancel = () => task.cancel();
  signal?.addEventListener("abort", cancel, { once: true });
  try { await task.promise; }
  finally { signal?.removeEventListener("abort", cancel); }
 } finally { ctx.restore(); }
 if (showMargins) {
  ctx.strokeStyle = '#738772';
  ctx.setLineDash([4, 4]);
  ctx.strokeRect(margin * dpi, margin * dpi, (sheetW - 2 * margin) * dpi, (sheetH - 2 * margin) * dpi);
 }
 return clipped;
}
