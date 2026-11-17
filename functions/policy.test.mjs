import {test} from 'node:test';
import assert from 'node:assert/strict';
import {expiredDocument,readyTransition} from './policy.mjs';
test('alerts only trigger on entering ready, never on repeated updates or completion',()=>{
 assert.equal(readyTransition({status:'Printing'},{status:'Ready for Pickup',ownerId:'a'}),true);
 assert.equal(readyTransition({status:'Ready for Pickup'},{status:'Ready for Pickup',ownerId:'a'}),false);
 assert.equal(readyTransition({status:'Ready for Pickup'},{status:'Completed',ownerId:'a'}),false);
});
test('retention is opt-in and limited to completed, owned PDFs past the chosen period',()=>{
 const now=30*86400000, order={status:'Completed',completedAt:1,ownerId:'a',storagePath:'documents/a/one.pdf'};
 assert.equal(expiredDocument(order,0,now),false);
 assert.equal(expiredDocument(order,7,now),true);
 assert.equal(expiredDocument({...order,status:'Ready for Pickup'},7,now),false);
 assert.equal(expiredDocument({...order,completedAt:now-86400000},7,now),false);
 assert.equal(expiredDocument({...order,storagePath:'documents/b/one.pdf'},7,now),false);
 assert.equal(expiredDocument({...order,documentDeletedAt:now},7,now),false);
 assert.equal(expiredDocument({...order,completedAt:undefined},7,now),false);
 assert.equal(expiredDocument(order,1,now),false);
});
