# Shop settings, page selection, pickup alerts and retention

PrintEase displays **Nano Print Alangilan**, near BSU Alangilan and outside campus. The location button opens the owner-provided [Google Maps listing](https://maps.app.goo.gl/gBCZetYJyz46RoeQ9), verified on November 20, 2026. The link resolves to Nano Print Alangilan at marker coordinates **13.7853824, 121.0733167** (Google Maps feature `0x33bd0ff51a980455:0x1c73b020a3978e11`). These are the place marker coordinates, not the map viewport centre.

The project owner provided the street address as **Neptune St., Golden Country Homes, Alangilan, Batangas City**, hours **6:00 AM–10:00 PM** (days unspecified), and business email **nanoprint.alangilan@gmail.com**. Printing prices and shop participation still need confirmation. The Facebook page URL is pending. Older saved settings named “Nano Alangilan” display the corrected name when loaded. Other administrator-configured shops retain a map search using their own name and address. No new Firebase fields or rules deployment are needed for this location update.

## Activate the update

1. Pull the latest `kotlin-native` branch, keep your local `app/google-services.json`, sync Gradle, and run `liveDebug`.
2. Deploy the matching rules before using the new clients. From the repository root:

   ```sh
   npm ci
   npx firebase login
   npx firebase deploy --only firestore:rules,firestore:indexes --project printease-ce44e
   ```

3. Sign in using the shop account with a console-created `admins/<uid>.enabled: true` grant or a trusted `admin: true` custom claim. Open **Account → Manage shop details & rates**. Set the confirmed shop name, address/landmark and pickup hours. Save. Android and web listen to `shopSettings/current` in the same project. Students cannot edit these settings.
4. Enter whole-peso B&W, color and Legal surcharge rates. Defaults remain ₱2, ₱10 and +₱1 per printed side. Each new order stores its own `pricing` snapshot and total. Rate changes do not change old quotes. Old orders without a snapshot use the original sample rates. New clients reject a changed quote and ask the student to review again. Deploy clients together when changing rates: old clients with hardcoded prices cannot submit an outdated price.
5. Switch **Accept new requests** off to pause new submissions. Existing requests can still move through the queue. A retained draft can be sent once the shop resumes. A retry of an already-saved request first resolves its existing ID.

## Pages and pricing

Leave **Pages to print** blank for all pages, or enter `1-3, 5, 8-10`. Ranges refer to physical PDF page positions starting at 1, not printed labels within the document. The app sorts and merges overlapping/adjacent ranges, bounds them to the PDF's actual page count and supports up to five normalized ranges.

The price is selected pages × copies × the saved rate per printed side. Duplex reduces sheets, not the printed-side price; odd selections round up separately for each copy. Every selected page uses the same color mode. The full original PDF is retained for preview. The shop operator must apply the displayed page selection, paper, copies and duplex options in their printer dialog; this application does not send Bluetooth or direct network printer jobs.

Rules independently validate each persisted range, the selected-page count, the current rate snapshot and total. Clients cannot change submitted PDF paths, options, quotes or completion times. Admin status changes preserve these fields.

## Android pickup notifications

Cloud Messaging is included in the Android client, but **the server function must be deployed before background alerts can be delivered**. This workspace has no Firebase deployment credentials, so these functions have not been activated in the live project.

On a trusted computer with access to the Firebase project:

```sh
npm ci --prefix functions
npx firebase deploy --only functions --project printease-ce44e
```

Functions use Node.js 22, region `asia-southeast1`, Firebase Admin SDK, Firestore events, Cloud Scheduler and Cloud Messaging. Deployment may require Blaze billing and enabling the Google Cloud APIs/permissions requested by Firebase. Review current pricing before accepting a billing change. Never put a service-account key in the mobile app or web source.

Then sign in as a student, open **Account → Pickup alerts**, switch it on and allow Android notification permission. Tokens are private to that Firebase user under `users/<uid>/devices/<token-hash>`. The `pickupReady` function sends a data message only when an order enters **Ready for Pickup** and is still ready when processed. The Android service checks both the current signed-in UID and the device's opt-in before showing a generic pickup alert. It does not put student or PDF names on the lock screen. Signing out clears visible alerts; old-account data messages cannot display for a different signed-in user.

Delivery requires internet and Google Play services, may be delayed by device power settings, and is unavailable while the app is force-stopped. Retries use a server event ledger and one Android notification ID per order. Messages can be delivered more than once after a partial send failure; the same order replaces its existing notification. The website shows an in-app ready message when an observed order changes to ready; it does not claim iPhone background push support.

## Completed PDF cleanup

Automatic deletion is **off (`retentionDays: 0`) by default**, including when the functions are deployed. An administrator can explicitly choose 7, 14 or 30 days. The daily **02:00 Asia/Manila** function removes only completed PDFs past their completion timestamp. Pending, accepted, printing, ready, rejected and cancelled PDFs are not removed by this policy. Older completed orders without a trustworthy completion timestamp are skipped.

Order history, print options and saved quote remain. `documentDeletedAt` records the removal and the app explains why preview is unavailable. This policy applies to Firebase Storage copies; it cannot recall PDFs someone already downloaded or printed. Missing objects are safe to retry after a partial failure. Do not enable a period until the shop has agreed to it and the cleanup function is deployed.

## Acceptance checks on the real project

- Sign in on Android and iPhone web with the same student account; submit a selected-page PDF and compare the saved quote, selection and status on both clients.
- Use a separate admin account to edit rates. Verify old quotes remain unchanged and a fresh request uses the new rate. Try saving settings concurrently; the older version must be rejected.
- Pause requests and verify students retain drafts but cannot create new orders.
- With alerts opted in, progress a test order through Accepted → Printing → Ready. Verify the notification opens the correct order. Deny permission, disable alerts, sign out and switch accounts; private alerts must not appear.
- Leave retention off during initial testing. In an isolated Firebase test project, create an expired completed order and verify cleanup retains its metadata and never removes an active PDF.
