# AnyNoise

Android (Java) app that listens to a phone's microphone — meant for a device
that's plugged in and charging all the time — and, when sound stays above a
threshold for a configured duration, pushes a notification to everyone who's
registered to hear about it. Any installed copy of the app can act as a
listener, a subscriber, or both at once.

## How it fits together

```
Android app  --writes-->  Firestore (devices, listeners, noiseEvents)
                                 |
                                 | onDocumentCreated(noiseEvents/*)
                                 v
                     Cloud Function (functions/index.js)
                        |                          |
                        v                          v
                 FCM push to subscribers   POST to Apps Script webhook
                                                    |
                                                    v
                                          Google Sheet gets a new row
```

- **Registration / opt-out**: every install writes a `devices/{deviceId}`
  Firestore doc with its FCM token and a `mutedListenerIds` array. No
  login — `deviceId` is a UUID generated once and stored locally.
- **Listening**: tapping "Start Listening" creates/reuses a
  `listeners/{listenerId}` doc and starts a foreground service
  (`NoiseListenerService`) that samples the mic, and writes a
  `noiseEvents/{eventId}` doc once a sustained-noise episode ends.
- **Notifications**: a Cloud Function watches `noiseEvents` and fans out an
  FCM push to every device that hasn't muted that listener.
- **Sheets logging** (optional, enhanced feature): the same Cloud Function
  POSTs the event to a Google Apps Script Web App, which appends a row.

## What's code-complete vs. what you still need to do

Everything in `app/`, `functions/`, and `appsscript/` is implemented. Three
things need your own Google/Firebase account and can't be done from here:

1. **Create a Firebase project** and download `google-services.json` into
   `app/google-services.json` (it's gitignored — never commit it).
2. **Deploy the Cloud Function and Firestore/Storage rules** (needs
   `firebase login`).
3. **Deploy the Apps Script Web App** for Sheets logging — see
   `appsscript/SETUP.md`. Skippable; the app works without it.

### 1. Firebase project

- Go to the [Firebase console](https://console.firebase.google.com), create
  a project, then add an Android app with package name `com.anynoise.app`.
- Download the generated `google-services.json` into `app/`.
- Enable **Firestore**, **Cloud Storage**, and **Cloud Messaging** in the
  console (Storage is only needed if you plan to use the audio-clip feature).

### 2. Deploy backend

```bash
npm install -g firebase-tools   # if you don't have it
firebase login
cp .firebaserc.example .firebaserc   # then edit in your project ID
cd functions && npm install && cd ..

# optional: enable Sheets logging first — see appsscript/SETUP.md,
# then create functions/.env with SHEETS_WEBHOOK_URL=...

firebase deploy --only firestore:rules,storage:rules,functions
```

### 3. Build and run the Android app

Open the `AnyNoise/` root in Android Studio (it will generate the Gradle
wrapper automatically) — this repo doesn't include a `gradlew` since no
Gradle/Android SDK is available in the environment it was generated in, so
building from this CLI wasn't possible to verify. From Android Studio:

- File → Open → select this directory.
- Let Gradle sync (it needs `app/google-services.json` in place first).
- Run on a real device — `AudioRecord` and foreground-service microphone
  access don't work reliably on most emulators.

On first launch the app requests `RECORD_AUDIO` and (Android 13+)
`POST_NOTIFICATIONS`, then prompts to exempt itself from battery
optimization (needed since it's meant to run continuously while charging).

## Using it

- **Listen tab**: name your sound source (e.g. "Nursery"), pick a
  sensitivity and minimum sustained duration, optionally enable audio-clip
  capture, then **Start Listening**. The service keeps running (and
  restarts itself on reboot) until you stop it.
- **Notifications tab**: shows every active listener from every device.
  Toggle "Notify me" off for any source you want to opt out of.

## Noise-detection behavior worth knowing

`NoiseListenerService` (`app/src/main/java/com/anynoise/app/listen/`) tracks
peak sample amplitude per ~64ms chunk. An "episode" starts when amplitude
crosses the sensitivity threshold, and is reported (Firestore write → push →
optional Sheet row) once the sound has stopped for 1.5s (a short hangover to
bridge brief pauses) — provided it lasted at least your configured minimum
duration. Continuous unbroken noise is force-split into a new episode every
2 minutes so it still notifies instead of waiting indefinitely for silence.

## Known limitations

- No authentication: Firestore/Storage rules (`firestore.rules`,
  `storage.rules`) validate document shape but can't verify a write really
  came from the device it claims to be. Fine for a small trusted group;
  add Firebase Anonymous Auth if you need real per-device isolation.
- The Cloud Function fans out by scanning the whole `devices` collection
  per event (fine at small scale; revisit with an index/topic-based
  approach if you have thousands of registered devices).
- Sensitivity/threshold is a raw PCM amplitude, not a calibrated dB(A)
  reading — it'll behave differently across phone microphones.
