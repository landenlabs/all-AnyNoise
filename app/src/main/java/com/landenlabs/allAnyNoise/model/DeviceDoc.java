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

    @ServerTimestamp
    public Date updatedAt;

    @Exclude
    public String id;

    public DeviceDoc() {
    }
}
