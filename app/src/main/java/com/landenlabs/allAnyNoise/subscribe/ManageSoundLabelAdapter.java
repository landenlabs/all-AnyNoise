// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.subscribe;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
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

/**
 * Full-list, per-row rename/delete adapter for {@link ManageSoundLabelsActivity} - a
 * fuller-featured sibling of {@link SoundLabelAdapter} that also tracks a checkbox
 * selection set for bulk delete.
 */
public class ManageSoundLabelAdapter extends RecyclerView.Adapter<ManageSoundLabelAdapter.ViewHolder> {

    public interface OnMuteToggleListener {
        void onMuteToggled(String soundLabelId, boolean muted);
    }

    public interface OnEditRequestedListener {
        void onEditRequested(SoundLabel label);
    }

    public interface OnDeleteRequestedListener {
        void onDeleteRequested(SoundLabel label);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectedCount);
    }

    private final List<SoundLabel> labels = new ArrayList<>();
    private final Set<String> mutedLabelIds = new HashSet<>();
    private final Set<String> selectedLabelIds = new HashSet<>();

    private final OnMuteToggleListener muteListener;
    private final OnEditRequestedListener editListener;
    private final OnDeleteRequestedListener deleteListener;
    private final OnSelectionChangedListener selectionListener;

    public ManageSoundLabelAdapter(OnMuteToggleListener muteListener, OnEditRequestedListener editListener,
                                    OnDeleteRequestedListener deleteListener,
                                    OnSelectionChangedListener selectionListener) {
        this.muteListener = muteListener;
        this.editListener = editListener;
        this.deleteListener = deleteListener;
        this.selectionListener = selectionListener;
    }

    public void submit(List<SoundLabel> newLabels, Set<String> newMutedIds) {
        labels.clear();
        labels.addAll(newLabels);
        mutedLabelIds.clear();
        mutedLabelIds.addAll(newMutedIds);

        Set<String> liveIds = new HashSet<>();
        for (SoundLabel label : newLabels) {
            liveIds.add(label.id);
        }
        selectedLabelIds.retainAll(liveIds);

        notifyDataSetChanged();
        selectionListener.onSelectionChanged(selectedLabelIds.size());
    }

    public void selectAll(boolean select) {
        selectedLabelIds.clear();
        if (select) {
            for (SoundLabel label : labels) {
                selectedLabelIds.add(label.id);
            }
        }
        notifyDataSetChanged();
        selectionListener.onSelectionChanged(selectedLabelIds.size());
    }

    public List<SoundLabel> getSelectedLabels() {
        List<SoundLabel> selected = new ArrayList<>();
        for (SoundLabel label : labels) {
            if (selectedLabelIds.contains(label.id)) {
                selected.add(label);
            }
        }
        return selected;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_manage_sound_label, parent, false);
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
                muteListener.onMuteToggled(label.id, !isChecked));

        holder.select.setOnCheckedChangeListener(null);
        holder.select.setChecked(selectedLabelIds.contains(label.id));
        holder.select.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (isChecked) {
                selectedLabelIds.add(label.id);
            } else {
                selectedLabelIds.remove(label.id);
            }
            selectionListener.onSelectionChanged(selectedLabelIds.size());
        });

        holder.editButton.setOnClickListener(v -> editListener.onEditRequested(label));
        holder.deleteButton.setOnClickListener(v -> deleteListener.onDeleteRequested(label));
    }

    @Override
    public int getItemCount() {
        return labels.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final CheckBox select;
        final TextView name;
        final Switch notifySwitch;
        final Button editButton;
        final Button deleteButton;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            select = itemView.findViewById(R.id.cb_select);
            name = itemView.findViewById(R.id.tv_name);
            notifySwitch = itemView.findViewById(R.id.switch_notify);
            editButton = itemView.findViewById(R.id.btn_edit);
            deleteButton = itemView.findViewById(R.id.btn_delete);
        }
    }
}
