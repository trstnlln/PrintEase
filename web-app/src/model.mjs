export const MAX_BYTES = 5 * 1024 * 1024;
export const statuses = ['Pending', 'Accepted', 'Printing', 'Ready for Pickup', 'Completed', 'Rejected', 'Cancelled'];
export const terminal = ['Completed', 'Rejected', 'Cancelled'];
export const defaults = () => ({paper:'A4', pages:1, copies:1, color:false, duplex:false});
export function validateOptions(o) {
  if (!['A4','Letter','Legal'].includes(o.paper) || !Number.isInteger(o.pages) || o.pages < 1 || o.pages > 1000 || !Number.isInteger(o.copies) || o.copies < 1 || o.copies > 100 || typeof o.color !== 'boolean' || typeof o.duplex !== 'boolean') throw new Error('Check your printing preferences. Copies must be between 1 and 100.');
  return o;
}
export function estimate(o) { validateOptions(o); const rate = (o.color ? 10 : 2) + (o.paper === 'Legal' ? 1 : 0); return {rate, total:o.pages * o.copies * rate, sheets:(o.duplex ? Math.ceil(o.pages / 2) : o.pages) * o.copies}; }
export const pesos = n => new Intl.NumberFormat('en-PH',{style:'currency', currency:'PHP'}).format(n);
export const dayKey = (date = new Date()) => new Intl.DateTimeFormat('en-CA',{timeZone:'Asia/Manila', year:'numeric', month:'2-digit', day:'2-digit'}).format(date);
export const dateLabel = n => new Intl.DateTimeFormat('en-PH',{timeZone:'Asia/Manila', month:'short',day:'numeric',hour:'numeric',minute:'2-digit'}).format(n);
export const escapeHtml = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
export const canTransition = (from,to) => ({Pending:['Accepted','Rejected','Cancelled'],Accepted:['Printing'],Printing:['Ready for Pickup'],'Ready for Pickup':['Completed']}[from] ?? []).includes(to);
export function filterOrders(orders, {filter='All', search='', admin=false}={}) {
 const term = search.trim().toLocaleLowerCase();
 return orders.filter(o => (filter === 'All' || (filter === 'Active' ? !terminal.includes(o.status) : filter === 'History' ? terminal.includes(o.status) : o.status === filter)) && (!term || `${o.fileName} ${admin ? o.studentName : ''} ${o.queue ?? ''}`.toLocaleLowerCase().includes(term)))
 .sort(admin ? (a,b) => Number(terminal.includes(a.status))-Number(terminal.includes(b.status)) || (a.queueDay ?? '9999').localeCompare(b.queueDay ?? '9999') || (a.queue ?? 1e10)-(b.queue ?? 1e10) || a.createdAt-b.createdAt : (a,b)=>b.createdAt-a.createdAt);
}
export function makeOrder(id,session,pdf,options) { validateOptions(options); if(session.admin) throw new Error('Sign in as a student to submit a request.'); return {id,ownerId:session.uid,studentName:session.name.slice(0,200),fileName:pdf.name.slice(0,255),storagePath:`documents/${session.uid}/${id}.pdf`,options:{...options},estimatedCost:estimate(options).total,createdAt:Date.now(),status:'Pending',queue:null,queueDay:null,note:''}; }
export function validatePdf(file) { if(!file || file.size < 5 || file.size > MAX_BYTES) throw new Error('Choose a PDF no larger than 5 MB.'); }
export function errorMessage(error) {
 const code = error?.code ?? '';
 const messages = {'auth/unauthorized-domain':'Google sign-in needs this website domain added in Firebase Authentication → Settings → Authorized domains.','auth/popup-blocked':'Safari blocked the account window. Allow pop-ups for this website, or sign in with email and password.','auth/popup-closed-by-user':'','auth/cancelled-popup-request':'','auth/operation-not-allowed':'This sign-in method is disabled in Firebase. Enable the Google or Email/Password provider.','auth/configuration-not-found':'Enable Authentication and the sign-in provider in your PrintEase Firebase project.','auth/invalid-credential':'Sign-in could not be verified. Check your details and try again.','auth/email-already-in-use':'This email is registered. Sign in or reset your password.','auth/account-exists-with-different-credential':'This email uses another sign-in method. Use that method to sign in first.','auth/weak-password':'Use a password with at least 8 characters.','auth/network-request-failed':'Check your internet connection and try again.','permission-denied':'Access was denied. Check your account and the deployed Firebase rules.','storage/unauthorized':'The PDF could not be accessed. Check your account and Storage rules.','storage/unknown':'Storage could not complete the request. Check connectivity, bucket setup and CORS configuration.'};
 if(Object.hasOwn(messages,code)) return messages[code];
 if(String(error?.message).includes('CONFIGURATION_NOT_FOUND')) return messages['auth/configuration-not-found'];
 return (error?.message || 'Something went wrong. Please try again.').slice(0,350);
}
export const online = () => {if(typeof navigator !== 'undefined' && !navigator.onLine) throw new Error('You’re offline. Your draft is saved; reconnect to submit or update an order.');};
export function timeout(promise,ms=25000) {let timer; return Promise.race([promise,new Promise((_,reject)=>{timer=setTimeout(()=>reject(new Error('The connection timed out. Check Orders before retrying; this draft keeps the same request ID.')),ms);})]).finally(()=>clearTimeout(timer));}
