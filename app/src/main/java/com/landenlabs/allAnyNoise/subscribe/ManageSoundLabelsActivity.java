// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.subscribe;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.landenlabs.allAnyNoise.DeviceIdentity;
import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.SoundLabel;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Full-page management of every named sound: rename, delete (single or multi-select),
 * and the mute toggle also available as a preview on {@link SubscriptionsFragment}.
 */
public class ManageSoundLabelsActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private CheckBox cbSelectAll;
    private Button btnDeleteSelected;
    private ManageSoundLabelAdapter adapter;

    private ListenerRegistration deviceRegistration;
    private ListenerRegistration soundLabelsRegistration;

    private final List<SoundLabel> latestLabels = new ArrayList<>();
    private final Set<String> latestMutedLabelIds = new HashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_sound_labels);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        recyclerView = findViewById(R.id.rv_sound_labels);
        tvEmpty = findViewById(R.id.tv_empty);
        cbSelectAll = findViewById(R.id.cb_select_all);
        btnDeleteSelected = findViewById(R.id.btn_delete_selected);

        adapter = new ManageSoundLabelAdapter(
                (soundLabelId, muted) -> DeviceIdentity.setSoundLabelMuted(this, soundLabelId, muted),
                this::showRenameDialog,
                this::confirmDeleteSingle,
                this::onSelectionChanged);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        cbSelectAll.setOnClickListener(v -> adapter.selectAll(cbSelectAll.isChecked()));
        btnDeleteSelected.setOnClickListener(v -> confirmDeleteSelected());

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String deviceId = DeviceIdentity.getDeviceId(this);

        deviceRegistration = db.collection("devices").document(deviceId)
                .addSnapshotListener((snapshot, error) -> {
                    latestMutedLabelIds.clear();
                    if (snapshot != null && snapshot.exists()) {
                        @SuppressWarnings("unchecked")
                        List<String> mutedLabels = (List<String>) snapshot.get("mutedSoundLabelIds");
                        if (mutedLabels != null) {
                            latestMutedLabelIds.addAll(mutedLabels);
                        }
                    }
                    render();
                });

        soundLabelsRegistration = db.collection("soundLabels")
                .addSnapshotListener((snapshot, error) -> {
                    if (snapshot == null) {
                        return;
                    }
                    latestLabels.clear();
                    for (QueryDocumentSnapshot doc : snapshot) {
                        SoundLabel label = doc.toObject(SoundLabel.class);
                        label.id = doc.getId();
                        latestLabels.add(label);
                    }
                    render();
                });
    }

    private void render() {
        adapter.submit(latestLabels, latestMutedLabelIds);
        boolean empty = latestLabels.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        cbSelectAll.setEnabled(!empty);
    }

    private void onSelectionChanged(int selectedCount) {
        btnDeleteSelected.setEnabled(selectedCount > 0);
        btnDeleteSelected.setText(selectedCount > 0
                ? getString(R.string.manage_labels_delete_selected, selectedCount)
                : getString(R.string.manage_labels_delete_button));
        cbSelectAll.setChecked(selectedCount > 0 && selectedCount == latestLabels.size());
    }

    private void showRenameDialog(SoundLabel label) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_name_sound, null, false);
        EditText input = dialogView.findViewById(R.id.et_sound_name);
        input.setText(label.name);

        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_labels_rename_dialog_title)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        SoundLabelManager.renameLabel(label.id, input.getText().toString(),
                                new SoundLabelManager.OnNameSavedListener() {
                                    @Override
                                    public void onSaved() {
                                        Toast.makeText(ManageSoundLabelsActivity.this,
                                                R.string.manage_labels_rename_saved, Toast.LENGTH_SHORT).show();
                                    }

                                    @Override
                                    public void onFailed(@NonNull Exception e) {
                                        Toast.makeText(ManageSoundLabelsActivity.this,
                                                getString(R.string.manage_labels_rename_failed, e.getMessage()),
                                                Toast.LENGTH_LONG).show();
                                    }
                                }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteSingle(SoundLabel label) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_labels_delete_confirm_title)
                .setMessage(getString(R.string.manage_labels_delete_confirm_message, label.name))
                .setPositiveButton(R.string.manage_labels_delete_button, (dialog, which) ->
                        deleteLabelsSequentially(Collections.singletonList(label), 0))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void confirmDeleteSelected() {
        List<SoundLabel> selected = adapter.getSelectedLabels();
        if (selected.isEmpty()) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.manage_labels_delete_confirm_title)
                .setMessage(getString(R.string.manage_labels_delete_confirm_message_multi, selected.size()))
                .setPositiveButton(R.string.manage_labels_delete_button, (dialog, which) ->
                        deleteLabelsSequentially(selected, 0))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Deletes one label at a time rather than in parallel batches, mirroring
     * SubscriptionsFragment's nameEventsSequentially - keeps each label's
     * noiseEvents-revert-then-delete batch independent and easy to retry/report on.
     */
    private void deleteLabelsSequentially(List<SoundLabel> labels, int index) {
        if (index >= labels.size()) {
            return;
        }
        SoundLabel label = labels.get(index);
        SoundLabelManager.deleteLabel(this, label.id, new SoundLabelManager.OnDeleteFinishedListener() {
            @Override
            public void onFinished() {
                deleteLabelsSequentially(labels, index + 1);
            }

            @Override
            public void onFailed(@NonNull Exception e) {
                Toast.makeText(ManageSoundLabelsActivity.this,
                        getString(R.string.manage_labels_delete_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                deleteLabelsSequentially(labels, index + 1);
            }
        });
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (deviceRegistration != null) {
            deviceRegistration.remove();
        }
        if (soundLabelsRegistration != null) {
            soundLabelsRegistration.remove();
        }
    }
}
