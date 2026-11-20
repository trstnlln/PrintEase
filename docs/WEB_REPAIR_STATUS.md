# Web repair status

## Confirmed service conditions

- Email/Password was disabled when account creation failed. The owner enabled and saved it. A subsequent temporary-account registration and email sign-in API check succeeded; the test account was deleted afterward.
- The configured Firebase Storage bucket does not exist. The owner chose a PDF storage solution that does not require Firebase Blaze billing.
- The live Firestore API returned SERVICE_DISABLED before the owner began setup. The console then reported that the database already exists. Open the existing `(default)` database; do not create a second ID.
- Firebase's authorized domains already include the published Site hostname.

The observations above describe the original failures. The deployment section below records the newer live checks.

## Source changes

- Preserve file selection when returning from the iPhone file picker; remove the window-focus account rebind.
- Snapshot actual form fields before rendering busy state, including Safari-autofilled values.
- Keep persistent authentication/request errors near the controls.
- Keep selected PDF bytes in memory when Safari refuses IndexedDB storage.
- Exercise the actual PDF parser, with clear unreadable-iCloud/corrupt-file errors.
- Add a private hosted PDF service and matching Kotlin/web clients. Verify original Firebase ID tokens, size/signature/hash, owner/admin access and immutable object paths.
- Keep legacy Firebase Storage reads. Disable misleading automatic-retention controls for hosted PDFs.
- Allow trusted console-created administrator grants; clients cannot self-promote.
- Preserve the session and existing orders during same-account refresh.
- Check production Worker assets, rules, emulator integration and native builds in CI.

## Deployment and live checks — November 20, 2026

The workspace recovered and Site version **6** was deployed successfully to https://printease-campus.consideratefennel8.chatgpt.site. The Site remains public. Its source commit is `96248a63f19d76d2d1645a41b8fe5b3261d9808b` in the separate Sites source repository; this is distinct from the GitHub branch history.

The managed storage manifest uses `"r2": "BUCKET"`. The hosting service validated this format and provisioned the private binding. No Firebase Storage bucket or Blaze upgrade is required for new PDFs.

Live checks confirmed:

- Hosted storage readiness returns 200 and ready true.
- Temporary email registration and password sign-in return 200.
- The owner published the Firestore rules. An authenticated shop-settings read reaches the database and returns 404 because no settings document exists yet; clients use the defaults.
- An unauthenticated PDF request is rejected with 401.
- A private PDF upload and authenticated download return 200, with identical bytes/SHA-256 and `Cache-Control: private, no-store`.
- The earlier PDF-verification QA accounts were deleted. The final index-check account (`printease-index-qa-…@example.invalid`) still needs manual removal from Firebase Authentication after its automatic cleanup request failed; the owner was given the exact identifying prefix. No production order was created. Synthetic PDF test objects remain private; hosted orphan cleanup is not implemented.

**The orders composite index is now usable:** `orders`, collection scope, `ownerId` Ascending and `createdAt` Descending. After the owner created the index, a live signed-in account queried its own orders successfully with HTTP 200. The verification created no production order. [Firebase setup](FIREBASE_SETUP.md) records the index configuration.

The default shop display name is **Nano Alangilan**, near BSU Alangilan. The exact address, hours and shop participation still need confirmation. The shop name, pickup details and rates remain editable by its trusted administrator.

## Remaining device and operational checks

Test the full student request and shop-admin workflow on the actual iPhone now that the index is usable. API checks do not exercise Safari’s picker, pop-up or visual layout. Real Safari visual QA and the real Google account chooser have not been exercised by the emulator suite. Hosted PDF retention and orphan cleanup are not implemented; files stay private and retention is shown as Off.

Page selections support up to five separate ranges, or all pages. Both clients reject larger selections before submission; the rule tests cover the same limit.
