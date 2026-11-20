import {test} from 'node:test';
import assert from 'node:assert/strict';
// Loading the real handlers catches SDK/runtime incompatibilities without contacting a project.
test('cloud handlers register as gen 2 Firestore and scheduled functions',async()=>{
 process.env.GCLOUD_PROJECT='demo-printease';
 const {pickupReady,removeCompletedPdfs}=await import('./index.mjs');
 assert.equal(pickupReady.__endpoint.platform,'gcfv2');
 assert.equal(removeCompletedPdfs.__endpoint.platform,'gcfv2');
 assert.equal(removeCompletedPdfs.__endpoint.scheduleTrigger.timeZone,'Asia/Manila');
});
