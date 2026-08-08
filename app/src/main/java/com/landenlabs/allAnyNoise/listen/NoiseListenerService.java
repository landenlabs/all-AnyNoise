// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.listen;

import android.Manifest;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ServiceCompat;

import com.landenlabs.allAnyNoise.DeviceIdentity;
import com.landenlabs.allAnyNoise.NotificationHelper;
import com.landenlabs.allAnyNoise.Prefs;
import com.landenlabs.allAnyNoise.model.AudioFeatures;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageMetadata;
import com.google.firebase.storage.StorageReference;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Foreground service that continuously samples the microphone and reports a
 * "noise event" to Firestore whenever sound stays above a configured
 * threshold for at least the configured minimum duration.
 *
 * Episodes are edge-triggered on the *end* of a sustained-sound period
 * (with a short hangover to bridge brief dips) so the reported duration and
 * optional audio clip are complete. A long-running, unbroken sound is force
 * split into successive episodes every {@link #MAX_EPISODE_MS} so it still
 * produces timely notifications instead of never firing.
 */
public class NoiseListenerService extends Service {

    private static final String TAG = "NoiseListenerService";

    public static final String ACTION_START = "com.anynoise.app.action.START";
    public static final String ACTION_STOP = "com.anynoise.app.action.STOP";

    public static final String EXTRA_LISTENER_ID = "listenerId";
    public static final String EXTRA_LISTENER_NAME = "listenerName";
    public static final String EXTRA_THRESHOLD_AMPLITUDE = "thresholdAmplitude";
    public static final String EXTRA_MIN_DURATION_MS = "minDurationMs";
    public static final String EXTRA_RECORD_AUDIO_CLIP = "recordAudioClip";

    private static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final int CHUNK_SAMPLES = 1024;

    private static final long HANGOVER_MS = 1500;
    private static final long MAX_EPISODE_MS = 120_000;
    private static final int MAX_EPISODE_AUDIO_BYTES = 6 * 1024 * 1024;

    // A raw reading must sit on the far side of the deadband below and hold
    // there for the sensor's debounce window before an on/off transition is
    // reported - this rejects both sensor jitter around the threshold and
    // momentary flicker (e.g. a hallway motion light or a single door bump).
    private static final long LIGHT_DEBOUNCE_MS = 2000;
    private static final long VIBRATION_DEBOUNCE_MS = 1000;
    private static final float HYSTERESIS_RATIO = 0.5f;

    // Live strip-chart samples are throttled to this interval so the full-page
    // live view doesn't redraw at the raw ~15Hz audio-chunk rate; light/vibration
    // arrive at SENSOR_DELAY_NORMAL (~5Hz) already and aren't throttled further.
    private static final long AUDIO_SAMPLE_INTERVAL_MS = 150;

    /** Callback for the Listen UI to show a live level meter. */
    public interface LevelListener {
        void onLevelUpdate(int level0to100, boolean episodeActive);
    }

    private static volatile LevelListener levelListener;

    public static void setLevelListener(@Nullable LevelListener listener) {
        levelListener = listener;
    }

    /** Callback for the full-page live view's three strip charts. */
    public interface LiveSampleListener {
        void onAudioSample(long timestampMs, float amplitude);
        void onLightSample(long timestampMs, float lux);
        void onVibrationSample(long timestampMs, float magnitude);
    }

    private static volatile LiveSampleListener liveSampleListener;

    public static void setLiveSampleListener(@Nullable LiveSampleListener listener) {
        liveSampleListener = listener;
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private Thread recordingThread;
    private volatile boolean running = false;

    private String listenerId;
    private String listenerName;
    private int thresholdAmplitude;
    private long minDurationMs;
    private boolean recordAudioClip;

    private SensorManager sensorManager;
    private SensorEventListener lightSensorListener;
    private SensorEventListener vibrationSensorListener;
    private int lightThresholdLux;
    private int vibrationThreshold;
    private EdgeDetector lightEdge;
    private EdgeDetector vibrationEdge;
    private long lastAudioSampleAt;

    @Override
    public void onCreate() {
        super.onCreate();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_STOP.equals(intent.getAction())) {
            stopListening();
            return START_NOT_STICKY;
        }

        listenerId = intent.getStringExtra(EXTRA_LISTENER_ID);
        listenerName = intent.getStringExtra(EXTRA_LISTENER_NAME);
        thresholdAmplitude = intent.getIntExtra(EXTRA_THRESHOLD_AMPLITUDE, 4000);
        minDurationMs = intent.getLongExtra(EXTRA_MIN_DURATION_MS, 3000);
        recordAudioClip = intent.getBooleanExtra(EXTRA_RECORD_AUDIO_CLIP, false);

        ServiceCompat.startForeground(this, 1,
                NotificationHelper.buildListeningNotification(this, listenerName),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);

        if (!running) {
            startRecordingThread();
        }
        if (sensorManager == null) {
            lightThresholdLux = Prefs.getLightSensitivityThresholdLux(this);
            vibrationThreshold = Prefs.getVibrationSensitivityThreshold(this);
            registerAmbientSensors();
        }

        return START_STICKY;
    }

    private void stopListening() {
        running = false;
        if (recordingThread != null) {
            recordingThread.interrupt();
            recordingThread = null;
        }
        unregisterAmbientSensors();
        Prefs.setListenerActive(this, false);
        if (listenerId != null) {
            FirebaseFirestore.getInstance().collection("listeners")
                    .document(listenerId)
                    .update("active", false);
        }
        stopForeground(true);
        stopSelf();
    }

    private void registerAmbientSensors() {
        sensorManager = getSystemService(SensorManager.class);
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager unavailable; light/vibration detection disabled.");
            return;
        }

        Sensor lightSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (lightSensor == null) {
            Log.w(TAG, "No ambient light sensor on this device; light on/off detection disabled.");
        } else {
            lightEdge = new EdgeDetector(LIGHT_DEBOUNCE_MS, HYSTERESIS_RATIO);
            lightSensorListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    long now = System.currentTimeMillis();
                    Boolean fired = lightEdge.evaluate(event.values[0], lightThresholdLux, now);
                    if (fired != null) {
                        reportBinaryEvent(fired ? "LIGHT_ON" : "LIGHT_OFF", event.values[0]);
                    }
                    publishLightSample(now, event.values[0]);
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sensorManager.registerListener(lightSensorListener, lightSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }

        // TYPE_LINEAR_ACCELERATION already has gravity subtracted out, so its
        // magnitude is a direct read of shake/vibration energy with no extra
        // gravity-estimation filtering needed.
        Sensor vibrationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
        if (vibrationSensor == null) {
            Log.w(TAG, "No linear-acceleration sensor on this device; vibration on/off detection disabled.");
        } else {
            vibrationEdge = new EdgeDetector(VIBRATION_DEBOUNCE_MS, HYSTERESIS_RATIO);
            vibrationSensorListener = new SensorEventListener() {
                @Override
                public void onSensorChanged(SensorEvent event) {
                    long now = System.currentTimeMillis();
                    float magnitude = vectorMagnitude(event.values);
                    Boolean fired = vibrationEdge.evaluate(magnitude, vibrationThreshold, now);
                    if (fired != null) {
                        reportBinaryEvent(fired ? "VIBRATION_ON" : "VIBRATION_OFF", magnitude);
                    }
                    publishVibrationSample(now, magnitude);
                }

                @Override
                public void onAccuracyChanged(Sensor sensor, int accuracy) {
                }
            };
            sensorManager.registerListener(vibrationSensorListener, vibrationSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void unregisterAmbientSensors() {
        if (sensorManager != null) {
            if (lightSensorListener != null) {
                sensorManager.unregisterListener(lightSensorListener);
            }
            if (vibrationSensorListener != null) {
                sensorManager.unregisterListener(vibrationSensorListener);
            }
        }
        sensorManager = null;
        lightSensorListener = null;
        vibrationSensorListener = null;
        lightEdge = null;
        vibrationEdge = null;
    }

    private static float vectorMagnitude(float[] values) {
        float sumSquares = 0f;
        for (float v : values) {
            sumSquares += v * v;
        }
        return (float) Math.sqrt(sumSquares);
    }

    private void reportBinaryEvent(String soundType, float sensorValue) {
        Map<String, Object> data = new HashMap<>();
        data.put("listenerId", listenerId);
        data.put("listenerName", listenerName);
        // firestore.rules requires this key on every noiseEvents create; 0 renders
        // as a blank cell in the Sheet (Code.gs treats it as falsy), so duration
        // still reads as "skipped" for these binary on/off events.
        data.put("durationSec", 0.0);
        data.put("soundType", soundType);
        // The raw lux/vibration-magnitude reading that tripped this transition -
        // lets the History graph plot it against the configured threshold.
        data.put("sensorValue", (double) sensorValue);
        data.put("startedAt", FieldValue.serverTimestamp());

        FirebaseFirestore.getInstance().collection("noiseEvents").document().set(data)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to write " + soundType + " event", e));
    }

    /**
     * Shared on/off edge detector for ambient sensors (light, vibration, ...):
     * a raw reading must clear a hysteresis deadband around the threshold and
     * hold there for debounceMs before a state transition commits, so sensor
     * jitter and brief flicker never fire a false event.
     */
    private static class EdgeDetector {
        private final long debounceMs;
        private final float hysteresisRatio;
        private Boolean state;
        private boolean pendingState;
        private long pendingSince;

        EdgeDetector(long debounceMs, float hysteresisRatio) {
            this.debounceMs = debounceMs;
            this.hysteresisRatio = hysteresisRatio;
        }

        /** Returns the newly committed state (true=on, false=off) if a transition just fired, else null. */
        Boolean evaluate(float value, float threshold, long now) {
            boolean rawOn = value > threshold;
            boolean rawOff = value < threshold * hysteresisRatio;
            if (!rawOn && !rawOff) {
                pendingSince = 0;
                return null;
            }
            boolean candidate = rawOn;

            if (state == null) {
                state = candidate;
                pendingSince = 0;
                return null;
            }
            if (candidate == state) {
                pendingSince = 0;
                return null;
            }
            if (pendingSince == 0 || pendingState != candidate) {
                pendingState = candidate;
                pendingSince = now;
                return null;
            }
            if (now - pendingSince >= debounceMs) {
                state = candidate;
                pendingSince = 0;
                return candidate;
            }
            return null;
        }
    }

    private void startRecordingThread() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "RECORD_AUDIO permission not granted; cannot listen.");
            stopSelf();
            return;
        }

        running = true;
        recordingThread = new Thread(this::recordLoop, "NoiseListenerService-Record");
        recordingThread.start();
    }

    private void recordLoop() {
        int minBufferBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferBytes <= 0) {
            Log.e(TAG, "Unable to determine AudioRecord buffer size on this device.");
            return;
        }
        int bufferBytes = Math.max(minBufferBytes, CHUNK_SAMPLES * 2 * 4);

        AudioRecord audioRecord;
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    CHANNEL_CONFIG, AUDIO_FORMAT, bufferBytes);
        } catch (SecurityException e) {
            Log.e(TAG, "AudioRecord init failed", e);
            return;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord failed to initialize.");
            audioRecord.release();
            return;
        }

        short[] buffer = new short[CHUNK_SAMPLES];
        ByteArrayOutputStream episodeAudio = new ByteArrayOutputStream();

        long episodeStartAt = 0;
        long lastLoudAt = 0;
        boolean hasMetMinDuration = false;

        audioRecord.startRecording();
        try {
            while (running) {
                int read = audioRecord.read(buffer, 0, buffer.length);
                if (read <= 0) {
                    continue;
                }
                long amplitude = computePeakAmplitude(buffer, read);
                long now = System.currentTimeMillis();
                boolean loud = amplitude > thresholdAmplitude;
                boolean episodeActive = episodeStartAt != 0;

                if (loud) {
                    lastLoudAt = now;
                    if (episodeStartAt == 0) {
                        episodeStartAt = now;
                        hasMetMinDuration = false;
                        episodeAudio.reset();
                    }
                    appendEpisodeAudio(episodeAudio, buffer, read);

                    long elapsed = now - episodeStartAt;
                    if (!hasMetMinDuration && elapsed >= minDurationMs) {
                        hasMetMinDuration = true;
                    }
                    if (elapsed >= MAX_EPISODE_MS) {
                        finishEpisode(hasMetMinDuration, elapsed, episodeAudio);
                        episodeStartAt = now;
                        hasMetMinDuration = false;
                        episodeAudio.reset();
                    }
                    episodeActive = true;
                } else if (episodeStartAt != 0) {
                    appendEpisodeAudio(episodeAudio, buffer, read);
                    if (now - lastLoudAt > HANGOVER_MS) {
                        long elapsed = now - episodeStartAt;
                        finishEpisode(hasMetMinDuration, elapsed, episodeAudio);
                        episodeStartAt = 0;
                        hasMetMinDuration = false;
                        episodeAudio.reset();
                        episodeActive = false;
                    }
                }

                publishLevel(amplitude, episodeActive);
                if (now - lastAudioSampleAt >= AUDIO_SAMPLE_INTERVAL_MS) {
                    lastAudioSampleAt = now;
                    publishAudioSample(now, amplitude);
                }
            }
        } finally {
            audioRecord.stop();
            audioRecord.release();
        }
    }

    private void appendEpisodeAudio(ByteArrayOutputStream out, short[] buffer, int samples) {
        if (out.size() >= MAX_EPISODE_AUDIO_BYTES) {
            return;
        }
        for (int i = 0; i < samples; i++) {
            short s = buffer[i];
            out.write(s & 0xFF);
            out.write((s >> 8) & 0xFF);
        }
    }

    private long computePeakAmplitude(short[] buffer, int samples) {
        long peak = 0;
        for (int i = 0; i < samples; i++) {
            long abs = Math.abs((int) buffer[i]);
            if (abs > peak) {
                peak = abs;
            }
        }
        return peak;
    }

    private void publishLevel(long amplitude, boolean episodeActive) {
        LevelListener listener = levelListener;
        if (listener == null) {
            return;
        }
        int level = (int) Math.min(100, (amplitude * 100L) / Math.max(1, thresholdAmplitude * 2));
        mainHandler.post(() -> listener.onLevelUpdate(level, episodeActive));
    }

    private void publishAudioSample(long timestampMs, long amplitude) {
        LiveSampleListener listener = liveSampleListener;
        if (listener == null) {
            return;
        }
        mainHandler.post(() -> listener.onAudioSample(timestampMs, amplitude));
    }

    private void publishLightSample(long timestampMs, float lux) {
        LiveSampleListener listener = liveSampleListener;
        if (listener == null) {
            return;
        }
        mainHandler.post(() -> listener.onLightSample(timestampMs, lux));
    }

    private void publishVibrationSample(long timestampMs, float magnitude) {
        LiveSampleListener listener = liveSampleListener;
        if (listener == null) {
            return;
        }
        mainHandler.post(() -> listener.onVibrationSample(timestampMs, magnitude));
    }

    private void finishEpisode(boolean metMinDuration, long elapsedMs, ByteArrayOutputStream episodeAudio) {
        if (!metMinDuration) {
            return;
        }
        double durationSec = elapsedMs / 1000.0;
        byte[] episodePcm = episodeAudio.toByteArray();
        AudioFeatures features = DspAudioAnalyzer.analyze(episodePcm, SAMPLE_RATE);
        byte[] pcmForUpload = recordAudioClip ? episodePcm : null;
        reportNoiseEvent(durationSec, features, pcmForUpload);
    }

    private void reportNoiseEvent(double durationSec, AudioFeatures features, @Nullable byte[] pcm) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String eventId = db.collection("noiseEvents").document().getId();

        if (pcm != null && pcm.length > 0) {
            byte[] wav = WavEncoder.encode(pcm, SAMPLE_RATE, 1, 16);
            String deviceId = DeviceIdentity.getDeviceId(this);
            StorageReference ref = FirebaseStorage.getInstance()
                    .getReference("noiseClips/" + deviceId + "/" + eventId + ".wav");
            StorageMetadata metadata = new StorageMetadata.Builder()
                    .setContentType("audio/wav")
                    .build();
            ref.putBytes(wav, metadata)
                    .continueWithTask(task -> ref.getDownloadUrl())
                    .addOnSuccessListener(uri ->
                            writeNoiseEventDoc(eventId, durationSec, features, uri.toString()))
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Audio clip upload failed; logging event without audio.", e);
                        writeNoiseEventDoc(eventId, durationSec, features, null);
                    });
        } else {
            writeNoiseEventDoc(eventId, durationSec, features, null);
        }
    }

    private void writeNoiseEventDoc(String eventId, double durationSec, AudioFeatures features,
                                     @Nullable String audioUrl) {
        Map<String, Object> data = new HashMap<>();
        data.put("listenerId", listenerId);
        data.put("listenerName", listenerName);
        data.put("durationSec", durationSec);
        data.put("audioUrl", audioUrl);
        data.put("soundType", features.soundType.name());
        data.put("fingerprint", toDoubleList(features.fingerprint));
        data.put("startedAt", FieldValue.serverTimestamp());

        FirebaseFirestore.getInstance().collection("noiseEvents").document(eventId).set(data)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to write noise event", e));
    }

    private static List<Double> toDoubleList(double[] values) {
        List<Double> list = new ArrayList<>(values.length);
        for (double value : values) {
            list.add(value);
        }
        return list;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        running = false;
        if (recordingThread != null) {
            recordingThread.interrupt();
        }
        unregisterAmbientSensors();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
