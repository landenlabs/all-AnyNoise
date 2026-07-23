# Dev notes

## What this is

Android (Java) app that listens to a phone's mic — meant for a device that's
plugged in and charging continuously — and pushes a notification to
registered users when sound stays above a threshold for a configured
duration. Any install can act as a listener, a subscriber, or both. See
`README.md` for the full architecture diagram and setup walkthrough.

## Architecture decisions made

- **Notifications**: Firebase (Firestore + Cloud Functions + FCM), no
  custom server to host.
- **Sheets logging**: Cloud Function POSTs to a Google Apps Script Web App
  (keeps credentials out of the APK).
- **Device roles**: any install can listen, subscribe, or both — no fixed
  "hub" device.
- **No login/auth system** (explicit scope decision) — `deviceId` is a
  locally-generated UUID. Firestore/Storage rules can only validate
  document shape, not caller identity. Documented as a limitation in
  `firestore.rules` and `README.md`.

## What's done (code-complete)

**Android app** (`app/`, package `com.anynoise.app`, minSdk 26):
- `MainActivity` — bottom nav (Listen / Notifications), runtime permission
  requests (`RECORD_AUDIO`, `POST_NOTIFICATIONS`), battery-optimization
  exemption prompt, registers device on launch via `DeviceIdentity`.
- `listen/NoiseListenerService` — foreground service (type `microphone`),
  `AudioRecord`-based peak-amplitude sampling, sustained-episode state
  machine (edge-triggered on episode end with a 1.5s hangover, force-split
  every 2 min for unbroken noise), optional PCM→WAV clip capture, uploads
  to Firebase Storage, writes `noiseEvents` doc.
- `listen/ListenFragment` — configure name/sensitivity/min-duration/clip
  toggle, start/stop, live level meter via `NoiseListenerService.LevelListener`.
- `listen/WavEncoder` — minimal RIFF/WAV header writer for raw PCM.
- `subscribe/SubscriptionsFragment` + `ListenerAdapter` — live Firestore
  listener list with per-listener mute/unmute switches.
- `fcm/AnyNoiseMessagingService` — displays incoming pushes, refreshes token.
- `DeviceIdentity`, `Prefs`, `NotificationHelper`, `BootReceiver`,
  `AnyNoiseApp`, `model/` POJOs (`Listener`, `DeviceDoc`, `NoiseEvent`).
- Full resources: layouts, strings, vector icons (`ic_mic`,
  `ic_notifications`), adaptive launcher icon (placeholder art — swap for a
  real logo whenever convenient), manifest with all required permissions.
- Gradle project files (`settings.gradle`, root/app `build.gradle`,
  `gradle.properties`, `proguard-rules.pro`, `.gitignore`). AGP 8.5.0,
  Firebase BoM 33.1.2.

**Backend**:
- `functions/index.js` — `onNoiseEventCreated` Firestore trigger: fans out
  FCM to devices that haven't muted the event's listener, cleans up stale
  tokens, best-effort POSTs to the Sheets webhook if `SHEETS_WEBHOOK_URL` is
  set. Syntax-checked with `node -c` (no Firebase project here to deploy
  against).
- `firestore.rules`, `storage.rules`, `firebase.json`,
  `firestore.indexes.json`, `.firebaserc.example`.
- `appsscript/Code.gs` + `appsscript/SETUP.md` — Sheets webhook and
  deployment walkthrough.

**Verification done in this environment**: cross-checked every `R.id`,
`R.string`, `R.drawable` reference in Java against defined resources (all
resolve), confirmed Java package declarations match directory structure,
brace-balance check across all `.java` files, `node -c` on `index.js`. No
Android SDK/Gradle available here, so `assembleDebug` was never run.

## What's left to do (needs your accounts / a real device)

1. **Firebase project setup**
   - Create project in Firebase console, add Android app with package
     `com.anynoise.app`, download `google-services.json` into `app/`
     (gitignored, never commit it).
   - Enable Firestore, Cloud Storage, Cloud Messaging.

2. **Deploy backend**
   - `firebase login`, copy `.firebaserc.example` → `.firebaserc` with your
     project ID.
   - `cd functions && npm install`.
   - `firebase deploy --only firestore:rules,storage:rules,functions`.

3. **Sheets logging (optional)** — follow `appsscript/SETUP.md`: deploy the
   Apps Script as a Web App, put the URL in `functions/.env` as
   `SHEETS_WEBHOOK_URL=...`, redeploy functions. Skippable — the app and
   push notifications work fully without it.

4. **Build and test on a real device**
   - Open the repo root in Android Studio (no `gradlew` is checked in — no
     Gradle/Android SDK was available in this build environment to generate
     or verify the wrapper; Android Studio generates it automatically on
     first open).
   - Gradle sync needs `app/google-services.json` in place first.
   - Run on a real device, not an emulator — `AudioRecord` +
     foreground-service microphone access is unreliable on most emulators.
   - Test plan: register two physical devices, start listening on one, mute
     that listener on the other, confirm no notification; unmute, make
     sustained noise near the listening device, confirm push notification
     arrives with correct duration; if audio-clip capture is enabled,
     confirm the Storage URL in the `noiseEvents` doc plays back correctly;
     if Sheets is wired up, confirm a row appears.
   - Reboot the listening device while active and confirm
     `BootReceiver` restarts the service.

5. **Nice-to-haves not built** (out of original scope, worth considering
   later): real per-device auth (Firebase Anonymous Auth) instead of
   trust-by-UUID; replacing the whole-`devices`-collection scan in the
   Cloud Function with an index/topic-based fan-out if the user base grows
   large; calibrated dB(A) sensitivity instead of raw PCM amplitude
   (currently varies by phone mic); real launcher icon art (current one is
   a simple placeholder vector mic glyph).
