# PrintEase Web

An iPhone-friendly web companion to the Kotlin Android app. Both clients share Firebase project **printease-ce44e**, accounts, orders and administrator grants. New PDFs use a private hosted document service backed by Sites managed R2 storage; Firebase Storage billing is not required.

Website: https://printease-campus.consideratefennel8.chatgpt.site

The Site is currently public. Visitors can reach the sign-in page, then must sign in to access their PrintEase orders and PDFs.

## Use on iPhone

1. Open the website in Safari.
2. Choose Continue with Google, or create/sign in to an email/password account.
3. Optionally choose Share → Add to Home Screen.
4. Select a PDF from Files, review print options and send the request. If the file is stored only in iCloud and fails to read, download it in Files first.

Explore the demo first is a separate local experience. Demo files and orders do not reach the shop or sync with Android.

## Firebase setup

Follow [Firebase setup](../docs/FIREBASE_SETUP.md). Enable the intended sign-in providers, open the existing `(default)` Firestore database, and publish the current rules and composite index. Do not create a second database when Firebase says one already exists.

Add `printease-campus.consideratefennel8.chatgpt.site` under Authentication → Settings → Authorized domains. The public web config lives in `src/config.mjs`; it is not a service-account secret. Keep Google enabled, add Android fingerprints for native Google login, and test the real account chooser on your device. Safari may need permission for the sign-in pop-up.

Administrators use a console-created `admins/<uid>` document with boolean `enabled: true`, or an existing trusted `admin: true` custom claim. There is no live role selector.

## Private PDFs without Firebase Storage billing

`worker/index.mjs` implements the document API on the same Site:

| Endpoint | Purpose |
| --- | --- |
| GET `/api/status` | Public readiness and size limit |
| PUT `/api/uploads/<sha256>` | Upload up to 5 MB to the authenticated user's namespace |
| GET `/api/uploads/<sha256>` | Read the signed-in user's own upload |
| GET `/api/documents/<order-id>` | Read a saved order's PDF as its owner or shop administrator |

Each private request carries a Firebase ID token. The Worker verifies the original token through Firebase before using its UID/claims, then checks the order and administrator grant through Firestore. Decoding a token alone never grants access. PDFs have immutable, content-addressed keys under `hosted/<uid>/<sha256>.pdf`. There are no public bearer download links and no API deletion endpoint that could race an uncertain order write.

The backend checks body size, PDF signature and SHA-256. Page counting and full PDF parsing run in the clients; the shop still reviews the document and final price.

New orders include `documentStore: "hosted"` and `fileHash`. Older Firebase Storage orders remain readable through the legacy SDK if their original bucket is available. Firebase Storage/CORS setup is unnecessary for new hosted PDFs.

There is currently no automatic removal or orphan cleanup for hosted PDFs. The settings screen shows retention Off. Uploads whose order write fails remain private and reusable on retry; a deliberate authenticated retention/admin deletion workflow is future work. Managed storage has hosting quotas/terms; this avoids a Firebase Blaze requirement, not every possible hosting limit.

## Build and deploy

```sh
npm ci
npm test
npm run build
node scripts/verify-build.mjs
```

The build outputs public assets plus the self-contained Worker at `dist/server/index.js`. `.openai/hosting.json` declares the managed R2 binding named `BUCKET`; the runtime provisions it during Sites deployment. Publish the complete `dist/` and hosting manifest through the existing Sites project. A GitHub push alone does not update the live website.

After deployment, confirm `/api/status` returns `"ready": true`, then verify sign-in, PDF upload, order creation and private download with a real account. A successful build is not evidence of a successful live deployment.

PDF.js, its worker and their license are bundled locally. The service worker caches only public application assets; `/api/` requests and private document bytes are never cached. Local per-account drafts use IndexedDB with a memory fallback when Safari cannot save them.

## Verification

The GitHub workflow runs Android unit/UI host tests, lint and builds; web unit/DOM tests, actual PDF parser tests, Worker authorization/storage tests, production build checks, cloud-function tests and a real Firebase Web SDK integration against Auth/Firestore/Storage emulators.

The integration uses the actual hosted Worker with an in-memory R2 test binding and Firebase emulators. It checks stable request retries, private file access, owner/admin isolation, queue updates, console administrator grants and same-account refresh without a transient sign-out. It does not contact the production project.

Physical iPhone Safari testing and the real Google account chooser remain device checks. Browser visual QA was unavailable because the managed environment did not expose its required browser-control tool.

## Product limits

The partner is a **Nearby print shop**, near campus. Details, pickup hours, rates and accepting-orders status are editable by the administrator. Selected pages determine the printed-side charge; duplex reduces sheets, not charged sides. Each order keeps its submitted quote.

Live actions require internet over Wi-Fi or cellular data. Bluetooth is not used. Payment and pickup happen at the physical shop. There is no online payment, direct printer integration or iPhone background push. Android background notifications require the separately deployed optional functions. Up to 500 live orders load; extended use needs pagination/archive management.

Local draft files can be cleared by Safari storage pressure or website-data removal. Sign-out clears the current draft. Demo data remains separate from live accounts.

Page selections support up to five separate ranges, or all pages. Both clients reject larger selections before submission; the rule tests cover the same limit.
