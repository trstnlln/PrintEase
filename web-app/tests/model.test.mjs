import test from 'node:test';
import assert from 'node:assert/strict';
import {estimate,defaults,dayKey,filterOrders,makeOrder,canTransition,escapeHtml,errorMessage,validatePdf,MAX_BYTES,parsePageRanges,serializeOptions} from '../src/model.mjs';
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

test('page range selection merges duplicates and bounds pricing to selected pages',async()=>{
 const {parsePageRanges,selectedPages,serializeOptions,sameOptions,defaultShop,validateShop,orderTotal}=await import('../src/model.mjs');
 const ranges=parsePageRanges('5, 1-3, 2, 8-10',10);assert.deepEqual(ranges,[{start:1,end:3},{start:5,end:5},{start:8,end:10}]);
 const o={...defaults(),pages:10,copies:3,duplex:true,pageRanges:ranges};assert.equal(selectedPages(o),7);assert.deepEqual(estimate(o,{bw:4,color:12,legalExtra:2}),{rate:4,total:84,sheets:12});
 assert.deepEqual(parsePageRanges('1-10',10),[]);for(const input of ['0','11','3-1','1,,2','1.5','1-9999999999999999999999'])assert.throws(()=>parsePageRanges(input,10));
 const serialized=serializeOptions(o);assert.equal(sameOptions(Object.fromEntries(Object.entries(serialized).reverse()),o),true);
 const order=makeOrder('snapshot',{uid:'a',name:'Alex',admin:false},{name:'PDF'},o,{bw:4,color:12,legalExtra:2});assert.equal(orderTotal(order),84);assert.equal(order.pricing.bw,4);
 assert.equal(defaultShop().retentionDays,0);assert.throws(()=>validateShop({...defaultShop(),retentionDays:1}));
});

test('page range limit is checked before submission',()=>{
 assert.equal(parsePageRanges('1,3,5,7,9',20).length,5);
 assert.throws(()=>parsePageRanges('1,3,5,7,9,11',20),/up to 5/);
 assert.throws(()=>serializeOptions({...defaults(),pages:20,pageRanges:Array.from({length:6},(_,i)=>({start:i*2+1,end:i*2+1}))}),/up to 5/);
});
