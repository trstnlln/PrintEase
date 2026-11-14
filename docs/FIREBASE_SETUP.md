# Connect PrintEase to Firebase

## 1. Create the project

Go to https://console.firebase.google.com/ and select **Create a new Firebase project**. Name it **PrintEase**, choose your project ID, and finish. Analytics and Gemini assistance are optional; neither is required by this prototype.

## 2. Register Android

From Project overview, click the **Android** icon (or Add app → Android):

- Android package name: **`com.printease.printease`**
- App nickname: **PrintEase**
- SHA-1 and SHA-256: required for Google sign-in; follow `GOOGLE_SIGN_IN.md` to obtain them from your local signing report.

Register the app and download **`google-services.json`**.

On the Kotlin branch, put it here:

```
PrintEase/
  app/
    build.gradle.kts
    google-services.json    <-- here
    src/
  build.gradle.kts
  settings.gradle.kts
```

If you are still on the old Flutter `main` branch, switch to `kotlin-native` before following this folder layout. The file is ignored by Git; keep it local. Never include a Firebase service-account private key in the app.

The console's **Add Firebase SDK** page explains Gradle configuration. This repository already includes the Google Services plugin and the Auth, Firestore and Storage SDKs, so you do not need to copy those snippets again. Click Next/Continue to console.

## 3. Enable services

- **Build → Authentication → Get started → Sign-in method → Google**. Enable Google, choose a support email and Save. Then add your signing fingerprints and download a fresh JSON file as described in [Google sign-in setup](GOOGLE_SIGN_IN.md). Optionally enable **Email/Password** for the alternate sign-in form; email link sign-in is not required.
- **Build → Firestore Database → Create database**. Choose production mode. Select an appropriate nearby region (for example Singapore if offered); choose carefully because a database location cannot simply be changed later.
- **Build → Storage → Get started**. Provision the default bucket. Firebase may require Blaze billing. Review current pricing and configure budget alerts before confirming. Firestore stores metadata; Storage stores PDFs.

## 4. Deploy rules

Use Node.js and the Firebase CLI on your computer. From the Kotlin project root:

```sh
npm ci
npx firebase login
npx firebase deploy --only firestore:rules,firestore:indexes,storage --project YOUR_PROJECT_ID
```

Use the actual Project ID shown under Firebase Project settings → General, not the display name. The demo project ID used in tests is not your live project.

The included rules allow students to access only their own orders/documents, submit pending requests and cancel their own pending requests. Administrators can read requests, accept with an atomic queue counter, reject with a reason and advance processing. Students cannot grant themselves an admin role or modify prices/statuses. Only administrators can delete uploaded documents.

## 5. Run live

In Android Studio, sync Gradle after adding the JSON file. Open **Build Variants** and choose **`liveDebug`** for the app module, then Run on an emulator or phone. No Flutter SDK is needed. The demo variant is deliberately separate and never uploads to Firebase.

Choose Continue with Google, or register a student with email/password if that provider is enabled. Firebase Authentication → Users should show that account. Submitting a PDF creates an object under Storage → Files → `documents/<uid>/` and a record under Firestore → `orders`.

If Gradle reports "No matching client found", the downloaded JSON package name does not match the chosen build variant. `com.printease.printease` is for the live app. The demo variant adds `.demo` and does not need Firebase configuration. When the JSON is present, the build filters Google Services tasks to live variants.

## 6. Make the administrator

For a Google administrator, sign in with that account once to create its Firebase user, then copy its UID under Authentication → Users. For email/password, create the shop account via Users → Add user. On a trusted computer, use a service account with Firebase Auth administration permission and set `GOOGLE_APPLICATION_CREDENTIALS` to the path of its JSON file using your OS environment settings. That service-account file is different from `google-services.json` and must remain private, outside this repository.

```sh
node tools/set-admin.cjs THE_ADMIN_UID
```

Sign out and back in to the live app. It will read the trusted `admin` claim and show the shop workspace. There is no demo role switch in live mode.

## Common issues

- **Demo orders do not appear in Firebase:** expected. Choose `liveDebug` after configuration.
- **Permission denied:** deploy these rules to the same project as the JSON file; sign in; refresh the admin claim by signing in again.
- **PDF upload fails:** ensure Storage is provisioned, billing requirements are met, rules are deployed, and the PDF is valid and ≤5 MB.
- **No admin interface:** verify the exact user's UID and claim, then sign in again.
- **Google Services plugin missing:** ensure you are opening the Kotlin repository root; sync after placing the JSON file in `app/`.
