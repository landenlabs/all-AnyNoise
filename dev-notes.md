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
  `AnyNoiseApp`, `model/` POJOs (`Listener`, `DeviceDoc`, `NoiseEvent`,
  `SoundLabel`).
- `listen/DspAudioAnalyzer` — DSP rule-based coarse tagging (`soundType`)
  plus a spectral "fingerprint" for grouping similar-sounding episodes;
  `subscribe/SoundLabelAdapter` + `UnnamedEventAdapter` + `SoundLabelManager`
  — SubscriptionsFragment sections to name a reviewed sound and mute
  notifications per named group, auto-recognized on later matches by the
  Cloud Function. See `audio-classification.md` for the full design/status.
- `battery/BatteryStatus`, `battery/BatteryReportWorker`,
  `battery/BatteryReportScheduler` — a WorkManager periodic job (default 6h,
  configurable in Settings via a preset spinner: 1/3/6/12/24h) that reads
  battery level/health/temperature off the sticky `ACTION_BATTERY_CHANGED`
  broadcast (no permission needed) and merges it into `devices/{deviceId}`
  (`batteryLevelPct`, `batteryHealth`, `batteryTempC`, `batteryUpdatedAt`).
  `UsersFragment`/`UserAdapter` show the latest snapshot per device (no
  history/graphing — that's out of scope, unlike noise events). WorkManager
  is free to batch/defer this (Doze, App Standby); an approximate schedule
  is fine.
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
   - `firebase deploy --only firestore:rules,storage:rules,functions` —
     required for sound-label naming/matching to work: `firestore.rules` now
     has a `soundLabels` collection and a loosened `noiseEvents` update rule,
     and `functions/index.js` does the fingerprint matching.

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
   - Sound naming: trigger a noise event, open the Subscriptions tab, name
     it under "Recent unnamed sounds", confirm it moves to "Named sounds"
     with a mute switch and the Sheet row (if wired up) shows it in the
     Sound Label column; trigger a similar sound again and confirm the
     Cloud Function auto-tags the new event with the same name (check the
     `noiseEvents` doc's `labelSource: "auto"`) and the push notification
     body shows the name instead of the coarse sound-type text.
   - Battery reporting: confirm `devices/{deviceId}` picks up
     `batteryLevelPct`/`batteryHealth`/`batteryTempC`/`batteryUpdatedAt`
     shortly after first launch and that the Users tab shows it. Don't wait
     6h to retest — either temporarily lower the Settings interval to 1h,
     or trigger the worker on demand from `adb shell` (e.g.
     `adb shell cmd jobscheduler run -f com.landenlabs.all_anynoise <jobId>`,
     or just `am force-stop`/relaunch after changing the interval, since
     `AnyNoiseApp.onCreate()` re-arms the schedule on every process start).
     Change the interval in Settings and confirm via
     `WorkManager.getInstance(context).getWorkInfosForUniqueWork("battery_report")`
     (or `adb shell dumpsys jobscheduler`) that it re-enqueues.

5. **Nice-to-haves not built** (out of original scope, worth considering
   later): real per-device auth (Firebase Anonymous Auth) instead of
   trust-by-UUID; replacing the whole-`devices`-collection scan in the
   Cloud Function with an index/topic-based fan-out if the user base grows
   large; calibrated dB(A) sensitivity instead of raw PCM amplitude
   (currently varies by phone mic); real launcher icon art (current one is
   a simple placeholder vector mic glyph).
