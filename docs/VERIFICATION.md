# Verification and remaining device checks

The migration is checked with native Android builds, Android lint, Kotlin/JVM model tests, Robolectric repository/PDF tests, Compose interaction tests and Firebase emulator rules tests. Latest local results (November 17, 2026):

- Gradle 8.11.1 / JDK 17 / Android SDK 35: **BUILD SUCCESSFUL**.
- Kotlin/Android host tests: **34 passed, 0 failed** (9 model, 6 repository, 12 Compose, 7 Google credential/configuration tests).
- Firebase emulator rules: **12 passed, 0 failed**.
- Android lint: **0 errors, 21 warnings**. Remaining warnings are dependency-update notices, Kotlin-extension style suggestions and an SDK resource-folder suggestion; they are not runtime errors.
- `assembleDemoDebug` and `assembleLiveDebug`: both APKs built.
- `assembleDemoDebugAndroidTest`: device-test APK built. The two native PDF device tests were **not executed** here.
- Student/admin screenshots captured from the native Compose test renderer and visually reviewed. Dark account layout checked at 150% font scale.

- Web model, IndexedDB repository and DOM interaction tests: **24 passed**. This includes page selection, preserved quotes, admin settings, request pauses and stable-ID retry behavior.
- Real Firebase Web SDK emulator integration: **1 passed**, including private PDF upload/download, student/admin claims, daily queue transitions, selected pages and changed rates.
- Cloud service policy tests: **2 passed**; both handler exports also loaded and registered with the Functions SDK. No real FCM delivery or scheduled deletion was run against the live project.
- Native screenshots for the student/admin workspace, shop settings, selected pages and dark account layout were reviewed. Browser layout/Safari QA was unavailable in this environment; DOM tests do not establish pixel-level browser behavior.

Google Services configuration was also checked with a synthetic local JSON fixture: live configuration processed successfully and demo processing was skipped. The fixture was deleted and was not committed.

No real Firebase project credentials were available to this build. Live network integration, actual phone/emulator execution of the PDF tests, network latency targets and release signing still require the user's setup.

## Automated coverage

- Sample black-and-white/color/Legal prices, odd-page duplex sheets and large valid option combinations.
- Invalid paper/copy counts; Philippine queue-date midnight boundary; allowed and blocked workflow transitions; original Firestore metadata round-trip; admin queue sort order.
- Demo state/role persistence across repository recreation; stable submission ID and document byte preservation; invalid and oversized PDFs; student/admin restrictions; mandatory rejection reason; cancellation and terminal-state protection.
- Compose dashboard action, search empty state, rejection form validation, disabled empty submissions, dark theme at 150% font scale, and full-app student/admin role switching.
- Firebase ownership, authentication, impersonation/estimate tampering, administrator transitions, PDF MIME/size/access, missing-ID retry lookup, cancellation, counter reuse rejection, concurrent queue assignment, and admin-only deletion.

`./gradlew :app:testDemoDebugUnitTest :app:lintDemoDebug :app:assembleDemoDebug :app:assembleLiveDebug`

`npm run test:rules`

Native PDF page counting/rendering and picker import live in `app/src/androidTest` because Robolectric has no Android PDF engine implementation. Compile with `:app:assembleDemoDebugAndroidTest` and run on a phone/emulator with `:app:connectedDemoDebugAndroidTest`. Their execution must not be inferred from host test results.

The demo APK is generated at `app/build/outputs/apk/demo/debug/app-demo-debug.apk`. Live compilation without a configuration file verifies the setup-screen build; it does not validate a real Firebase project connection.

## Manual Android acceptance before a demonstration

1. Run `demoDebug` on a phone or emulator. Try student/admin roles, restart, and verify saved orders and state. Use device Back, rotation, keyboard, dark theme and larger fonts.
2. Pick a two-page PDF from Android Files. Preview both pages, choose paper/color/duplex/copies, review the estimate, submit, and switch to the administrator. Preview the submitted file and accept it. Return to the student view and check queue/status.
3. Reject a separate pending request with a reason. Verify the student sees that reason. Cancel a pending request as a student; accepted/printing requests must not offer cancellation.
4. Configure the real project using `FIREBASE_SETUP.md` and deploy its rules/index. Test with separate student/admin accounts on two devices. A student must not see another student's order or document.
5. Submit under Wi-Fi and cellular, interrupt a transfer, and retry the same draft. Verify only one order ID is created. Check Orders after an uncertain write before discarding a draft or starting another request.
6. Accept multiple requests simultaneously from separate administrator sessions. Verify distinct queue numbers and no duplicate acceptance; stale actions should show an error or the latest status.
7. Measure ten uploads (up to 5 MB) against the proposal's target: at least 90% within 15 seconds under stable connectivity. Measure status changes against the 5-second / 90% target. These are evaluation targets, not guarantees.
8. Confirm the shop's actual rates in Account → Manage shop details & rates. Deploy the matching rules before using the updated clients. Review PDF page counts before accepting; the client estimate is not a trusted server PDF inspection.

No real Firebase account/project is provisioned by this code change. Physical-device execution, real-world network latency, Firebase billing and production release signing must be validated in the user's setup. There is no claim that every possible device/network bug is eliminated.

## Google sign-in and branding update

The Google credential request, supported response types, missing-client configuration, disabled/busy button, login/registration button routing and demo isolation are covered by host tests. Both app variants build with the new adaptive logo. Authentication/configuration errors are translated into readable guidance. The Google account chooser and real Firebase token exchange still require the user’s configured Google provider, local signing fingerprints, updated JSON file and a device with Google Play services. No real Google account was used in automated tests.

The logo is available under `docs/branding/`; screenshots under `docs/screenshots/` show the current native UI.

## Shop experience 2.1 coverage

Page-range parsing rejects out-of-bounds, malformed and overflowing input, merges duplicate/adjacent selections, and prices only selected sides. Kotlin and web preserve saved rate snapshots when administrator rates change. Demo restart tests cover range/pricing/settings persistence. Security rules independently reject forged selection counts, overlapping ranges, underquoted prices, student setting edits and cross-user notification-token access. Settings use version checks to prevent silent concurrent overwrites.

Ready-alert delivery and scheduled PDF removal require the new server functions to be deployed to `printease-ce44e`; they have not been deployed from this workspace. Cleanup is off by default. Real device notifications, actual Google sign-in, iPhone Safari layouts, native PDF device tests and deletion in an isolated Firebase test project remain manual acceptance checks. See [Shop operations](SHOP_OPERATIONS.md).

The repository includes GitHub Actions for Android builds/tests/lint and Firebase/web/cloud-policy checks. A successful local run does not establish a successful hosted CI run; inspect the workflow after pushing.
