package com.landenlabs.allAnyNoise.subscribe;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.SoundLabel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Per-label mute switches, exact parallel to ListenerAdapter but keyed on soundLabels instead of listeners. */
public class SoundLabelAdapter extends RecyclerView.Adapter<SoundLabelAdapter.ViewHolder> {

    public interface OnMuteToggleListener {
        void onMuteToggled(String soundLabelId, boolean muted);
    }

    private final List<SoundLabel> labels = new ArrayList<>();
    private final Set<String> mutedLabelIds = new HashSet<>();
    private final OnMuteToggleListener callback;

    public SoundLabelAdapter(OnMuteToggleListener callback) {
        this.callback = callback;
    }

    public void submit(List<SoundLabel> newLabels, Set<String> newMutedIds) {
        labels.clear();
        labels.addAll(newLabels);
        mutedLabelIds.clear();
        mutedLabelIds.addAll(newMutedIds);
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
        SoundLabel label = labels.get(position);
        holder.name.setText(label.name);

        boolean subscribed = !mutedLabelIds.contains(label.id);
        holder.notifySwitch.setOnCheckedChangeListener(null);
        holder.notifySwitch.setChecked(subscribed);
        holder.notifySwitch.setOnCheckedChangeListener((buttonView, isChecked) ->
                callback.onMuteToggled(label.id, !isChecked));
    }

    @Override
    public int getItemCount() {
        return labels.size();
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
