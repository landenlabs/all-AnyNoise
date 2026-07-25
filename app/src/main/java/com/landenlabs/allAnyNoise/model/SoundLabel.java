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

/**
 * Mirrors a soundLabels/{labelId} Firestore document - a human-assigned name
 * (e.g. "Furnace") for a group of similar-sounding noiseEvents, identified by
 * a running-mean spectral fingerprint centroid. See DspAudioAnalyzer for how
 * the fingerprint itself is computed, and functions/index.js for how new
 * events get auto-matched against these centroids.
 */
public class SoundLabel {

    public String name;
    /** Lowercased name, used for exact-match merge lookups when a user names a new event. */
    public String nameLower;
    /** Coarse PhysicalSoundType this label lives in; narrows auto-match candidates. */
    public String physicalSoundType;
    public List<Double> centroid = new ArrayList<>();
    public long sampleCount;
    public String createdByDeviceId;

    @ServerTimestamp
    public Date createdAt;

    @ServerTimestamp
    public Date updatedAt;

    @Exclude
    public String id;

    public SoundLabel() {
    }
}
