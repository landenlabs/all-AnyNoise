package com.landenlabs.allAnyNoise.model;

/** DSP metrics computed over one finished episode's PCM, plus the resulting tag. */
public class AudioFeatures {

    public double rmsDb;
    public int peakAmplitude;
    public double zeroCrossingRate;
    public double spectralCentroidHz;
    public long durationMs;
    public PhysicalSoundType soundType = PhysicalSoundType.UNKNOWN;

    /**
     * Loudness-invariant spectral "fingerprint" for grouping similar-sounding
     * episodes (see DspAudioAnalyzer.FINGERPRINT_BANDS) - a fixed-length,
     * L2-normalized vector of log-spaced band energies. All-zero when the
     * episode was too short to analyze (see DspAudioAnalyzer.MIN_SAMPLES_FOR_SPECTRUM).
     */
    public double[] fingerprint = new double[0];

    public AudioFeatures() {
    }
}
