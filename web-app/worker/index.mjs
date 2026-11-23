import {createPushApi} from './push.mjs';
import {firebaseConfig} from '../src/config.mjs';
const LIMIT=5*1024*1024, SITE='https://printease-campus.consideratefennel8.chatgpt.site';
class HttpError extends Error{constructor(status,code,message){super(message);this.status=status;this.code=code;}}
const fail=(status,code,message)=>{throw new HttpError(status,code,message);};
const json=(value,status=200)=>new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store'}});
function decodeClaims(token){try{const part=token.split('.')[1].replace(/-/g,'+').replace(/_/g,'/');return JSON.parse(new TextDecoder().decode(Uint8Array.from(atob(part),c=>c.charCodeAt(0))));}catch{fail(401,'auth/invalid-session','Sign in again to continue.');}}

// Injectable endpoints are used only by tests. The published entrypoint uses the fixed project configuration.
export function createWorker(assets={}, {fetchImpl=fetch,projectId=firebaseConfig.projectId,apiKey=firebaseConfig.apiKey,identityBase='https://identitytoolkit.googleapis.com/v1',firestoreBase='https://firestore.googleapis.com/v1',origin=SITE,now=()=>Date.now()}={}){
 async function requestJson(url,init={}){
  let response;try{response=await fetchImpl(url,{...init,signal:AbortSignal.timeout(20000)});}catch{fail(503,'document/service-unavailable','The document service is unavailable. Keep your draft and try again.');}
  let data;try{data=await response.json();}catch{data={};}return {response,data};
 }
 async function authorize(request){
  const header=request.headers.get('Authorization')||'';
  if(!/^Bearer [^\s]+$/.test(header)||header.length>8192)fail(401,'auth/sign-in-required','Sign in to continue.');
  const token=header.slice(7),payload=decodeClaims(token);
  if(payload.aud!==projectId||payload.iss!==`https://securetoken.google.com/${projectId}`||typeof payload.sub!=='string'||!payload.sub||payload.sub.length>128||/[\/\\]/.test(payload.sub)||!Number.isFinite(payload.exp)||payload.exp*1000<=now())fail(401,'auth/invalid-session','Your session expired. Sign in again.');
  // Decoding is not authorization: Firebase verifies the original token, including its signature and account.
  const {response,data}=await requestJson(identityBase+'/accounts:lookup?key='+encodeURIComponent(apiKey),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({idToken:token})});
  if(!response.ok||data.users?.[0]?.localId!==payload.sub||data.users[0].disabled===true||(Number(data.users[0].validSince)>0&&(!Number.isFinite(payload.auth_time)||payload.auth_time<Number(data.users[0].validSince))))fail(401,'auth/invalid-session','Your session could not be verified. Sign in again.');
  return {uid:payload.sub,token,admin:payload.admin===true};
 }
 async function firestore(user,path,init={}){
  const base=`${firestoreBase}/projects/${projectId}/databases/(default)/documents`;
  const result=await requestJson(base+(path.startsWith(':')?'':'/')+path,{...init,headers:{Authorization:'Bearer '+user.token,'Content-Type':'application/json',...init.headers}});
  const message=result.data.error?.message||'';
  if(!result.response.ok&&(message.includes('has not been used')||message.includes('SERVICE_DISABLED')||(message.includes('database')&&message.includes('does not exist'))))fail(503,'document/orders-not-ready','Order tracking is not ready yet. Keep your PDF selected and contact the shop.');
  return result;
 }
 const value=(doc,name)=>doc?.fields?.[name];
 async function administrator(user){
  if(user.admin)return true;
  const {response,data}=await firestore(user,'admins/'+encodeURIComponent(user.uid));return response.ok&&value(data,'enabled')?.booleanValue===true;
 }
 const pushApi=createPushApi({authorize,administrator,firestore,json,fail,origin,now,fetchImpl});
 const validRequestId = /^[A-Za-z0-9_-]{1,128}$/;
 const validHash = /^[a-f0-9]{64}$/;
 const scopedKey = (owner, id, hash) => `hosted/${owner}/${id}/${hash}.pdf`;

 async function removeCompletedPdf(user, env, id) {
  if (!await administrator(user)) fail(403, 'document/access-denied', 'Only the shop can remove expired PDFs.');
  const { response, data: order } = await firestore(user, 'orders/' + id);
  if (!response.ok) fail(404, 'document/order-missing', 'Refresh Orders to find this request.');
  const owner = value(order, 'ownerId')?.stringValue;
  const hash = value(order, 'fileHash')?.stringValue;
  const key = value(order, 'storagePath')?.stringValue;
  if (!owner || /[\/\\]/.test(owner) || !validHash.test(hash || '') ||
      value(order, 'documentStore')?.stringValue !== 'hosted' || key !== scopedKey(owner, id, hash)) {
   fail(409, 'document/ineligible', 'This PDF uses shared storage and cannot be removed from this request.');
  }
  if (value(order, 'documentDeletedAt')?.integerValue) return json({ removed: true });
  const settings = await firestore(user, 'shopSettings/current');
  const days = Number(value(settings.data, 'retentionDays')?.integerValue);
  const completedAt = Number(value(order, 'completedAt')?.integerValue);
  if (!settings.response.ok || ![7, 14, 30].includes(days) ||
      value(order, 'status')?.stringValue !== 'Completed' || !Number.isSafeInteger(completedAt) ||
      completedAt <= 0 || completedAt + days * 86400000 > now()) {
   fail(409, 'document/ineligible', 'This PDF has not reached the shop’s retention period.');
  }
  const prefix = `hosted/${owner}/${id}/`;
  const files = await env.BUCKET.list({ prefix, limit: 100 });
  const older = files.objects.filter(object => object.key !== key).map(object => object.key);
  if (older.length) await env.BUCKET.delete(older);
  if (files.truncated) return json({ removed: false });
  await env.BUCKET.delete(key);
  const saved = await firestore(user, 'orders/' + id + '?updateMask.fieldPaths=documentDeletedAt', {
   method: 'PATCH', body: JSON.stringify({ fields: { documentDeletedAt: { integerValue: String(now()) } } })
  });
  if (!saved.response.ok) {
   const latest = await firestore(user, 'orders/' + id);
   if (!latest.response.ok || !value(latest.data, 'documentDeletedAt')?.integerValue)
    fail(503, 'document/cleanup-incomplete', 'The PDF was removed. Try again to update the order history.');
  }
  return json({ removed: true });
 }

 async function boundedPdf(request){
  if(request.headers.get('Content-Length')&&Number(request.headers.get('Content-Length'))>LIMIT)fail(413,'document/too-large','Choose a PDF no larger than 5 MB.');
  if((request.headers.get('Content-Type')||'').split(';')[0]!=='application/pdf')fail(415,'document/not-pdf','Choose a PDF document.');
  if(!request.body)fail(400,'document/empty','Choose a PDF document.');
  const reader=request.body.getReader(),parts=[];let length=0;
  try{while(true){const item=await reader.read();if(item.done)break;length+=item.value.length;if(length>LIMIT){await reader.cancel();fail(413,'document/too-large','Choose a PDF no larger than 5 MB.');}parts.push(item.value);}}finally{reader.releaseLock();}
  if(length<5)fail(400,'document/empty','Choose a valid PDF document.');
  const bytes=new Uint8Array(length);let offset=0;for(const part of parts){bytes.set(part,offset);offset+=part.length;}
  if(new TextDecoder().decode(bytes.subarray(0,5))!=='%PDF-')fail(415,'document/not-pdf','Choose a valid PDF document.');
  return bytes;
 }
 async function download(env,key){
  const object=await env.BUCKET.get(key);if(!object)fail(404,'document/not-found','The PDF is unavailable. Keep the request ID and contact the shop.');
  return new Response(object.body,{headers:{'Content-Type':'application/pdf','Content-Disposition':'inline; filename="PrintEase-document.pdf"','Cache-Control':'private, no-store','X-Content-Type-Options':'nosniff'}});
 }
 async function api(request,env,path){
  if(path.startsWith('/api/push/'))return pushApi(request,env,path);
  if(path==='/api/status'&&request.method==='GET')return json({ready:!!env.BUCKET,documentStore:'hosted',maxBytes:LIMIT});
  if(!env.BUCKET)fail(503,'document/storage-not-ready','PDF storage is not ready yet. Keep your draft and try again later.');
  const user=await authorize(request);
  const upload = /^\/api\/uploads\/(?:(?<orderId>[A-Za-z0-9_-]{1,128})\/)?(?<hash>[a-f0-9]{64})$/.exec(path);
  if (upload) {
   const { orderId, hash } = upload.groups;
   const key = orderId ? scopedKey(user.uid, orderId, hash) : `hosted/${user.uid}/${hash}.pdf`;
   if (request.method === 'PUT') {
    const bytes = await boundedPdf(request);
    const actual = Array.from(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)), byte => byte.toString(16).padStart(2, '0')).join('');
    if (hash !== actual) fail(409, 'document/changed', 'The PDF changed. Choose it again before sending.');
    if (orderId) {
     const prior = await firestore(user, 'orders/' + orderId);
     if (prior.response.ok) {
      const owner = value(prior.data, 'ownerId')?.stringValue;
      const stage = value(prior.data, 'review')?.mapValue?.fields?.stage?.stringValue;
      if (owner !== user.uid || value(prior.data, 'status')?.stringValue !== 'Pending' || stage !== 'correction') {
       fail(409, 'document/order-closed', 'Refresh this request before replacing its PDF.');
      }
     } else if (prior.response.status !== 404) {
      fail(403, 'document/access-denied', 'You cannot upload a PDF for this request.');
     }
    }
    if (!await env.BUCKET.head(key)) {
     await env.BUCKET.put(key, bytes, {
      onlyIf: { etagDoesNotMatch: '*' },
      httpMetadata: { contentType: 'application/pdf' },
      customMetadata: { ownerId: user.uid, sha256: hash }
     });
    }
    return json({ documentStore: 'hosted', storagePath: key, fileHash: hash, bytes: bytes.length });
   }
   if (request.method === 'GET' && !orderId) return download(env, key);
  }
  const cleanupId = path.startsWith('/api/document-cleanup/') ? path.slice('/api/document-cleanup/'.length) : '';
  if (request.method === 'POST' && validRequestId.test(cleanupId)) return removeCompletedPdf(user, env, cleanupId);
  const order=/^\/api\/documents\/([A-Za-z0-9_-]{1,128})$/.exec(path);
  if(order&&request.method==='GET'){
   const {response,data}=await firestore(user,'orders/'+encodeURIComponent(order[1]));
   if(response.status===404)fail(404,'document/order-missing','This request is unavailable. Refresh Orders and try again.');
   if(!response.ok)fail(403,'document/access-denied','You do not have access to this PDF.');
   const owner=value(data,'ownerId')?.stringValue,key=value(data,'storagePath')?.stringValue,hash=value(data,'fileHash')?.stringValue;
   if(owner!==user.uid&&!await administrator(user))fail(403,'document/access-denied','You do not have access to this PDF.');
   if(value(data,'documentDeletedAt')?.integerValue)fail(410,'document/removed','This PDF has been removed. Your order history is saved.');
   if(value(data,'documentStore')?.stringValue!=='hosted'||!/^[a-f0-9]{64}$/.test(hash||'')||(key!==`hosted/${owner}/${hash}.pdf` && key!==scopedKey(owner,order[1],hash)))fail(409,'document/store-mismatch','This PDF uses an older storage service. Open it in the matching app version.');
   const index = new URL(request.url).searchParams.get('file');
   if (index == null || index === '0') return download(env,key);
   if (!['1','2'].includes(index)) fail(404,'document/not-found','This PDF is unavailable.');
   const item = value(data,'attachments')?.arrayValue?.values?.[Number(index)-1]?.mapValue;
   const fileHash = item?.fields?.fileHash?.stringValue;
   if (!validHash.test(fileHash || '') || key !== scopedKey(owner,order[1],hash)) {
    fail(404,'document/not-found','This PDF is unavailable.');
   }
   return download(env,scopedKey(owner,order[1],fileHash));
  }
  fail(404,'document/not-found','This document action is unavailable.');
 }
 return {async fetch(request,env={}){
  const url=new URL(request.url),path=url.pathname;
  if(path.startsWith('/api/')){
   const incoming=request.headers.get('Origin');
   if(incoming&&incoming!==origin)return json({error:{code:'document/origin-denied',message:'This website cannot access PrintEase documents.'}},403);
   if(request.method==='OPTIONS')return new Response(null,{status:204,headers:{'Access-Control-Allow-Origin':origin,'Access-Control-Allow-Methods':'GET, PUT, POST, DELETE, OPTIONS','Access-Control-Allow-Headers':'Authorization, Content-Type','Vary':'Origin'}});
   let response;try{response=await api(request,env,path);}catch(error){response=json({error:{code:error instanceof HttpError?error.code:'document/service-unavailable',message:error instanceof HttpError?error.message:'The document service could not finish. Keep your draft and try again.'}},error instanceof HttpError?error.status:503);}
   response.headers.set('Access-Control-Allow-Origin',origin);response.headers.set('Vary','Origin');return response;
  }
  if(!['GET','HEAD'].includes(request.method))return new Response('Method not allowed',{status:405});
  const asset=assets[path==='/'?'/index.html':path];if(!asset)return new Response('Not found',{status:404});
  const bytes=request.method==='HEAD'?null:Uint8Array.from(atob(asset.data),c=>c.charCodeAt(0));
  return new Response(bytes,{headers:{'Content-Type':asset.type,'Cache-Control':'public, max-age=0, must-revalidate','X-Content-Type-Options':'nosniff'}});
 }};
}
