import test from 'node:test';
import assert from 'node:assert/strict';
import {estimate,defaults,dayKey,filterOrders,makeOrder,canTransition,escapeHtml,errorMessage,validatePdf,MAX_BYTES} from '../src/model.mjs';
test('independent pricing examples and odd duplex sheet counts',()=>{
 assert.deepEqual(estimate({...defaults(),pages:12,copies:2}),{rate:2,total:48,sheets:24});
 assert.deepEqual(estimate({...defaults(),paper:'Legal',pages:3,copies:2,color:true,duplex:true}),{rate:11,total:66,sheets:4});
 assert.throws(()=>estimate({...defaults(),copies:0}));assert.throws(()=>estimate({...defaults(),copies:1.5}));
});
test('queue day follows Philippine midnight',()=>{assert.equal(dayKey(new Date('2026-11-14T15:59:59Z')),'2026-11-14');assert.equal(dayKey(new Date('2026-11-14T16:00:00Z')),'2026-11-15');});
test('workflow protects terminal and accepted requests',()=>{assert.equal(canTransition('Pending','Cancelled'),true);assert.equal(canTransition('Printing','Cancelled'),false);assert.equal(canTransition('Completed','Pending'),false);assert.equal(canTransition('Pending','Completed'),false);});
test('search and filters never expose student names as a student search term',()=>{const orders=[{id:'a',fileName:'Report.pdf',studentName:'Other Person',status:'Pending',createdAt:2},{id:'b',fileName:'Notes.pdf',studentName:'Alex',status:'Completed',createdAt:1}];assert.equal(filterOrders(orders,{filter:'Active'}).length,1);assert.equal(filterOrders(orders,{search:'Other'}).length,0);assert.equal(filterOrders(orders,{search:'Other',admin:true}).length,1);assert.equal(filterOrders(orders,{filter:'History'})[0].id,'b');});
test('order metadata matches Android Firebase schema and admins cannot submit',()=>{const user={uid:'uid',name:'Alex',admin:false};const order=makeOrder('id',user,{name:'Report.pdf'},defaults());assert.equal(order.storagePath,'documents/uid/id.pdf');assert.equal(order.status,'Pending');assert.equal(order.estimatedCost,2);assert.equal(order.queue,null);assert.throws(()=>makeOrder('id',{...user,admin:true},{name:'Report.pdf'},defaults()));});
test('user supplied text is escaped and setup errors are actionable',()=>{assert.equal(escapeHtml('<img src=x onerror="bad()">'), '&lt;img src=x onerror=&quot;bad()&quot;&gt;');assert.match(errorMessage({code:'auth/unauthorized-domain'}),/authorized domains/i);assert.equal(errorMessage({code:'auth/popup-closed-by-user'}),'');});
test('PDF size is bounded before parsing',()=>{assert.throws(()=>validatePdf({size:MAX_BYTES+1}));assert.throws(()=>validatePdf({size:0}));assert.doesNotThrow(()=>validatePdf({size:MAX_BYTES}));});
