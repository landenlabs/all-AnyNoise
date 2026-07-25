// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.users;

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

import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.DeviceDoc;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.QueryDocumentSnapshot;

import java.util.ArrayList;
import java.util.List;

/** Lists every device that has registered with the app (devices/{deviceId} docs). */
public class UsersFragment extends Fragment {

    private RecyclerView recyclerView;
    private TextView tvEmpty;
    private UserAdapter adapter;

    private ListenerRegistration devicesRegistration;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_users, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        recyclerView = view.findViewById(R.id.rv_users);
        tvEmpty = view.findViewById(R.id.tv_empty);

        adapter = new UserAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        devicesRegistration = FirebaseFirestore.getInstance().collection("devices")
                .addSnapshotListener((snapshot, error) -> {
                    if (snapshot == null) {
                        return;
                    }
                    List<DeviceDoc> devices = new ArrayList<>();
                    for (QueryDocumentSnapshot doc : snapshot) {
                        DeviceDoc device = doc.toObject(DeviceDoc.class);
                        device.id = doc.getId();
                        devices.add(device);
                    }
                    adapter.submit(devices);
                    boolean empty = devices.isEmpty();
                    tvEmpty.setVisibility(empty ? View.VISIBLE : View.GONE);
                    recyclerView.setVisibility(empty ? View.GONE : View.VISIBLE);
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (devicesRegistration != null) {
            devicesRegistration.remove();
        }
    }
}
