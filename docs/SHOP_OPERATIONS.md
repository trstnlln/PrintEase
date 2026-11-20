# Shop settings, page selection, pickup alerts and retention

PrintEase displays **Nano Print Alangilan**, near BSU Alangilan and outside campus. The location button opens the owner-provided [Google Maps listing](https://maps.app.goo.gl/gBCZetYJyz46RoeQ9), verified on November 20, 2026. The link resolves to Nano Print Alangilan at marker coordinates **13.7853824, 121.0733167** (Google Maps feature `0x33bd0ff51a980455:0x1c73b020a3978e11`). These are the place marker coordinates, not the map viewport centre.

The project owner provided the street address as **Neptune St., Golden Country Homes, Alangilan, Batangas City**, hours **6:00 AM–10:00 PM** (days unspecified), and business email **nanoprint.alangilan@gmail.com**. Printing prices and shop participation still need confirmation. The Facebook page URL is pending. Older saved settings named “Nano Alangilan” display the corrected name when loaded. Other administrator-configured shops retain a map search using their own name and address. The map update alone needs no new fields. The newer estimates, contacts and alert subscriptions require the current Firestore rules.

## Activate the update

1. Pull the latest `kotlin-native` branch, keep your local `app/google-services.json`, sync Gradle, and run `liveDebug`.
2. Deploy the matching rules before using the new clients. From the repository root:

   ```sh
   npm ci
   npx firebase login
   npx firebase deploy --only firestore:rules,firestore:indexes --project printease-ce44e
   ```

3. Sign in using the shop account with a console-created `admins/<uid>.enabled: true` grant or a trusted `admin: true` custom claim. Open **Account → Manage shop details & rates**. Set the confirmed shop name, address/landmark and pickup hours. Save. Android and web listen to `shopSettings/current` in the same project. Students cannot edit these settings.
4. Enter whole-peso B&W, color and Legal surcharge rates. Sample defaults remain ₱2, ₱10 and +₱1 per printed side. Leave **Rates confirmed with the shop** off until the shop verifies them. Each new order stores its own `pricing` snapshot and total. Rate changes do not change old quotes. Old orders without a snapshot use the original sample rates. New clients reject a changed quote and ask the student to review again. Deploy clients together when changing rates: old clients with hardcoded prices cannot submit an outdated price.
5. Switch **Accept new requests** off to pause new submissions. Existing requests can still move through the queue. A retained draft can be sent once the shop resumes. A retry of an already-saved request first resolves its existing ID.

## Pages and pricing

Leave **Pages to print** blank for all pages, or enter `1-3, 5, 8-10`. Ranges refer to physical PDF page positions starting at 1, not printed labels within the document. The app sorts and merges overlapping/adjacent ranges, bounds them to the PDF's actual page count and supports up to five normalized ranges.

The price is selected pages × copies × the saved rate per printed side. Duplex reduces sheets, not the printed-side price; odd selections round up separately for each copy. Every selected page uses the same color mode. The full original PDF is retained for preview. The shop operator must apply the displayed page selection, paper, copies and duplex options in their printer dialog; this application does not send Bluetooth or direct network printer jobs.

Rules independently validate each persisted range, the selected-page count, the current rate snapshot and total. Clients cannot change submitted PDF paths, options, quotes or completion times. Admin status changes preserve these fields.

## Pickup estimates, collection and repeat requests

In an active request, staff can set **Minutes from now** (1 minute to 7 days) or clear an existing estimate. Students see the estimated time and a note that it may change. Queue numbers identify requests; they do not imply an exact wait.

At handover, ask the student to open their order and provide the six-character **collection code**. Enter the matching code before marking Completed. The code is a workflow check within the authenticated shop workspace. Never grant administrator access to ordinary students.

A student can select **Print this again** on a completed, cancelled or rejected order with an available PDF. It prepares a fresh draft and requires review of current prices. Replacing an unfinished draft requires confirmation; download/validation failure preserves the existing draft.

## Pickup notifications without Firebase billing

Android **Account → Pickup alerts** enables periodic WorkManager checks for the most recent 100 orders. Checks run about every 15 minutes, and Android battery/network settings can delay them. They need internet and notification permission. Sign-out cancels checks and clears visible alerts; both the worker and notification display verify the same opted-in UID. One ready notification is recorded per account/order on this device. Keep Orders open for immediate status updates.

The web companion uses encrypted Web Push via the existing Site Worker. On iPhone, use iOS 16.4 or later, add PrintEase to the Home Screen in Safari, then open the installed app and choose **Account → Enable pickup alerts**. The Account test button sends a generic test alert. Browser permission is requested only after the user taps Enable.

The Site requires runtime `PUSH_VAPID_PUBLIC_KEY` and secret `PUSH_VAPID_PRIVATE_KEY`, already configured for this project, plus the current Firestore rules. Device subscriptions live under `users/<uid>/pushSubscriptions`; only the owner can register them. Staff can send ready alerts through the authenticated API and retry **Send pickup alert** if delivery failed. Per-order/device receipts skip previously accepted sends. Private keys remain server-side, known provider hosts are checked before sending, and notification text contains no document names or collection codes. A service-worker account marker prevents alerts from displaying after local opt-out or account changes.

Notification delivery is best-effort. A crash after provider acceptance but before receipt persistence can cause a repeated send; the same request uses one notification tag. A failed alert never rolls back the saved Ready status. Real iPhone/Android notification delivery still needs device verification.

The legacy optional Firebase Cloud Messaging functions remain available in `functions/` for owners who choose to deploy them separately. They are not required for these periodic Android reminders or web alerts and may require Firebase billing. They have not been deployed by this workspace.

## Quotes, corrections and add-ons

Publish the current Firestore rules, then refresh the web app or sign in again on Android. Updated clients can detect the published rules. A new request from an updated client starts in shop review. The shop can request a correction with a note or send a final price in whole Philippine pesos. The student can update their PDF or options on the same request, or approve or decline the latest price. Staff can assign a queue number after the student approves. Orders from earlier clients can still use their original workflow.

Bookbinding and lamination are optional requests on the print form. Staff include confirmed add-ons in the final quote. Students can ask the shop about its other advertised services. New requests stay paused when staff enable confirmed rates and keep the shop's availability switch off.

## Completed PDF cleanup

Shop administrators can select **Keep files**, **7 days**, **14 days** or **30 days** in shop settings. The default is Keep files. Once a completed order reaches the selected period, choose **Remove expired PDF** in its details and confirm. The app removes the current PDF and earlier uploads stored specifically for that order. Print options, pricing, status and the request ID remain in order history. This action does not affect copies someone already saved elsewhere.

Legacy uploads shared by multiple orders are excluded from this workflow. Staff initiate cleanup from the order screen. There is no scheduled cleanup or automatic orphan removal in the hosted deployment. The legacy Firebase Functions retention job applies only to Firebase Storage if deployed separately.

## Acceptance checks on the real project

- Sign in on Android and iPhone web with the same student account; submit a selected-page PDF and compare the saved quote, selection and status on both clients.
- Use a separate admin account to edit rates. Verify old quotes remain unchanged and a fresh request uses the new rate. Try saving settings concurrently; the older version must be rejected.
- Pause requests and verify students retain drafts but cannot create new orders.
- With alerts opted in, progress a test order through Accepted → Printing → Ready. Verify the notification opens the correct order. Deny permission, disable alerts, sign out and switch accounts; private alerts must not appear.
- Leave retention off during initial testing. In an isolated Firebase test project, create an expired completed order and verify cleanup retains its metadata and never removes an active PDF.
