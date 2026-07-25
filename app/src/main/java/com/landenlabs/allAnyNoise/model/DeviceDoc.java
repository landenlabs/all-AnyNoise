// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.model;

import com.google.firebase.firestore.Exclude;
import com.google.firebase.firestore.ServerTimestamp;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** Mirrors a devices/{deviceId} Firestore document. */
public class DeviceDoc {

    public String fcmToken;
    public String displayName;
    public List<String> mutedListenerIds = new ArrayList<>();
    public List<String> mutedSoundLabelIds = new ArrayList<>();

    @ServerTimestamp
    public Date updatedAt;

    public Long batteryLevelPct;
    public String batteryHealth;
    public Double batteryTempC;

    @ServerTimestamp
    public Date batteryUpdatedAt;

    @Exclude
    public String id;

    public DeviceDoc() {
    }
}
