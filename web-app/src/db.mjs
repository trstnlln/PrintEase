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
export async function transact(stores,fn,mode='readwrite') {const db=await database();const tx=db.transaction(stores,mode);const done=new Promise((resolve,reject)=>{tx.oncomplete=resolve;tx.onabort=()=>reject(tx.error || new Error('Local changes could not be saved.'));tx.onerror=()=>{};});let result;try {result=await fn(tx);}catch(e){tx.abort(); await done.catch(()=>{});throw e;}await done;return result;}
export const get = (store,id) => transact([store],tx=>request(tx.objectStore(store).get(id)),'readonly');
export const put = (store,obj) => transact([store],tx=>request(tx.objectStore(store).put(obj)));
export const remove = (store,id) => transact([store],tx=>request(tx.objectStore(store).delete(id)));
export const all = store => transact([store],tx=>request(tx.objectStore(store).getAll()),'readonly');
