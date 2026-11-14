# PrintEase Web

An iPhone-friendly web companion to the Kotlin PrintEase Android app. The website uses the **same `printease-ce44e` Firebase project**, accounts, private PDF objects, order metadata and trusted administrator claims.

Published Site: https://printease-campus.consideratefennel8.chatgpt.site

The Site starts **owner-private**. Open it using the ChatGPT account that owns the Site, then use PrintEase's separate Google/email sign-in. Publishing this source does not automatically grant other people access to the Site. Sharing/audience changes are separate from Firebase account roles.

## Use on iPhone

1. Open the published URL in **Safari**. Complete the private Site's ChatGPT sign-in if prompted.
2. Choose **Continue with Google**, or use email/password if enabled in Firebase. Existing Android users use the same account. No new PrintEase password is needed for Google accounts.
3. Tap Safari's **Share → Add to Home Screen → Add**. The website includes an opaque home-screen icon and standalone app manifest.
4. Submit a PDF, review printing preferences and the estimated cost, then track the same order on Android or the website. Collect and pay at the physical shop.

For testing without changing real orders, choose **Explore the demo first**. Demo student/admin roles and PDFs live only in that browser and do not sync with Android or other devices.

## Required Firebase setup

The public Firebase web client configuration supplied by the project owner is in `src/config.mjs`. It is not a service-account key. Database and document access are enforced by Firebase rules and trusted claims.

### Google sign-in domain

Under **Firebase Console → PrintEase → Authentication → Settings → Authorized domains**, add:

```
printease-campus.consideratefennel8.chatgpt.site
```

Add only the hostname, without `https://` or a path. Keep the Google provider enabled. The app starts the account window directly from the button click. If Safari blocks it, allow pop-ups for this site or use the email/password alternative. No real Google account was used in automated checks; the owner must verify the live account chooser.

### Firestore, Storage and admin

Use the canonical rules and index from the PrintEase repository root (`firebase/`). They support the same schema as the Kotlin app, including cancelled pending requests, immutable order details and counter/order binding for queue uniqueness. Deploy them to **`printease-ce44e`**, not the demo emulator project:

```sh
# From the Android/repository root, with Firebase CLI installed
npx firebase deploy --only firestore:rules,firestore:indexes,storage --project printease-ce44e
```

Enable Authentication, provision Firestore in the project's selected region, and provision the Storage bucket. Cloud Storage may require a Blaze billing account; check Firebase pricing before confirming an upgrade. The configured bucket name alone does not prove that a bucket has been provisioned.

The administrator must have the trusted Firebase `admin: true` custom claim assigned through the repository's `tools/set-admin.cjs` procedure. There is no live role selector. After assigning a claim, sign in again or use Account → Refresh account & orders.

### Browser PDF downloads and CORS

Private PDF previews/downloads use the authenticated Storage SDK (`getBytes`), not public bearer download links. If a browser reports a CORS failure, a project owner with Google Cloud Storage permissions can apply the included `storage-cors.json`:

```sh
gcloud storage buckets update gs://printease-ce44e.firebasestorage.app --cors-file=storage-cors.json
```

This permits browser GET/HEAD requests from the exact deployed origin; it does not bypass Firebase Storage access rules. The Android app does not require a CORS change. If hosting the website on another origin, update the allowed origin before applying the file. Reapply an existing bucket policy thoughtfully if it also serves other websites.

## Development and checks

```sh
npm ci
npm test
npm run build
```

`dist/` is the static deployment output. Firebase SDK code is bundled locally; PDF.js and its worker are copied locally with their license, so the UI/PDF renderer does not depend on a third-party JavaScript CDN. The service worker caches only public app assets. It never caches Firebase HTTP responses or private document bytes. The local demo/draft store is separate IndexedDB data.

`tests/cloud.integration.mjs` runs against the repository's Auth/Firestore/Storage emulators using the real Firebase web SDK. From the repository root after installing both root and web dependencies:

```sh
npx firebase emulators:exec --only auth,firestore,storage --project demo-printease "node --test web-app/tests/cloud.integration.mjs"
```

The test creates disposable **emulator** accounts, verifies private upload/download, stable-ID retries, trusted admin queue updates, student-visible tracking and other-student isolation. It never connects to the real Firebase project.

## Features and limits

- Google/email authentication and password reset; trusted administrator roles.
- Student dashboard, searchable/filterable orders and an admin queue.
- PDF picker, bounded 5 MB files, actual PDF page counting (up to 1,000 pages), paged canvas preview and download.
- A4/Letter/Legal, 1–100 copies, color/B&W and duplex; review before submission.
- Saved per-account drafts and stable request IDs for uncertain upload/write retries. The app checks an existing order before retrying and never deletes a PDF after an uncertain write.
- Atomic queue acceptance using Philippine dates, rejection reasons and pending student cancellation.
- Local demo persistence, cross-tab updates and offline app assets after the first successful visit.
- Mobile safe-area spacing, keyboard-friendly forms, accessible labels, reduced-motion support and system dark mode.

Live submissions and status updates need internet. Real Firebase documents are not deliberately persisted in an offline document cache; the PDF preview exists in memory while open. Local drafts/demo files remain in browser storage, which Safari may clear under storage pressure or when the user clears website data. Sign-out removes the current draft. The demo and native app are separate local demos; only **live Firebase** accounts/orders are shared.

Prices are samples (PHP 2 B&W / PHP 10 color per side; Legal adds PHP 1). Duplex saves sheets, not printed-side charges. The shop reviews page counts and final pricing; server-side PDF inspection is not implemented. There is no payment processing, direct printer integration, multi-shop support or background push notification feature. Up to 500 live orders are loaded; extend with pagination/archive policy for long-running use.

## Verification

19 unit/DOM/repository tests and one real-web-SDK Firebase emulator integration scenario pass. The static production build succeeds. Actual browser visual QA was unavailable in the managed environment because its required browser-control tool was not exposed. Physical iPhone Safari testing, Google account chooser sign-in, live Firebase permissions/billing, bucket CORS and Wi-Fi/cellular timing targets remain owner/device checks. DOM checks are not a claim of real Safari execution.
