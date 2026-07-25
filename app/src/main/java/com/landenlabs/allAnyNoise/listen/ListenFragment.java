// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.listen;

import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.landenlabs.allAnyNoise.DeviceIdentity;
import com.landenlabs.allAnyNoise.Prefs;
import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.NoiseEvent;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class ListenFragment extends Fragment implements NoiseListenerService.LevelListener {

    private static final int MAX_THRESHOLD_AMPLITUDE = 8000;
    private static final int MIN_THRESHOLD_AMPLITUDE = 800;
    private static final int RECENT_EVENTS_LIMIT = 25;
    private static final double TEST_EVENT_DURATION_SEC = 1.0;

    private EditText etListenerName;
    private SeekBar seekSensitivity;
    private EditText etMinDuration;
    private CheckBox cbRecordAudio;
    private Button btnStartStop;
    private Button btnTestNotification;
    private TextView tvStatus;
    private ProgressBar pbLevel;
    private TextView tvLastEvent;

    private String lastTriggeredAt;
    private ListenerRegistration lastEventRegistration;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_listen, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        etListenerName = view.findViewById(R.id.et_listener_name);
        seekSensitivity = view.findViewById(R.id.seek_sensitivity);
        etMinDuration = view.findViewById(R.id.et_min_duration);
        cbRecordAudio = view.findViewById(R.id.cb_record_audio);
        btnStartStop = view.findViewById(R.id.btn_start_stop);
        btnTestNotification = view.findViewById(R.id.btn_test_notification);
        tvStatus = view.findViewById(R.id.tv_status);
        pbLevel = view.findViewById(R.id.pb_level);
        tvLastEvent = view.findViewById(R.id.tv_last_event);

        restoreSavedConfig();

        boolean active = Prefs.isListenerActive(requireContext());
        updateStartStopUi(active);

        btnStartStop.setOnClickListener(v -> {
            if (Prefs.isListenerActive(requireContext())) {
                stopListening();
            } else {
                startListening();
            }
        });

        btnTestNotification.setOnClickListener(v -> sendTestNotification());

        lastEventRegistration = FirebaseFirestore.getInstance().collection("noiseEvents")
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(RECENT_EVENTS_LIMIT)
                .addSnapshotListener((snapshot, error) -> {
                    if (snapshot == null) {
                        return;
                    }
                    String listenerId = Prefs.getListenerId(requireContext());
                    for (QueryDocumentSnapshot doc : snapshot) {
                        NoiseEvent event = doc.toObject(NoiseEvent.class);
                        if (listenerId != null && listenerId.equals(event.listenerId)) {
                            showLastEvent(event);
                            return;
                        }
                    }
                    tvLastEvent.setText(R.string.listen_last_event_none);
                });
    }

    /**
     * Writes a real noiseEvents doc so the existing Cloud Function
     * (onNoiseEventCreated) fans it out end-to-end: FCM push to subscribers
     * and a row appended to the Sheets webhook - the same path a real
     * detected episode takes, not just a locally-drawn notification.
     */
    private void sendTestNotification() {
        if (!NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
            Toast.makeText(requireContext(), R.string.listen_test_notification_disabled, Toast.LENGTH_LONG).show();
        }

        String listenerId = Prefs.getListenerId(requireContext());
        if (TextUtils.isEmpty(listenerId)) {
            listenerId = "test-" + DeviceIdentity.getDeviceId(requireContext());
        }
        String listenerName = etListenerName.getText().toString().trim();
        if (TextUtils.isEmpty(listenerName)) {
            listenerName = getString(R.string.listen_test_notification_listener_name);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("listenerId", listenerId);
        data.put("listenerName", listenerName);
        data.put("durationSec", TEST_EVENT_DURATION_SEC);
        data.put("startedAt", FieldValue.serverTimestamp());

        FirebaseFirestore.getInstance().collection("noiseEvents").document().set(data)
                .addOnSuccessListener(unused -> Toast.makeText(requireContext(),
                        R.string.listen_test_notification_sent, Toast.LENGTH_SHORT).show())
                .addOnFailureListener(e -> Toast.makeText(requireContext(),
                        getString(R.string.listen_test_notification_failed, e.getMessage()),
                        Toast.LENGTH_LONG).show());
    }

    private void showLastEvent(NoiseEvent event) {
        String time = event.startedAt != null
                ? new SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(event.startedAt)
                : "?";
        tvLastEvent.setText(getString(R.string.listen_last_event_value, time, event.durationSec));
    }

    private void restoreSavedConfig() {
        String savedName = Prefs.getListenerName(requireContext());
        if (!TextUtils.isEmpty(savedName)) {
            etListenerName.setText(savedName);
        }

        int savedThreshold = Prefs.getThresholdAmplitude(requireContext());
        int seekValue = Math.round(
                (MAX_THRESHOLD_AMPLITUDE - savedThreshold) * 9f
                        / (MAX_THRESHOLD_AMPLITUDE - MIN_THRESHOLD_AMPLITUDE));
        seekSensitivity.setProgress(Math.max(0, Math.min(9, seekValue)));

        long savedDurationMs = Prefs.getMinDurationMs(requireContext());
        etMinDuration.setText(String.valueOf(savedDurationMs / 1000));

        cbRecordAudio.setChecked(Prefs.getRecordAudioClip(requireContext()));
    }

    private void startListening() {
        String name = etListenerName.getText().toString().trim();
        if (TextUtils.isEmpty(name)) {
            etListenerName.setError(getString(R.string.listen_name_hint));
            return;
        }

        int thresholdAmplitude = thresholdFromSeekBar(seekSensitivity.getProgress());
        long minDurationMs = parseDurationSeconds() * 1000L;
        boolean recordAudioClip = cbRecordAudio.isChecked();

        String listenerId = Prefs.getListenerId(requireContext());
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String ownerDeviceId = DeviceIdentity.getDeviceId(requireContext());

        if (TextUtils.isEmpty(listenerId)) {
            listenerId = db.collection("listeners").document().getId();
            Map<String, Object> data = new HashMap<>();
            data.put("name", name);
            data.put("ownerDeviceId", ownerDeviceId);
            data.put("active", true);
            data.put("thresholdAmplitude", thresholdAmplitude);
            data.put("minDurationMs", minDurationMs);
            data.put("createdAt", FieldValue.serverTimestamp());
            db.collection("listeners").document(listenerId).set(data);
        } else {
            Map<String, Object> data = new HashMap<>();
            data.put("name", name);
            data.put("active", true);
            data.put("thresholdAmplitude", thresholdAmplitude);
            data.put("minDurationMs", minDurationMs);
            db.collection("listeners").document(listenerId).update(data);
        }

        Prefs.saveListenerConfig(requireContext(), true, listenerId, name, thresholdAmplitude,
                minDurationMs, recordAudioClip);

        android.content.Intent intent = new android.content.Intent(requireContext(), NoiseListenerService.class);
        intent.setAction(NoiseListenerService.ACTION_START);
        intent.putExtra(NoiseListenerService.EXTRA_LISTENER_ID, listenerId);
        intent.putExtra(NoiseListenerService.EXTRA_LISTENER_NAME, name);
        intent.putExtra(NoiseListenerService.EXTRA_THRESHOLD_AMPLITUDE, thresholdAmplitude);
        intent.putExtra(NoiseListenerService.EXTRA_MIN_DURATION_MS, minDurationMs);
        intent.putExtra(NoiseListenerService.EXTRA_RECORD_AUDIO_CLIP, recordAudioClip);
        ContextCompat.startForegroundService(requireContext(), intent);

        updateStartStopUi(true);
    }

    private void stopListening() {
        android.content.Intent intent = new android.content.Intent(requireContext(), NoiseListenerService.class);
        intent.setAction(NoiseListenerService.ACTION_STOP);
        requireContext().startService(intent);

        updateStartStopUi(false);
    }

    private int thresholdFromSeekBar(int progress) {
        return MAX_THRESHOLD_AMPLITUDE
                - Math.round(progress * (MAX_THRESHOLD_AMPLITUDE - MIN_THRESHOLD_AMPLITUDE) / 9f);
    }

    private int parseDurationSeconds() {
        try {
            int seconds = Integer.parseInt(etMinDuration.getText().toString().trim());
            return Math.max(1, seconds);
        } catch (NumberFormatException e) {
            return 3;
        }
    }

    private void updateStartStopUi(boolean active) {
        btnStartStop.setText(active ? R.string.listen_stop : R.string.listen_start);
        etListenerName.setEnabled(!active);
        seekSensitivity.setEnabled(!active);
        etMinDuration.setEnabled(!active);
        cbRecordAudio.setEnabled(!active);

        if (!active) {
            tvStatus.setText(R.string.listen_status_idle);
            pbLevel.setProgress(0);
        } else if (lastTriggeredAt != null) {
            tvStatus.setText(getString(R.string.listen_status_active, lastTriggeredAt));
        } else {
            tvStatus.setText(R.string.listen_status_never_triggered);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        NoiseListenerService.setLevelListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        NoiseListenerService.setLevelListener(null);
    }

    @Override
    public void onLevelUpdate(int level0to100, boolean episodeActive) {
        pbLevel.setProgress(level0to100);
        if (episodeActive) {
            lastTriggeredAt = new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
            tvStatus.setText(getString(R.string.listen_status_active, lastTriggeredAt));
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (lastEventRegistration != null) {
            lastEventRegistration.remove();
        }
    }
}
