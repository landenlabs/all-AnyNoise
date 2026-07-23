package com.landenlabs.allAnyNoise.subscribe;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.landenlabs.allAnyNoise.DeviceIdentity;
import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.Listener;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class SubscriptionsFragment extends Fragment {

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private ListenerAdapter adapter;

    private ListenerRegistration listenersRegistration;
    private ListenerRegistration deviceRegistration;

    private final List<Listener> latestListeners = new ArrayList<>();
    private final Set<String> latestMutedIds = new HashSet<>();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_subscriptions, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.rv_listeners);
        tvEmpty = view.findViewById(R.id.tv_empty);

        adapter = new ListenerAdapter((listenerId, muted) ->
                DeviceIdentity.setListenerMuted(requireContext(), listenerId, muted));
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

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
                    renderList();
                });

        deviceRegistration = db.collection("devices").document(deviceId)
                .addSnapshotListener((snapshot, error) -> {
                    latestMutedIds.clear();
                    if (snapshot != null && snapshot.exists()) {
                        List<String> muted = (List<String>) snapshot.get("mutedListenerIds");
                        if (muted != null) {
                            latestMutedIds.addAll(muted);
                        }
                    }
                    renderList();
                });
    }

    private void renderList() {
        adapter.submit(latestListeners, latestMutedIds);
        boolean empty = latestListeners.isEmpty();
        tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
        recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (listenersRegistration != null) {
            listenersRegistration.remove();
        }
        if (deviceRegistration != null) {
            deviceRegistration.remove();
        }
    }
}
