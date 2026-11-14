# Sign in to PrintEase with Google / Gmail

Use **Continue with Google** on the live sign-in or registration screen. The system account chooser lets the user select a Google account. The app does not ask for or receive that account's password. Firebase validates the returned Google ID token and creates or signs in the corresponding Firebase user; the existing trusted admin-claim check determines access to the shop workspace.

## Configure Firebase on your computer

1. Open your **PrintEase** project in Firebase Console.
2. Go to **Build → Authentication → Sign-in method → Add new provider → Google** (or select Google if already listed).
3. Enable Google, set a public-facing project name if prompted, select your **project support email**, and Save. Google Analytics is not required.
4. In Android Studio's terminal, from your local PrintEase root, run:

   ```powershell
   .\gradlew.bat :app:signingReport
   ```

   On macOS/Linux use `./gradlew :app:signingReport`.
5. Find **Variant: liveDebug** in the output. Copy its **SHA1** and **SHA-256** fingerprints. Use fingerprints from **your computer**, because its debug signing key differs from other developers' keys.
6. In Firebase **Project settings → General → Your apps**, open the Android app registered as **`com.printease.printease`**. Choose **Add fingerprint** and save the SHA-1 and SHA-256 values. Register the certificate of any other developer/device build or Play signing certificate separately when applicable.
7. Download a **fresh `google-services.json`** from this Android app's settings, after enabling Google and adding the fingerprints. Replace your local **`app/google-services.json`**. Older downloads may lack the generated `default_web_client_id` required by Google sign-in.
8. Sync Gradle, select **liveDebug** and run **app**. Use a phone or an emulator image with **Google Play services**. Ensure it has a Google account and an internet connection.
9. Tap **Continue with Google** and choose your account. You do not need to fill the email/password fields first. A new account appears under Firebase Authentication → Users, with the Google provider.

The SDKs and Google Services Gradle plugin are already included. Do not paste duplicate dependencies from the Firebase wizard. Keep `google-services.json` local (Git ignores it). Never add service-account keys or Google passwords to this project.

## Admin using Google

Sign in with the intended administrator's Google account once so Firebase creates its user record. Copy that user's UID from Authentication → Users and use the trusted `tools/set-admin.cjs` procedure in `FIREBASE_SETUP.md`. Sign out and sign in again to obtain the new claim. A Google account does not automatically become an administrator, regardless of its email or display name.

## Expected behavior

- Both new and returning users use the same Google button. The account chooser is explicit rather than restricted to previously authorized accounts.
- Email/password remains an optional alternative if that provider is enabled in Firebase.
- Closing the chooser simply returns to sign-in; it is not shown as a login failure.
- All authentication actions are disabled while a sign-in request is in progress.
- Sign-out clears the Firebase session and requests a Credential Manager session reset, so a later login can select an account again. This does not sign the phone out of Google or remove its account.
- Demo mode stays local and has no Google button.
- ID tokens and Google passwords are not logged or saved by the application. Tokens are handed directly to Firebase for validation.

## Troubleshooting

- **CONFIGURATION_NOT_FOUND / sign-in method disabled:** enable Firebase Authentication and the Google provider in the same project as your JSON file.
- **Google sign-in needs setup:** download the JSON again after enabling Google. It must include a Web OAuth client (`client_type: 3`), which the Google Services plugin turns into `default_web_client_id`. Do not substitute the Android OAuth client ID.
- **DEVELOPER_ERROR / configuration error:** verify package name, the liveDebug signing certificate's SHA-1/SHA-256, and the updated JSON. Reinstall the newly rebuilt app and allow Firebase configuration changes a short time to propagate.
- **No credential/provider available:** check that Google Play services is installed and updated, add a Google account under Android Settings, and check connectivity. An AOSP-only emulator is insufficient.
- **Account exists with a different credential:** use the account's existing sign-in method first. The app does not bypass verification by automatically linking unverified accounts.

A real Google sign-in cannot be validated using fake tokens, a synthetic configuration file, or Firebase emulators alone. Perform the account chooser → Firebase → student dashboard check on your configured device. Then sign out and verify a different account can be selected. Confirm the administrator's claim still produces the shop workspace.
