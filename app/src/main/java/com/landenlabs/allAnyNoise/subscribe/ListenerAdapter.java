// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.subscribe;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.Listener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class ListenerAdapter extends RecyclerView.Adapter<ListenerAdapter.ViewHolder> {

    public interface OnMuteToggleListener {
        void onMuteToggled(String listenerId, boolean muted);
    }

    private final List<Listener> listeners = new ArrayList<>();
    private final Set<String> mutedListenerIds = new HashSet<>();
    private final OnMuteToggleListener callback;

    public ListenerAdapter(OnMuteToggleListener callback) {
        this.callback = callback;
    }

    public void submit(List<Listener> newListeners, Set<String> newMutedIds) {
        listeners.clear();
        listeners.addAll(newListeners);
        mutedListenerIds.clear();
        mutedListenerIds.addAll(newMutedIds);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_listener, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Listener listener = listeners.get(position);
        holder.name.setText(listener.name);

        boolean subscribed = !mutedListenerIds.contains(listener.id);
        holder.notifySwitch.setOnCheckedChangeListener(null);
        holder.notifySwitch.setChecked(subscribed);
        holder.notifySwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                callback.onMuteToggled(listener.id, !isChecked));
    }

    @Override
    public int getItemCount() {
        return listeners.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView name;
        final Switch notifySwitch;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.tv_name);
            notifySwitch = itemView.findViewById(R.id.switch_notify);
        }
    }
}
