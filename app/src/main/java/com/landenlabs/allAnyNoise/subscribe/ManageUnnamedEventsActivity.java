// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.subscribe;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.NoiseEvent;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.firestore.QueryDocumentSnapshot;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.List;

/**
 * Full-page management of the unnamed-sounds review queue. Mirrors the section that
 * used to live inline on {@link SubscriptionsFragment} (which now only shows a short
 * preview of this list); fetches a much larger window than that preview since this
 * page exists specifically to handle a long queue.
 */
public class ManageUnnamedEventsActivity extends AppCompatActivity {

    private static final int UNNAMED_QUERY_LIMIT = 200;

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private Button btnClearAll;
    private UnnamedEventAdapter adapter;

    private ListenerRegistration unnamedEventsRegistration;

    private final List<NoiseEvent> latestUnnamedEvents = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_unnamed_events);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        recyclerView = findViewById(R.id.rv_unnamed_events);
        tvEmpty = findViewById(R.id.tv_unnamed_empty);
        btnClearAll = findViewById(R.id.btn_clear_all_unnamed);

        adapter = new UnnamedEventAdapter(this::showNameDialog, this::dismissEvent);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);
        attachSwipeToDismiss();

        btnClearAll.setOnClickListener(v -> clearAll());

        unnamedEventsRegistration = FirebaseFirestore.getInstance().collection("noiseEvents")
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
                    render(unnamed);
                });
    }

    private void attachSwipeToDismiss() {
        ItemTouchHelper touchHelper = new ItemTouchHelper(new ItemTouchHelper.SimpleCallback(0,
                ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public int getMovementFlags(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                if (!adapter.isEventRow(viewHolder.getBindingAdapterPosition())) {
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
                adapter.onItemDismissedBySwipe(viewHolder.getBindingAdapterPosition());
            }
        });
        touchHelper.attachToRecyclerView(recyclerView);
    }

    private void dismissEvent(NoiseEvent event) {
        FirebaseFirestore.getInstance().collection("noiseEvents").document(event.id)
                .update("dismissed", true)
                .addOnFailureListener(e -> Toast.makeText(this,
                        getString(R.string.subscriptions_name_failed, e.getMessage()), Toast.LENGTH_LONG).show());
    }

    private void clearAll() {
        if (latestUnnamedEvents.isEmpty()) {
            return;
        }
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        WriteBatch batch = db.batch();
        for (NoiseEvent event : latestUnnamedEvents) {
            batch.update(db.collection("noiseEvents").document(event.id), "dismissed", true);
        }
        batch.commit().addOnFailureListener(e -> Toast.makeText(this,
                getString(R.string.subscriptions_name_failed, e.getMessage()), Toast.LENGTH_LONG).show());
    }

    private void render(List<NoiseEvent> unnamed) {
        latestUnnamedEvents.clear();
        latestUnnamedEvents.addAll(unnamed);

        List<FingerprintGrouper.Group> groups = FingerprintGrouper.group(unnamed);
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
        adapter.submit(rows);

        boolean empty = unnamed.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
        btnClearAll.setEnabled(!empty);
    }

    private void showNameDialog(List<NoiseEvent> events) {
        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_name_sound, null, false);
        EditText input = dialogView.findViewById(R.id.et_sound_name);

        new AlertDialog.Builder(this)
                .setTitle(R.string.subscriptions_name_dialog_title)
                .setView(dialogView)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        nameEventsSequentially(events, 0, input.getText().toString()))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void nameEventsSequentially(List<NoiseEvent> events, int index, String name) {
        if (index >= events.size()) {
            Toast.makeText(this, R.string.subscriptions_name_saved, Toast.LENGTH_SHORT).show();
            return;
        }
        NoiseEvent event = events.get(index);
        SoundLabelManager.nameEvent(this, event.id, event.soundType, event.fingerprint, name,
                new SoundLabelManager.OnNameSavedListener() {
                    @Override
                    public void onSaved() {
                        nameEventsSequentially(events, index + 1, name);
                    }

                    @Override
                    public void onFailed(@NonNull Exception e) {
                        Toast.makeText(ManageUnnamedEventsActivity.this,
                                getString(R.string.subscriptions_name_failed, e.getMessage()), Toast.LENGTH_LONG).show();
                        nameEventsSequentially(events, index + 1, name);
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
        adapter.stopPlayback();
        if (unnamedEventsRegistration != null) {
            unnamedEventsRegistration.remove();
        }
    }
}
