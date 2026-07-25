package com.landenlabs.allAnyNoise.model;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;
import java.util.List;

/** Mirrors a noiseEvents/{eventId} Firestore document. */
public class NoiseEvent {

    public String listenerId;
    public String listenerName;
    public double durationSec;
    public String audioUrl;
    public String soundType;

    /** Spectral fingerprint used for soundLabels matching; see DspAudioAnalyzer. */
    public List<Double> fingerprint;

    /** Set once a human names this sound (or the Cloud Function auto-matches it to an existing name). */
    public String soundLabelId;
    public String soundLabelName;
    /** "manual" (a person named it) or "auto" (matched to an existing soundLabels centroid); null if unlabeled. */
    public String labelSource;

    /** Set (client-side, via swipe or "clear all") to hide from the unnamed-sounds review queue without naming it. */
    public Boolean dismissed;

    @ServerTimestamp
    public Date startedAt;

    @Exclude
    public String id;

    public NoiseEvent() {
    }

    public NoiseEvent(String listenerId, String listenerName, double durationSec, String audioUrl) {
        this.listenerId = listenerId;
        this.listenerName = listenerName;
        this.durationSec = durationSec;
        this.audioUrl = audioUrl;
    }
}
