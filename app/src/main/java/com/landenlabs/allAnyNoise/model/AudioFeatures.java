package com.landenlabs.allAnyNoise.model;

/** DSP metrics computed over one finished episode's PCM, plus the resulting tag. */
public class AudioFeatures {

    public double rmsDb;
    public int peakAmplitude;
    public double zeroCrossingRate;
    public double spectralCentroidHz;
    public long durationMs;
    public PhysicalSoundType soundType = PhysicalSoundType.UNKNOWN;

    public AudioFeatures() {
    }
}
