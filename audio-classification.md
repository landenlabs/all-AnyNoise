# Audio classification

## Goal

Classify (tag) each detected noise episode with a coarse sound type, surface
that tag in the Google Sheet and the push notification, and — once tagging is
proven — let users opt in/out of notifications by classification (not just by
listener, as today).

**Extended goal (added once the above shipped):** go beyond the 5 fixed
coarse tags — compute a per-episode acoustic "fingerprint" so similar-sounding
episodes (e.g. every time the furnace runs) can be grouped automatically, let
a human name a group the first time it's reviewed (e.g. "Furnace"), have
later matching episodes auto-recognized under that name, and let users opt
in/out of notifications per named group with the name shown in the push body.

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

## What's done (fingerprint / named-groups extension)

**New files:**
- `model/SoundLabel.java` — mirrors a `soundLabels/{labelId}` doc: `name`,
  `nameLower` (exact-match merge lookup), `physicalSoundType` (coarse
  namespace, narrows auto-match candidates), `centroid` (running-mean
  fingerprint), `sampleCount`, `createdByDeviceId`.
- `subscribe/SoundLabelAdapter.java` — per-label mute switches, exact
  parallel to `ListenerAdapter` (reuses `item_listener.xml`).
- `subscribe/UnnamedEventAdapter.java` + `res/layout/item_unnamed_event.xml`
  — review queue of recent `noiseEvents` with no `soundLabelId` yet, each
  with a "Name" button.
- `subscribe/SoundLabelManager.java` — the naming write path: looks up an
  existing label by `(nameLower, physicalSoundType)`; if found, folds this
  event's fingerprint into its centroid (transaction) and tags the event
  `labelSource: "manual"`; else creates a new label seeded with this
  fingerprint. The lookup query runs outside the transaction (Firestore's
  client SDK can't query inside one) — two devices naming the same brand-new
  sound at the same instant could create two labels instead of merging;
  accepted as a rare race at the same risk tier as the rest of this
  no-auth app.
- `res/layout/dialog_name_sound.xml` — naming dialog's text input.

**Changed files:**
- `listen/DspAudioAnalyzer.java` — `analyzeSpectrum()`'s existing FFT frame
  loop now also accumulates a 16-band, log-spaced (50 Hz–Nyquist), L2-normalized
  energy vector (`FINGERPRINT_BANDS`), reusing the FFT work already being done
  for the spectral centroid. Loudness-invariant by construction (normalized),
  so the same sound at a different volume still matches. Verified with a
  standalone smoke test (pure-Java, no Android deps, run outside the project
  like the original DSP smoke test): fixed length, same tone at half amplitude
  → cosine similarity ~1.0, distinct tones/noise → cosine similarity ~0.
- `model/AudioFeatures.java` — added `fingerprint` (`double[]`).
- `model/NoiseEvent.java` — added `fingerprint`, `soundLabelId`,
  `soundLabelName`, `labelSource`, `id` (`@Exclude`, set from the doc ID when
  read back — needed so the naming UI knows which doc to update).
- `model/DeviceDoc.java` — added `mutedSoundLabelIds`.
- `listen/NoiseListenerService.java` — `writeNoiseEventDoc` now also writes
  `fingerprint`.
- `DeviceIdentity.java` — added `setSoundLabelMuted` (parallel to
  `setListenerMuted`); new device docs seed `mutedSoundLabelIds: []`.
- `functions/index.js` — `onNoiseEventCreated` now calls `matchSoundLabel()`
  before fanning out: cosine-matches the event's fingerprint against
  `soundLabels` filtered to the same `physicalSoundType`, and on a match
  ≥ `LABEL_MATCH_THRESHOLD` (0.90, unvalidated against real recordings —
  same caveat as the DSP thresholds) tags the event `labelSource: "auto"`
  and folds the fingerprint into the label's centroid (transaction, so
  concurrent matches from multiple listener devices don't race). No match →
  event stays unlabeled, notification/Sheet fall back to the coarse
  `describeSoundType(soundType)` text as before. `notifySubscribers` now
  also excludes devices with the label in `mutedSoundLabelIds`, and prefers
  `soundLabelName` in the push body over the coarse label. `logToSheet` adds
  `soundLabelName`. Syntax-checked with `node -c`; cosine-similarity/merge
  math (mismatched-length, zero-vector, first-fold, running-mean-fold cases)
  verified with a standalone Node script outside the project.
- `subscribe/SubscriptionsFragment.java` — added two more sections/lists
  ("Named sounds" with mute switches, "Recent unnamed sounds" with the naming
  dialog) alongside the existing per-listener list; wrapped the whole
  fragment in a `ScrollView` since it no longer fits one screen with 3 lists.
  The unnamed-events query fetches a small recent window and filters
  `soundLabelId == null` client-side (same over-fetch-and-filter pattern
  `ListenFragment` already uses) rather than an equality Firestore filter,
  because `==null` wouldn't match events logged before this field existed —
  it only matches an explicit `null`, not a missing field.
- `appsscript/Code.gs` / `SETUP.md` — Sheet row/header gets a 6th column,
  `Sound Label`.
- `history/HistoryAdapter.java`, `history/HistoryFragment.java`,
  `item_history_row.xml`, `item_history_header.xml` — added the matching 6th
  column to the on-device History table.
- `firestore.rules` — new `soundLabels` collection rules; `noiseEvents` update
  rule now allows changing exactly `soundLabelId`/`soundLabelName`/
  `labelSource` (previously fully append-only) via
  `diff(resource.data).affectedKeys().hasOnly([...])`, so naming an event
  from the app doesn't get rejected.

**Not yet deployed** (needs your Google account, same as before):
- `firebase deploy --only firestore:rules,functions` — ships the new
  `soundLabels` rules, the loosened `noiseEvents` update rule, and the
  fingerprint-matching Cloud Function. Nothing above works end-to-end without
  this.
- Paste the updated `Code.gs` into the Sheet's bound Apps Script and
  redeploy the Web App (container-bound, same limitation as before).
  Existing sheet rows won't gain a 6th column; only new rows will.

## What's left to do

1. **Tune both sets of DSP thresholds against real recordings.** The original
   coarse-tag thresholds (`CLICK_MAX_DURATION_MS`, `BANG_MIN_PEAK_AMPLITUDE`,
   etc.) and the new `LABEL_MATCH_THRESHOLD` (cosine similarity cutoff for
   "same sound") are both validated only against synthetic signals so far.
   Suspect `LABEL_MATCH_THRESHOLD` in particular will need real-world tuning —
   e.g. a furnace and a washing machine may be closer in this 16-band shape
   than expected.
2. **No automated test coverage in the repo itself for either** the DSP
   classifier or the fingerprint/matching math — validation so far is
   throwaway standalone smoke tests outside the project (Java for the
   fingerprint math, Node for the Cloud Function's cosine-similarity/merge
   math). Worth adding real JVM/Node unit tests.
3. **Surface the coarse tag in the main app UI** — still not done (original
   item 3; unrelated to the naming UI, which lives on the Subscriptions tab).
   Candidates unchanged: the "Last sound recorded" line on the Listen tab,
   the History tab's last-row summary.
4. **No backfill for events logged before this shipped.** They have no
   `fingerprint`, so they can never be auto-matched or offered in the naming
   queue — a soft cutover, same precedent as the original `soundType` column
   addition (old Sheet rows never gained a 5th column either).
5. **No "suggested match" in the naming dialog.** Right now, typing a name
   that doesn't exactly match an existing label's `nameLower` always creates
   a new label — nothing nudges the user toward reusing "Furnace" instead of
   accidentally creating "furnace running" as a separate group. Worth adding
   once real-world label fragmentation turns out to be a problem.
6. **YAMNet / TFLite ML classification** — still deferred. If the DSP
   fingerprint's grouping accuracy proves too weak in practice (see item 1),
   the natural upgrade is swapping in a YAMNet embedding as the fingerprint —
   the matching/centroid-learning code downstream doesn't care whether the
   vector is DSP- or ML-derived, so that's a contained swap, not a rework.
7. **Redeploy** (see "Not yet deployed" above) before any of this is live
   end-to-end.

## Confirmed working / found issue (2026-07-25 on-device pass)

Rebuilt and installed on one physical device, ran through the naming flow
several times, then inspected `functions:log` and the raw Firestore data
directly (via `gcloud auth application-default login` + a throwaway
`firebase-admin` script — no crashes/errors in either the device logcat or
the Cloud Function log across the whole session).

**Found:** every real detected episode had `soundType` but a completely
missing `fingerprint` field — not zeros, just absent. Root cause: the
*listening* device ("monitor audio 1" in the data) is a different physical
phone from the one that got rebuilt here, so it's still running a pre-
fingerprint build. Direct symptom: both labels created during testing
("Dryer", "Dryer Done") have empty (`length 0`) centroids, and every one of
their member events has `labelSource: "manual"` — i.e. the exact-name-merge
path worked, but real acoustic auto-matching has never had fingerprint data
to run on yet. **Action needed: rebuild + install on the actual listening
device too**, not just the naming/subscriber one.

## What's done (Sounds-tab review-queue UX, 2026-07-25)

Five additions to the "Recent unnamed sounds" section on the Subscriptions
("Sounds") tab, all client-side/display-layer except the `dismissed` field:

- **Play button** (`UnnamedEventAdapter`) — streams `noiseEvent.audioUrl` via
  `MediaPlayer` if present (i.e. that listener had "record audio clip" on);
  disabled otherwise. Only one clip plays at a time; toggling another stops
  the current one first.
- **Swipe to dismiss** (`SubscriptionsFragment.attachSwipeToDismiss`) — an
  `ItemTouchHelper` on the unnamed-events list; swiping sets a new
  `NoiseEvent.dismissed` field to `true` (optimistic local removal, real
  write follows). Group header rows aren't swipeable.
- **"Clear all"** — batched `dismissed: true` write over every currently
  unnamed event.
- **Client-side grouping** (`FingerprintGrouper`) — greedy single-linkage
  clustering of the fetched unnamed-events window by cosine similarity of
  `fingerprint`, purely for display; assigns "Unknown group #N (M sounds)"
  headers. Not persisted anywhere - recomputed from scratch every time the
  screen loads, so numbering isn't a stable identity. Singleton "groups"
  (no similar match found) render as plain rows, same as before, with no
  header.
- **Name the whole group** — reuses the existing single-event naming path
  unchanged: the header's "Name group" button opens the same dialog, then
  calls `SoundLabelManager.nameEvent` once per member event, *sequentially*
  (not in parallel) so the first call's label create/merge is visible to the
  next one.

`firestore.rules`'s `noiseEvents` update exception widened from
`['soundLabelId', 'soundLabelName', 'labelSource']` to also allow
`'dismissed'`. Deployed via `firebase deploy --only firestore:rules`.
Verified with a real `./gradlew compileDebugJavaWithJavac` + `installDebug`
this time (gradlew/Android SDK/a connected device all became available
mid-session) rather than the earlier phases' standalone smoke tests - this
is the first part of this feature actually compiler-checked, not just
hand-verified.

**Still open:** grouping/matching are only as good as the fingerprints
feeding them - the "confirmed working / found issue" gap above (listening
device needs the rebuild too) applies here as well; until then the grouping
UI won't cluster anything meaningfully since all fingerprints on real
episodes are currently missing from that device.
