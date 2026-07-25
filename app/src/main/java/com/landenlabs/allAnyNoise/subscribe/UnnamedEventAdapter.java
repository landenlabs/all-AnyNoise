// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.subscribe;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.landenlabs.allAnyNoise.R;
import com.landenlabs.allAnyNoise.model.NoiseEvent;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Review queue of recent noiseEvents that have no soundLabelId yet. Rows are
 * either a {@link GroupHeader} (a client-side-only cluster of similar
 * fingerprints, see FingerprintGrouper) or a plain {@link NoiseEvent}; a
 * group's "Name group" button and a lone event's "Name" button both funnel
 * into the same callback, just with a different list size.
 */
public class UnnamedEventAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface OnNameRequestedListener {
        void onNameRequested(List<NoiseEvent> events);
    }

    public interface OnDismissRequestedListener {
        void onDismissRequested(NoiseEvent event);
    }

    /** A placeholder-named cluster of similar unnamed events; not persisted anywhere. */
    public static final class GroupHeader {
        final String title;
        final List<NoiseEvent> events;

        public GroupHeader(String title, List<NoiseEvent> events) {
            this.title = title;
            this.events = events;
        }
    }

    private static final int VIEW_TYPE_HEADER = 0;
    private static final int VIEW_TYPE_EVENT = 1;

    private final List<Object> rows = new ArrayList<>();
    private final OnNameRequestedListener nameListener;
    private final OnDismissRequestedListener dismissListener;

    private MediaPlayer activePlayer;
    private String activePlayEventId;

    public UnnamedEventAdapter(OnNameRequestedListener nameListener, OnDismissRequestedListener dismissListener) {
        this.nameListener = nameListener;
        this.dismissListener = dismissListener;
    }

    /** @param newRows a mix of {@link GroupHeader} and {@link NoiseEvent}, in display order. */
    public void submit(List<Object> newRows) {
        stopPlayback();
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    public boolean isEventRow(int position) {
        return position >= 0 && position < rows.size() && rows.get(position) instanceof NoiseEvent;
    }

    /** Optimistically removes the swiped row locally; the caller still owns the actual Firestore write. */
    public void onItemDismissedBySwipe(int position) {
        if (position < 0 || position >= rows.size()) {
            return;
        }
        Object row = rows.get(position);
        if (!(row instanceof NoiseEvent)) {
            return;
        }
        rows.remove(position);
        notifyItemRemoved(position);
        dismissListener.onDismissRequested((NoiseEvent) row);
    }

    /** Stops any in-progress preview playback; call from the fragment's onDestroyView to avoid leaking the player. */
    public void stopPlayback() {
        if (activePlayer != null) {
            activePlayer.release();
            activePlayer = null;
        }
        if (activePlayEventId != null) {
            activePlayEventId = null;
            notifyDataSetChanged();
        }
    }

    @Override
    public int getItemViewType(int position) {
        return rows.get(position) instanceof GroupHeader ? VIEW_TYPE_HEADER : VIEW_TYPE_EVENT;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_HEADER) {
            return new HeaderViewHolder(inflater.inflate(R.layout.item_unnamed_group_header, parent, false));
        }
        return new EventViewHolder(inflater.inflate(R.layout.item_unnamed_event, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder viewHolder, int position) {
        Object row = rows.get(position);
        if (row instanceof GroupHeader) {
            GroupHeader header = (GroupHeader) row;
            HeaderViewHolder holder = (HeaderViewHolder) viewHolder;
            holder.title.setText(header.title);
            holder.nameGroupButton.setOnClickListener(v -> nameListener.onNameRequested(header.events));
            return;
        }

        NoiseEvent event = (NoiseEvent) row;
        EventViewHolder holder = (EventViewHolder) viewHolder;
        holder.summary.setText(summarize(event));
        holder.nameButton.setOnClickListener(v -> nameListener.onNameRequested(Collections.singletonList(event)));

        boolean hasAudio = event.audioUrl != null;
        holder.playButton.setEnabled(hasAudio);
        boolean isPlayingThis = event.id != null && event.id.equals(activePlayEventId);
        holder.playButton.setText(isPlayingThis ? R.string.subscriptions_stop_button : R.string.subscriptions_play_button);
        holder.playButton.setOnClickListener(v -> togglePlay(event, v.getContext()));
    }

    @Override
    public int getItemCount() {
        return rows.size();
    }

    private void togglePlay(NoiseEvent event, Context context) {
        boolean wasPlayingThis = event.id != null && event.id.equals(activePlayEventId);
        stopPlayback();
        if (wasPlayingThis || event.audioUrl == null) {
            return;
        }
        try {
            MediaPlayer player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(event.audioUrl);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> stopPlayback());
            player.setOnErrorListener((mp, what, extra) -> {
                stopPlayback();
                Toast.makeText(context, R.string.subscriptions_play_failed, Toast.LENGTH_SHORT).show();
                return true;
            });
            player.prepareAsync();
            activePlayer = player;
            activePlayEventId = event.id;
            notifyDataSetChanged();
        } catch (IOException e) {
            stopPlayback();
            Toast.makeText(context, R.string.subscriptions_play_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private static String summarize(NoiseEvent event) {
        String time = event.startedAt != null
                ? new SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault()).format(event.startedAt)
                : "?";
        String soundType = event.soundType != null ? event.soundType.replace('_', ' ') : "";
        return String.format(Locale.getDefault(), "%s · %s · %.1fs · %s",
                time, event.listenerName, event.durationSec, soundType);
    }

    static class EventViewHolder extends RecyclerView.ViewHolder {
        final TextView summary;
        final Button playButton;
        final Button nameButton;

        EventViewHolder(@NonNull View itemView) {
            super(itemView);
            summary = itemView.findViewById(R.id.tv_summary);
            playButton = itemView.findViewById(R.id.btn_play);
            nameButton = itemView.findViewById(R.id.btn_name);
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final Button nameGroupButton;

        HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.tv_group_title);
            nameGroupButton = itemView.findViewById(R.id.btn_name_group);
        }
    }
}
