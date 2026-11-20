# Connect PrintEase to Firebase

PrintEase uses Firebase Authentication and Cloud Firestore for accounts, shop settings and orders. New PDF uploads use the private document service on the PrintEase website, backed by Sites managed object storage. You do not need to enable Firebase Storage or upgrade to Blaze for this setup. The hosted document service must be deployed before these clients can submit PDFs.

Use the existing project **printease-ce44e**. Do not create another project or a second database to fix an existing-database message.

## 1. Register Android

In Project settings → General, register Android package **com.printease.printease** and download `google-services.json`. Put the file next to `app/build.gradle.kts`:

```
PrintEase/
  app/
    build.gradle.kts
    google-services.json
    src/
  build.gradle.kts
  settings.gradle.kts
```

The JSON is ignored by Git. This repository already configures the Google Services plugin and Firebase SDK dependencies; do not paste duplicate Gradle snippets. A service-account key is a different file and must never be included in the app, website or repository.

For Google sign-in, follow [Google setup](GOOGLE_SIGN_IN.md) to add your local liveDebug SHA-1 and SHA-256 fingerprints, then download the updated JSON.

## 2. Enable Authentication

Open Authentication → Sign-in method:

1. Enable **Email/Password**, turn on its first switch and Save. The email-link switch is not required.
2. Enable **Google**, select a project support email and Save.
3. Under Authentication → Settings → Authorized domains, add `printease-campus.consideratefennel8.chatgpt.site`. Enter only the hostname.

A Gmail address can be used with either method. A Google account uses Continue with Google; the email form uses a PrintEase password.

## 3. Open or create the default Firestore database

Open Firestore Database. If `(default)` is listed, open it. If Firebase says **Database already exists**, close the creation dialog, refresh and select the existing `(default)` database. Do not choose another database ID.

If no database exists, create the **Standard** edition database in Production mode, ID `(default)`. Choose the region carefully, preferably one near your users; it cannot simply be changed later. This application does not need Enterprise edition.

If the existing database is still inaccessible, check Google Cloud → APIs & Services → Enabled APIs for **Cloud Firestore API** in this same project. An existing database and a disabled API are different conditions.

## 4. Publish rules and indexes

From the Kotlin repository root, with Node.js installed:

```sh
npm ci
npx firebase login
npx firebase deploy --only firestore:rules,firestore:indexes --project printease-ce44e
```

Alternatively, open the existing database → Rules, replace the editor with the full contents of [`firebase/firestore.rules`](../firebase/firestore.rules), and click Publish. Use the current `kotlin-native` version. Do not use blanket `allow read, write: if true` rules.

The orders list needs this collection-scope composite index on `orders`:

| Field | Direction |
| --- | --- |
| ownerId | Ascending |
| createdAt | Descending |

Deploying `firestore:indexes` creates the index from the repository. If using the console, add it under Indexes → Composite and wait until it finishes building.

Rules enforce student ownership, quote validation, document path/hash binding, immutable submitted details, allowed status transitions and atomic queue numbers. Clients cannot grant themselves an administrator role.

## 5. Create the shop administrator

Sign in with the shop account once, then copy its exact UID from Authentication → Users.

In Firestore → Data, create collection `admins`, document ID equal to that UID, and field **enabled** of type **boolean**, value **true**. Only a trusted project owner in the Firebase console can create this grant; the app cannot write it. Sign out and back in, or use Refresh account & orders.

The existing custom-claim method in `tools/set-admin.cjs` is still supported for owners who already administer claims. No private service-account file is needed for the console method.

## 6. Deploy the PDF service and run live

The website's build produces a Worker at `web-app/dist/server/index.js`. Its Sites hosting manifest declares the private `BUCKET` binding. Deploy the complete web build through Sites; see [web setup](../web-app/README.md). Confirm `/api/status` returns `"ready": true` after deployment. The status endpoint does not expose files.

Then sync Gradle in Android Studio, select **liveDebug** in Build Variants and Run. The website and native app use the same Firebase accounts and orders. New private files have keys `hosted/<uid>/<sha256>.pdf`; Firestore stores the key, hash, options and order status, not the PDF itself.

The separate **demoDebug** build never writes live orders. Demo mode works without Firebase setup.

## Troubleshooting

- **Account creation says provider disabled:** enable Email/Password in this exact project and Save.
- **Database already exists:** open the existing `(default)` database.
- **Orders cannot sync:** check database/API availability, current rules and the composite index.
- **Storage is not ready:** the hosted Worker or BUCKET binding has not been deployed. Enabling Firebase Storage is not the fix for these new clients.
- **No matching client found:** download Android JSON for `com.printease.printease`; the demo package has a separate `.demo` suffix.
- **No shop workspace:** check the exact account UID and `admins/<uid>.enabled`, then refresh.
- **iPhone file cannot be read:** download the PDF from iCloud to Files first, then select it again. Valid files must be unencrypted PDFs, at most 5 MB and 1,000 pages.

Page selections support up to five separate ranges, or all pages. Both clients reject larger selections before submission; the rule tests cover the same limit.
