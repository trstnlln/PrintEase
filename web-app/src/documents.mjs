import {MAX_BYTES,MAX_FILE_MB,timeout} from './model.mjs';
const UPLOAD_TIMEOUT_MS = 150000;
export const DOCUMENT_ORIGIN='https://printease-campus.consideratefennel8.chatgpt.site';
export class HostedDocuments {
 constructor({baseUrl=globalThis.location?.origin||DOCUMENT_ORIGIN,request=globalThis.fetch?.bind(globalThis),xhr=globalThis.XMLHttpRequest}={}){this.baseUrl=baseUrl;this.request=request;this.XHR=xhr;}
 async headers(user){if(!user)throw new Error('Sign in before sending your PDF.');return {Authorization:'Bearer '+await user.getIdToken()};}
 async response(response){
  if(response.ok)return response;let data;try{data=await response.json();}catch{}
  const error=new Error(data?.error?.message||'The PDF could not transfer. Keep this draft and try again.');error.code=data?.error?.code||'document/transfer-failed';throw error;
 }
 async upload(user,draft,progress,orderId=null){
  if(!/^[a-f0-9]{64}$/.test(draft.pdf?.sha256||''))throw new Error('Choose the PDF again before sending.');
  if(orderId&&!/^[A-Za-z0-9_-]{1,128}$/.test(orderId))throw new Error('This request ID is invalid.');
  const path = orderId ? orderId+'/'+draft.pdf.sha256 : draft.pdf.sha256;
  const headers=await this.headers(user),url=this.baseUrl+'/api/uploads/'+path;
  const stored = {documentStore:'hosted',fileHash:draft.pdf.sha256,storagePath:`hosted/${user.uid}/${path}.pdf`};
  // Reuse only a server-confirmed file with the same authenticated scope and hash.
  if (this.request) {
   const prior = await timeout(this.request(url,{method:'HEAD',headers,cache:'no-store'}),8000).catch(()=>null);
   if (prior?.status === 204) { progress(.97); return stored; }
  }
  if(this.XHR)await new Promise((resolve,reject)=>{
   const xhr=new this.XHR();xhr.open('PUT',url);xhr.timeout=UPLOAD_TIMEOUT_MS;xhr.setRequestHeader('Authorization',headers.Authorization);xhr.setRequestHeader('Content-Type','application/pdf');
   xhr.upload.onprogress=event=>{if(event.lengthComputable)progress(Math.min(.95,event.loaded/event.total*.95));};
   xhr.onload=async()=>{try{await this.response(new Response(xhr.responseText,{status:xhr.status||502,headers:{'Content-Type':'application/json'}}));resolve();}catch(error){reject(error);}};
   xhr.onerror=()=>reject(new Error('The PDF could not upload. Check your connection and retry this draft.'));
   xhr.ontimeout=()=>reject(new Error('The upload timed out. Keep this draft and retry; it uses the same request ID.'));
   xhr.onabort=()=>reject(new Error('The upload stopped. Your PDF is still selected.'));xhr.send(draft.file);
  });else await this.response(await timeout(this.request(url,{method:'PUT',headers:{...headers,'Content-Type':'application/pdf'},body:draft.file}),UPLOAD_TIMEOUT_MS));
  progress(.97);return stored;
 }
 async cleanup(user,id){
  const response = await this.response(await timeout(this.request(this.baseUrl+'/api/document-cleanup/'+encodeURIComponent(id),{
   method:'POST',headers:await this.headers(user),cache:'no-store'
  }),30000));
  return (await response.json()).removed === true;
 }
 async sweepRetention(user,kind,after){
  const url=new URL(this.baseUrl+'/api/document-retention');
  url.searchParams.set('kind',kind);
  if(after)url.searchParams.set('after',after);
  const response=await this.response(await timeout(this.request(url,{method:'POST',headers:await this.headers(user),cache:'no-store'}),90000));
  return response.json();
 }
 async serviceBytes(user,id,side='front'){
  const response = await this.response(await timeout(this.request(this.baseUrl+'/api/service-documents/'+encodeURIComponent(id)+(side==='back'?'?side=back':''),{
   headers:await this.headers(user),cache:'no-store'
  }),60000));
  const bytes = new Uint8Array(await response.arrayBuffer());
  if(bytes.length>MAX_BYTES)throw new Error(`The document exceeds ${MAX_FILE_MB} MB.`);
  return bytes;
 }
 async bytes(user,order,index=0){
  const response=await this.response(await timeout(this.request(this.baseUrl+'/api/documents/'+encodeURIComponent(order.id)+(index ? '?file='+index : ''),{headers:await this.headers(user),cache:'no-store'}),60000));
  const bytes=new Uint8Array(await response.arrayBuffer());if(bytes.length>MAX_BYTES)throw new Error(`The document exceeds ${MAX_FILE_MB} MB.`);return bytes;
 }
}
