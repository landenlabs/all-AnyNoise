# Google Services Used by AnyNoise

Source of truth: `app/build.gradle`, `app/google-services.json`, and usages under
`app/src/main/java/com/landenlabs/allAnyNoise/`.

Firebase project: `all-anynoise` (project number `882437431711`).
SDK versions pinned via `com.google.firebase:firebase-bom:34.16.0`.

## Summary table

| Service | Gradle dependency | Has cost? | Used for |
|---|---|---|---|
| Cloud Firestore | `firebase-firestore` | Yes (usage-based, free tier available) | Primary backend database |
| Firebase Cloud Messaging (FCM) | `firebase-messaging` | No (free) | Push notifications to devices |
| Cloud Storage for Firebase | `firebase-storage` | Yes (usage-based, free tier available) | Storing recorded audio clips |
| Firebase Analytics (Google Analytics for Firebase) | `firebase-analytics` | No (free) | Included via BOM; no explicit event/logging calls found in code — likely default/automatic collection only |

## Details

### Cloud Firestore
- **Dependency:** `com.google.firebase:firebase-firestore`
- **Cost:** Billed on the Blaze (pay-as-you-go) plan based on document reads/writes/deletes and storage; Firestore has a daily free quota but this app has no client-side caps on read/write volume (e.g. real-time listeners), so cost scales with usage/device count.
- **Where used:**
  - `DeviceIdentity.java` — creates/updates a `devices/{deviceId}` document with FCM token, display name, and muted-listener list.
  - `listen/NoiseListenerService.java` — writes `noiseEvents/{eventId}` documents (duration, sound type, audio URL) whenever a noise episode is detected; updates `listeners/{listenerId}` `active` flag when listening stops.
  - `listen/ListenFragment.java` — reads/writes `noiseEvents` and `listeners` collections; listens for the latest event in real time.
  - `subscribe/SubscriptionsFragment.java` — real-time listener on `listeners` (active listeners) and the current device's document (for muted listener IDs).
  - `users/UsersFragment.java` — real-time listener on the `devices` collection to show every device that has registered with the app.
  - Model classes `model/Listener.java`, `model/DeviceDoc.java`, `model/NoiseEvent.java` mirror Firestore documents (`listeners/*`, `devices/*`, `noiseEvents/*`).
  - `androidTest/FirestoreConnectivityTest.java` — instrumentation test verifying Firestore connectivity.
- **Purpose:** Firestore is the app's shared backend — it tracks registered devices, active "listeners" (noise monitors), and logged noise events, and drives real-time sync across devices (e.g., someone subscribing to another device's noise alerts).

### Firebase Cloud Messaging (FCM)
- **Dependency:** `com.google.firebase:firebase-messaging`
- **Cost:** Free — FCM has no usage charges.
- **Where used:**
  - `fcm/AnyNoiseMessagingService.java` — `FirebaseMessagingService` subclass; receives push notifications and displays them, and forwards new FCM tokens to `DeviceIdentity.updateFcmToken()`.
  - `DeviceIdentity.java` — fetches the FCM token via `FirebaseMessaging.getInstance().getToken()` and stores it in the device's Firestore doc.
  - Declared in `AndroidManifest.xml` as a service listening for `com.google.firebase.MESSAGING_EVENT`.
- **Purpose:** Delivers push alerts to a device (presumably triggered by a Cloud Functions backend, not present in this repo) when a subscribed/monitored noise event occurs.

### Cloud Storage for Firebase
- **Dependency:** `com.google.firebase:firebase-storage`
- **Cost:** Billed on the Blaze plan for stored bytes, downloads, and operations; has a free tier. Cost scales with number/size of recorded audio clips.
- **Where used:**
  - `listen/NoiseListenerService.java` (`reportNoiseEvent`) — when "record audio clip" is enabled for a listener, uploads the captured episode as a `.wav` file to `noiseClips/{deviceId}/{eventId}.wav`, then stores the resulting download URL on the corresponding `noiseEvents` Firestore document.
- **Purpose:** Stores optional audio recordings tied to detected noise events so they can be played back later (URL referenced from Firestore).

### Firebase Analytics
- **Dependency:** `com.google.firebase:firebase-analytics`
- **Cost:** Free.
- **Where used:** Not called explicitly anywhere in the codebase (no `FirebaseAnalytics.getInstance()` or `logEvent` calls found). It's pulled in as a dependency, which by default enables Google Analytics for Firebase's automatic event collection (screen views, app opens, etc.) tied to the Firebase project, but the app does not appear to log any custom events.
- **Purpose:** Currently unused beyond default automatic collection; could be removed if analytics data isn't being reviewed, or extended with explicit event logging if desired.

## Other notes
- No Google Maps, Play Billing, AdMob, Google Sign-In, or other Google Play Services libraries are present — only the four Firebase products above.
- `google-services.json` also lists an `appinvite_service` entry with no OAuth clients configured; there's no App Invite code in the app, so this appears to be an unused default from Firebase project setup.
- All Firebase costs are billed to whichever Google Cloud/Firebase billing account owns the `all-anynoise` project — check the Firebase console (Usage & billing) for current spend, since this repo doesn't define any client-side quotas or budget alerts.
