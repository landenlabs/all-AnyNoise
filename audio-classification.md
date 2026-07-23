# Audio classification

## Goal

Classify (tag) each detected noise episode with a coarse sound type, surface
that tag in the Google Sheet and the push notification, and — once tagging is
proven — let users opt in/out of notifications by classification (not just by
listener, as today).

This doc tracks progress toward that goal. It's a working log, not a spec —
update it as the plan changes.

## Decisions made

- **DSP rule-based tagging first, YAMNet/TFLite deferred.** A design from
  Gemini proposed both a DSP pipeline and an ML (YAMNet/TFLite) pipeline
  together. Adopted DSP-only for this pass: zero new dependencies, no model
  assets/APK size hit, ships the full column/notification plumbing
  immediately. ML classification is a distinct follow-up phase.
- **Tag computed once per finished episode**, not continuously per audio
  chunk. Matches the actual need (one tag per Sheet row / per notification);
  no live "guess" shown during recording.
- **Episode PCM is now always buffered for analysis**, decoupled from the
  existing "attach audio clip" checkbox. That checkbox now only controls
  whether the WAV clip gets *uploaded* to Storage — analysis runs regardless,
  so tagging doesn't silently stop working when a user doesn't want audio
  uploaded.
- Followed this codebase's existing conventions rather than the Gemini
  design's generic Android architecture: no `AudioCaptureManager` (there's
  already exactly one `AudioRecord` loop, in `NoiseListenerService`), no
  `ExecutorService`/`ViewModel`/`LiveData` (unused anywhere in this app; new
  code uses the existing `Thread` + `Handler(mainLooper)` + callback-interface
  pattern), plain-public-field POJOs (matches `Listener`/`NoiseEvent`/
  `DeviceDoc`, not getter/setter JavaBeans).

## What's done

**New files:**
- `app/src/main/java/com/landenlabs/allAnyNoise/model/PhysicalSoundType.java`
  — enum: `QUICK_CLICK`, `STEADY_HUM`, `LOUD_BANG`, `LOW_RUMBLE`, `UNKNOWN`.
- `app/src/main/java/com/landenlabs/allAnyNoise/model/AudioFeatures.java`
  — `rmsDb`, `peakAmplitude`, `zeroCrossingRate`, `spectralCentroidHz`,
  `durationMs`, `soundType`.
- `app/src/main/java/com/landenlabs/allAnyNoise/listen/DspAudioAnalyzer.java`
  — pure-Java, no Android dependency. Computes RMS, peak/attack timing,
  zero-crossing rate over the full episode buffer, and an averaged spectral
  centroid from a bounded number of Hann-windowed FFT frames (caps analysis
  cost regardless of episode length — episodes can run up to 120s). Applies
  a small rule engine to produce a `PhysicalSoundType`.
  - Fixed during development: the FFT frames were originally unwindowed
    (rectangular window), which leaks spectral energy across many bins for
    non-integer-cycle content and badly skews the centroid for low-frequency/
    tonal signals — exactly what `LOW_RUMBLE`/`STEADY_HUM` depend on. Added a
    Hann window before each FFT frame; verified with a standalone smoke test
    (synthetic 100Hz/1kHz tones, a click burst, an impulse, noise, silence,
    and an empty buffer) that all six non-trivial cases classify as expected
    and analysis runs in single-digit milliseconds.

**Changed files:**
- `model/NoiseEvent.java` — added `soundType` field.
- `listen/NoiseListenerService.java` — `appendClipAudio` renamed to
  `appendEpisodeAudio` and no longer gated by the record-clip toggle;
  `finishEpisode()` runs `DspAudioAnalyzer.analyze(...)` and threads the
  resulting tag through to the Firestore write (`soundType` field on the
  `noiseEvents` doc).
- `functions/index.js` — reads `soundType` off the event, maps it to a human
  label via `describeSoundType()`/`SOUND_TYPE_LABELS`, includes that label in
  the FCM notification body (and the raw tag in the data payload), and passes
  `soundType` through to the Sheets webhook JSON body.
- `appsscript/Code.gs` — `doPost` appends a 5th column, `soundType`.
- `appsscript/SETUP.md` — suggested header row updated to include
  `Sound Type`.

**Not yet deployed** (needs your Google account, same as other Sheets/Cloud
Function setup in this repo):
- `firebase deploy --only functions` to ship the updated `index.js`.
- Paste the updated `Code.gs` into the Sheet's bound Apps Script editor and
  re-deploy the Web App — it's container-bound, so this can't be done from
  here. Existing sheet rows won't have a 5th column; only new rows will.

## What's left to do

1. **Tune the DSP thresholds against real recordings.** Current thresholds
   (`CLICK_MAX_DURATION_MS`, `BANG_MIN_PEAK_AMPLITUDE`, `RUMBLE_MAX_CENTROID_HZ`,
   `HUM_MAX_ZCR`, etc., all in `DspAudioAnalyzer`) are an initial heuristic
   validated only against synthetic test signals, not real-world audio from
   the app's actual use cases (nursery, front door, etc.).
2. **No automated test coverage for `DspAudioAnalyzer`** in the repo itself —
   validation so far was a throwaway standalone smoke test outside the
   project. Worth adding a real JVM unit test (the class has no Android
   dependency, so it doesn't need `androidTest`).
3. **Surface the tag in the app UI** — not done yet. Candidates: the "Last
   sound recorded" line on the Listen tab, the History tab's last-row
   summary.
4. **YAMNet / TFLite ML classification** — deferred. Would add the
   `tensorflow-lite-task-audio` dependency, bundle/download the `.tflite`
   model, and add inference threading. Separate scoping/design pass before
   starting.
5. **Opt-in/out controls by classification** — the original end goal.
   Needs: a schema decision (e.g. `mutedSoundTypes` array on `devices/{id}`,
   parallel to today's `mutedListenerIds`), UI controls (likely on the
   Notifications/Subscriptions tab), and a Cloud Function change so
   `notifySubscribers` in `functions/index.js` also excludes devices that
   muted the event's `soundType`, not just its `listenerId`.
6. **Redeploy the Cloud Function and Apps Script** (see "Not yet deployed"
   above) before any of this is live end-to-end.
