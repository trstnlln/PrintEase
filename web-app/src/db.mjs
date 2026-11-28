import { abortable } from "./abort.mjs";

let dbPromise;
export const request = req => new Promise((resolve,reject)=>{req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(req.error);});
export async function database() {
 if(!dbPromise) dbPromise = new Promise((resolve,reject)=>{
   const req=indexedDB.open('printease-browser-v1',1);
   req.onupgradeneeded=()=>{for(const name of ['orders','files','settings']) req.result.createObjectStore(name,{keyPath:'id'});};
   req.onsuccess=()=>resolve(req.result);req.onerror=()=>reject(new Error('Local storage is unavailable. Use a normal Safari tab with storage enabled.'));
 });
 return dbPromise;
}
export async function transact(stores, fn, mode = "readwrite", signal) {
 const db = await abortable(database(), signal);
 signal?.throwIfAborted();
 const tx = db.transaction(stores, mode);
 const done = new Promise((resolve, reject) => {
  tx.oncomplete = resolve;
  tx.onabort = () => reject(tx.error || new Error("Local changes could not be saved."));
  tx.onerror = () => {};
 });
 // An abort can reject completion before the store request settles.
 done.catch(() => {});
 try {
  const result = await abortable(fn(tx), signal);
  await abortable(done, signal);
  return result;
 } catch (error) {
  try { tx.abort(); } catch { /* The transaction may already be closed. */ }
  if (!signal?.aborted) await done.catch(() => {});
  throw error;
 }
}
export const get = (store,id) => transact([store],tx=>request(tx.objectStore(store).get(id)),'readonly');
export const put = (store,obj) => transact([store],tx=>request(tx.objectStore(store).put(obj)));
export const remove = (store,id) => transact([store],tx=>request(tx.objectStore(store).delete(id)));
export const all = store => transact([store],tx=>request(tx.objectStore(store).getAll()),'readonly');
