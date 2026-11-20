# Web repair status

## Confirmed service conditions

- Email/Password was disabled when account creation failed. The owner enabled and saved it. A subsequent temporary-account registration and email sign-in API check succeeded; the test account was deleted afterward.
- The configured Firebase Storage bucket does not exist. The owner chose a PDF storage solution that does not require Firebase Blaze billing.
- The live Firestore API returned SERVICE_DISABLED before the owner began setup. The console then reported that the database already exists. Open the existing `(default)` database; do not create a second ID.
- Firebase's authorized domains already include the published Site hostname.

These observations are point-in-time diagnostics. They do not establish that current rules, indexes or the hosted document service are live.

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

## Deployment is separate

The coding workspace became unavailable during this repair. Source changes can be committed and checked through GitHub, but publishing the new Worker to the existing Sites source repository requires the workspace connection to recover. Until a new Site deployment succeeds, the live website remains on its previous version and hosted PDF uploads are not available.

The owner must also publish the validated current Firestore rules and index to the existing database. These cannot be applied with a public Firebase client config. [Firebase setup](FIREBASE_SETUP.md) gives console and CLI steps.

The existing Site audience is public and must remain public on the next deployment.

## Remaining device and operational checks

After deployment and Firebase setup, test registration/sign-in, selecting a PDF from iPhone Files/iCloud, uploading and previewing it, and shop-admin access. Real Safari visual QA and the real Google account chooser have not been exercised by the emulator suite. Hosted PDF retention and orphan cleanup are not implemented; files stay private and retention is shown as Off.

Page selections support up to five separate ranges, or all pages. Both clients reject larger selections before submission; the rule tests cover the same limit.
