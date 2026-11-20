# PrintEase

A Flutter + Dart + Firebase Android prototype for BSU Alangilan's campus printing queue. Open this folder in **Android Studio**. Student and shop administrator views live in the same app.

## Run the demo in Android Studio

1. Install the Flutter SDK (stable; Dart >=3.10) and Android Studio's **Flutter** plugin (which includes Dart support).
2. In Android Studio's SDK Manager, install the Android SDK and an emulator image. Set the Flutter SDK path in Settings → Languages & Frameworks → Flutter.
3. Open this project folder (the folder containing `pubspec.yaml`, not only `android/`). Run `flutter pub get` in the terminal.
4. Start an Android emulator or connect a phone with USB debugging. Select `lib/main.dart` and press Run.
5. Choose **Try as student** or **Try as administrator**. In Account, switch demo roles to manage the same orders. Demo mode is enabled by default; it uses in-memory data and resets on restart. Sample orders have no attached PDF; upload your own to test preview.

Terminal equivalent:

```sh
flutter pub get
flutter run --dart-define=DEMO=true
```

A web target is included for convenient demo checks (`flutter run -d chrome`). Android is the primary target.

## Connect your Firebase project

No live Firebase project or credentials are bundled. Demo mode does not contact Firebase.

1. Create a Firebase project. Enable **Authentication → Email/Password**, create **Cloud Firestore** in production mode, and provision **Cloud Storage**. Cloud Storage requires a Blaze billing account; check current Firebase quotas and pricing, set budget alerts, and use test PDFs.
2. Install Node.js, Firebase CLI and FlutterFire CLI:

```sh
npm install -g firebase-tools
firebase login
dart pub global activate flutterfire_cli
flutterfire configure
```

3. Select your project and Android platform. The existing Android application ID is `com.printease.printease`. Allow the CLI to replace `lib/firebase_options.dart` (a setup placeholder) and generate native configuration. If prompted to overwrite the file, choose yes. If you also want live Firebase on web, select web too. Keep service-account credentials outside the repository; they never belong in the app.
4. Deploy the included rules to **your project** before using real student documents:

```sh
firebase deploy --only firestore:rules,firestore:indexes,storage --project YOUR_PROJECT_ID
```

5. In Android Studio, edit the run configuration for `lib/main.dart`, add `--dart-define=DEMO=false` to Additional run args, and restart the app. Or:

```sh
flutter run --dart-define=DEMO=false
```

6. Register student accounts using the app. Pre-create the shop administrator account in Firebase Authentication and copy its UID. Assign its custom claim from a trusted machine:

```sh
npm ci
# Set GOOGLE_APPLICATION_CREDENTIALS to a service-account JSON path using
# your operating system's environment settings. Do not paste keys into source.
node tools/set-admin.cjs THE_ADMIN_USER_UID
```

The service account needs permission to manage Firebase Authentication users. The administrator must sign out and back in afterward. Student registration cannot grant admin access. In live mode there is no role switch. Use the same Firebase project for the service account, app, and rules.

## Included features

- Email/password registration and sign-in; trusted administrator custom claim.
- Student dashboard, order history, filters, account details, and shop information.
- PDF selection (5 MB maximum), signature check, actual PDF parsing, page counting, and preview. Invalid/unreadable files produce a retryable error. Password-protected PDFs are not supported for this prototype.
- A4, Letter or Legal paper; 1–100 copies; black & white or color; single/double sided.
- Review-before-submit and upload progress. PDF bytes go to private Cloud Storage; order metadata goes to Firestore. Uploads and submissions require connectivity.
- Administrator PDF preview, acceptance, rejection with reason, and ordered status updates: Pending → Accepted → Printing → Ready for Pickup → Completed.
- Atomic daily queue counter, assigned on acceptance. Queue day uses Philippine time (UTC+8); numbers are unique within that day when using the acceptance transaction. Older orders display their queue date.
- Firestore listeners synchronize views while the app is running. No background push notifications are included.
- Private document rules, owner-scoped order queries, and administrator-only queue/status changes. Firestore rules also validate the estimate against the sample price formula.

## Pricing

These are **sample rates**, not confirmed shop prices:

- A4 / Letter: PHP 2 per black & white printed side; PHP 10 per color printed side.
- Legal adds PHP 1 per printed side.
- Estimate = pages × copies × rate. Duplex reduces sheet count, not price per printed side.
- Duplex sheets = ceil(pages / 2) × copies. Blank final backs are not charged.

Update `PrintOptions.rate`, the pricing text in the Account screen, and `validPrice` in Firestore rules together if the shop changes rates. All pages in an order use the selected color mode; no mixed-color detection or page ranges. The shop reviews the PDF and confirms pricing before accepting. Page count is parsed on the client; a modified client could misreport it, so the administrator must verify it. Strict server-side PDF validation would require a trusted backend.

## Project layout

- `lib/main.dart`: theme, bootstrapping and Firebase initialization.
- `lib/screens.dart`: authentication, dashboards, submission, details and PDF preview.
- `lib/models.dart`: order model, pricing, status transitions and queue day.
- `lib/store.dart`: Firebase operations plus isolated in-memory demo mode.
- `firebase/`: Firestore/Storage rules, indexes and emulator tests.
- `tools/set-admin.cjs`: trusted administrator provisioning.
- `android/`: generated Android Studio project and manifest with internet permission.

The app uses Flutter's `ChangeNotifier` / `ListenableBuilder`; no additional state management framework is needed for this scope. Orders are sorted on the client to avoid requiring composite indexes for the small prototype dataset. A production deployment should use pagination.

## Checks

```sh
flutter analyze
flutter test

npm ci
npm run test:rules
```

Rule tests launch local Firestore and Storage emulators against the `demo-printease` project; they do not access a production project. Firebase emulators require a supported Java runtime. Flutter native host tests may require a C/C++ toolchain for the PDF library. Android development needs the SDK/NDK versions requested by Flutter and its plugins.

## Before your class demonstration

Use two Android devices/emulators with live Firebase: one student, one administrator. Test 10 uploads up to 5 MB; record completion time, 20 orders for field accuracy, concurrent acceptance from two administrator sessions for unique numbers, and status propagation on Wi-Fi and cellular. Verify estimates manually for color, Legal, copies, and odd-page duplex cases. Compare measurements to your proposal's 15-second upload and 5-second update targets; they are evaluation targets, not guaranteed network performance.

Submission writes Storage and Firestore in separate operations. A failed metadata write triggers best-effort file cleanup, but network failures can still leave an orphaned file or an uncertain submission result. Check order history before retrying and inspect old orphaned uploads manually. The prototype has no automatic retention policy or cancellation flow. Set an agreed retention policy before using real student documents.

Actual printing and payments happen manually at the shop. No printer integration, online payments, multiple shops, offline submissions, or automatic printing is included. Release signing is not configured: use your own Android signing key before distributing a production release. The generated Gradle project signs demo release builds with the debug key.
