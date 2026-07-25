// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.subscribe;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.landenlabs.allAnyNoise.DeviceIdentity;
import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.Listener;
import com.landenlabs.allAnyNoise.model.NoiseEvent;
import com.landenlabs.allAnyNoise.model.SoundLabel;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sound page: quick previews of named/unnamed sounds with full-page rename/delete/bulk
 * management delegated to {@link ManageSoundLabelsActivity} and
 * {@link ManageUnnamedEventsActivity} (via the "Manage" buttons), since either list can
 * grow far past what's comfortable to render inline on this scrolling summary page.
 */
public class SubscriptionsFragment extends Fragment {

    // Recent-events window to scan for unnamed sounds; a small over-fetch
    // filtered client-side, same pattern ListenFragment uses for its
    // "last event for this listener" lookup - avoids relying on Firestore's
    // == null query semantics, which don't match events missing the field
    // entirely (e.g. events logged before this feature shipped).
    private static final int UNNAMED_QUERY_LIMIT = 30;

    // Both lists on this page are just a taste of the full set - the "Manage" buttons
    // open the full lists (with rename/delete/bulk actions) in their own activities.
    private static final int PREVIEW_LIMIT = 3;

    private RecyclerView recyclerViewListeners;
    private TextView tvEmpty;
    private ListenerAdapter listenerAdapter;

    private RecyclerView recyclerViewLabels;
    private TextView tvLabelsEmpty;
    private Button btnManageLabels;
    private SoundLabelAdapter soundLabelAdapter;

    private RecyclerView recyclerViewUnnamed;
    private TextView tvUnnamedEmpty;
    private Button btnManageUnnamed;
    private UnnamedEventAdapter unnamedEventAdapter;

    private ListenerRegistration listenersRegistration;
    private ListenerRegistration deviceRegistration;
    private ListenerRegistration soundLabelsRegistration;
    private ListenerRegistration unnamedEventsRegistration;

    private final List<Listener> latestListeners = new ArrayList<>();
    private final Set<String> latestMutedListenerIds = new HashSet<>();
    private final List<SoundLabel> latestLabels = new ArrayList<>();
    private final Set<String> latestMutedLabelIds = new HashSet<>();
    private final List<NoiseEvent> latestUnnamedEvents = new ArrayList<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_subscriptions, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerViewListeners = view.findViewById(R.id.rv_listeners);
        tvEmpty = view.findViewById(R.id.tv_empty);
        recyclerViewLabels = view.findViewById(R.id.rv_sound_labels);
        tvLabelsEmpty = view.findViewById(R.id.tv_labels_empty);
        btnManageLabels = view.findViewById(R.id.btn_manage_labels);
        recyclerViewUnnamed = view.findViewById(R.id.rv_unnamed_events);
        tvUnnamedEmpty = view.findViewById(R.id.tv_unnamed_empty);
        btnManageUnnamed = view.findViewById(R.id.btn_manage_unnamed);

        listenerAdapter = new ListenerAdapter((listenerId, muted) ->
                DeviceIdentity.setListenerMuted(requireContext(), listenerId, muted));
        recyclerViewListeners.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerViewListeners.setAdapter(listenerAdapter);

        soundLabelAdapter = new SoundLabelAdapter((soundLabelId, muted) ->
                DeviceIdentity.setSoundLabelMuted(requireContext(), soundLabelId, muted));
        recyclerViewLabels.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerViewLabels.setAdapter(soundLabelAdapter);

        unnamedEventAdapter = new UnnamedEventAdapter(this::showNameDialog, this::dismissEvent);
        recyclerViewUnnamed.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerViewUnnamed.setAdapter(unnamedEventAdapter);
        attachSwipeToDismiss();

        btnManageLabels.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ManageSoundLabelsActivity.class)));
        btnManageUnnamed.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ManageUnnamedEventsActivity.class)));

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        String deviceId = DeviceIdentity.getDeviceId(requireContext());

        listenersRegistration = db.collection("listeners")
                .whereEqualTo("active", true)
                .addSnapshotListener((snapshot, error) -> {
                    if (snapshot == null) {
                        return;
                    }
                    latestListeners.clear();
                    for (QueryDocumentSnapshot doc : snapshot) {
                        Listener listener = doc.toObject(Listener.class);
                        listener.id = doc.getId();
                        latestListeners.add(listener);
                    }
                    renderListeners();
                });

        deviceRegistration = db.collection("devices").document(deviceId)
                .addSnapshotListener((snapshot, error) -> {
                    latestMutedListenerIds.clear();
                    latestMutedLabelIds.clear();
                    if (snapshot != null && snapshot.exists()) {
                        List<String> mutedListeners = (List<String>) snapshot.get("mutedListenerIds");
                        if (mutedListeners != null) {
                            latestMutedListenerIds.addAll(mutedListeners);
                        }
                        List<String> mutedLabels = (List<String>) snapshot.get("mutedSoundLabelIds");
                        if (mutedLabels != null) {
                            latestMutedLabelIds.addAll(mutedLabels);
                        }
                    }
                    renderListeners();
                    renderLabels();
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
                    renderLabels();
                });

        unnamedEventsRegistration = db.collection("noiseEvents")
                .orderBy("startedAt", Query.Direction.DESCENDING)
                .limit(UNNAMED_QUERY_LIMIT)
                .addSnapshotListener((snapshot, error) -> {
                    if (snapshot == null) {
                        return;
                    }
                    List<NoiseEvent> unnamed = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshot) {
                        NoiseEvent event = doc.toObject(NoiseEvent.class);
                        if (event.soundLabelId == null && !Boolean.TRUE.equals(event.dismissed)) {
                            event.id = doc.getId();
                            unnamed.add(event);
                        }
                    }
                    renderUnnamed(unnamed);
                });
    }

    private void attachSwipeToDismiss() {
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                if (!unnamedEventAdapter.isEventRow(viewHolder.getBindingAdapterPosition())) {
                    return 0;
                }
                return super.getMovementFlags(recyclerView, viewHolder);
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                                   @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                unnamedEventAdapter.onItemDismissedBySwipe(viewHolder.getBindingAdapterPosition());
            }
        });
        touchHelper.attachToRecyclerView(recyclerViewUnnamed);
    }

    private void dismissEvent(NoiseEvent event) {
        FirebaseFirestore.getInstance().collection("noiseEvents").document(event.id)
                .update("dismissed", true)
                .addOnFailureListener(e -> {
                    if (isAdded()) {
                        Toast.makeText(requireContext(),
                                getString(R.string.subscriptions_name_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void renderListeners() {
        listenerAdapter.submit(latestListeners, latestMutedListenerIds);
        boolean empty = latestListeners.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerViewListeners.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    private void renderLabels() {
        boolean empty = latestLabels.isEmpty();
        List<SoundLabel> preview = empty ? latestLabels : latestLabels.subList(0, Math.min(PREVIEW_LIMIT, latestLabels.size()));
        soundLabelAdapter.submit(preview, latestMutedLabelIds);
        tvLabelsEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerViewLabels.setVisibility(empty ? View.GONE : View.VISIBLE);
        btnManageLabels.setEnabled(!empty);
        btnManageLabels.setText(getString(R.string.subscriptions_manage_button, latestLabels.size()));
    }

    private void renderUnnamed(List<NoiseEvent> unnamed) {
        latestUnnamedEvents.clear();
        latestUnnamedEvents.addAll(unnamed);

        List<NoiseEvent> preview = unnamed.isEmpty() ? unnamed : unnamed.subList(0, Math.min(PREVIEW_LIMIT, unnamed.size()));
        List<FingerprintGrouper.Group> groups = FingerprintGrouper.group(preview);
        List<Object> rows = new ArrayList<>();
        int groupNumber = 1;
        for (FingerprintGrouper.Group group : groups) {
            if (group.events.size() > 1) {
                String title = getString(R.string.subscriptions_unknown_group_title, groupNumber++, group.events.size());
                rows.add(new UnnamedEventAdapter.GroupHeader(title, group.events));
                rows.addAll(group.events);
            } else {
                rows.addAll(group.events);
            }
        }
        unnamedEventAdapter.submit(rows);

        boolean empty = unnamed.isEmpty();
        tvUnnamedEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerViewUnnamed.setVisibility(empty ? View.GONE : View.VISIBLE);
        btnManageUnnamed.setEnabled(!empty);
        btnManageUnnamed.setText(getString(R.string.subscriptions_manage_button, unnamed.size()));
    }

    private void showNameDialog(List<NoiseEvent> events) {
        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_name_sound, null, false);
        EditText input = dialogView.findViewById(R.id.et_sound_name);

        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.subscriptions_name_dialog_title)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        nameEventsSequentially(events, 0, input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /**
     * Names every event in a group one at a time rather than in parallel, so
     * the first call's label-create (or merge) is visible to the next call -
     * otherwise concurrent creates for a brand-new name could each miss
     * seeing the others and create duplicate labels instead of merging.
     */
    private void nameEventsSequentially(List<NoiseEvent> events, int index, String name) {
        if (index >= events.size()) {
            if (isAdded()) {
                Toast.makeText(requireContext(), R.string.subscriptions_name_saved, Toast.LENGTH_SHORT).show();
            }
            return;
        }
        NoiseEvent event = events.get(index);
        SoundLabelManager.nameEvent(requireContext(), event.id, event.soundType, event.fingerprint, name,
                new SoundLabelManager.OnNameSavedListener() {
                    @Override
                    public void onSaved() {
                        nameEventsSequentially(events, index + 1, name);
                    }

                    @Override
                    public void onFailed(@NonNull Exception e) {
                        if (isAdded()) {
                            Toast.makeText(requireContext(),
                                    getString(R.string.subscriptions_name_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                        }
                        nameEventsSequentially(events, index + 1, name);
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        unnamedEventAdapter.stopPlayback();
        if (listenersRegistration != null) {
            listenersRegistration.remove();
        }
        if (deviceRegistration != null) {
            deviceRegistration.remove();
        }
        if (soundLabelsRegistration != null) {
            soundLabelsRegistration.remove();
        }
        if (unnamedEventsRegistration != null) {
            unnamedEventsRegistration.remove();
        }
    }
}
