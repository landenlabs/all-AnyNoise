package com.landenlabs.allAnyNoise.model;

import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

/** Mirrors a noiseEvents/{eventId} Firestore document. */
public class NoiseEvent {

    public String listenerId;
    public String listenerName;
    public double durationSec;
    public String audioUrl;
    public String soundType;

    @ServerTimestamp
    public Date startedAt;

    public NoiseEvent() {
    }

    public NoiseEvent(String listenerId, String listenerName, double durationSec, String audioUrl) {
        this.listenerId = listenerId;
        this.listenerName = listenerName;
        this.durationSec = durationSec;
        this.audioUrl = audioUrl;
    }
}
