// Run from a trusted machine with GOOGLE_APPLICATION_CREDENTIALS pointing
// to a service account JSON file. Never bundle that file inside the app.
const { initializeApp, applicationDefault } = require('firebase-admin/app');
const { getAuth } = require('firebase-admin/auth');
const uid = process.argv[2];
if (!uid) { console.error('Usage: node tools/set-admin.cjs <firebase-user-uid>'); process.exit(1); }
initializeApp({ credential: applicationDefault() });
(async () => {
  const user = await getAuth().getUser(uid);
  await getAuth().setCustomUserClaims(uid, { ...user.customClaims, admin: true });
  console.log('Administrator role assigned. Sign out and sign in again.');
})().catch(e => { console.error(e.message); process.exit(1); });
