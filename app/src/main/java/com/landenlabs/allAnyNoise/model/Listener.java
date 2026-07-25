// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.model;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.Date;

/** Mirrors a listeners/{listenerId} Firestore document. */
public class Listener {

    public String name;
    public String ownerDeviceId;
    public boolean active;
    public int thresholdAmplitude;
    public long minDurationMs;

    @ServerTimestamp
    public Date createdAt;

    @Exclude
    public String id;

    public Listener() {
    }
}
