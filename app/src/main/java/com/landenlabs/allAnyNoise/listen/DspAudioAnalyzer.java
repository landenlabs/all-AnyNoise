package com.landenlabs.allAnyNoise.listen;

import com.landenlabs.allAnyNoise.model.AudioFeatures;
import com.landenlabs.allAnyNoise.model.PhysicalSoundType;

/**
 * Rule-based DSP classifier for one finished noise episode's raw PCM.
 * Computes RMS, zero-crossing rate, peak/attack timing, and an averaged
 * spectral centroid (via a small in-house FFT over evenly-spaced frames),
 * then tags the episode with a coarse {@link PhysicalSoundType}. The
 * thresholds below are an initial heuristic - expect to tune them against
 * real recordings once classification is running end-to-end.
 *
 * <p>Also emits a fixed-length spectral "fingerprint" (see
 * {@link com.landenlabs.allAnyNoise.model.AudioFeatures#fingerprint}) reusing
 * the same FFT frames, for grouping similar-sounding episodes together
 * server-side regardless of which coarse tag they land in.
 */
public final class DspAudioAnalyzer {

    private static final int FRAME_SIZE = 1024; // must be a power of two
    private static final int MAX_FRAMES = 32; // bounds analysis cost on long episodes
    private static final int MIN_SAMPLES_FOR_SPECTRUM = 256;

    private static final int MIN_PEAK_FOR_CLASSIFICATION = 500; // ignore near-silence
    private static final int CLICK_MAX_DURATION_MS = 200;
    private static final double CLICK_MIN_ZCR = 0.35;
    private static final int BANG_MIN_PEAK_AMPLITUDE = 20000; // of 32767
    private static final long BANG_MAX_ATTACK_MS = 15;
    private static final double RUMBLE_MAX_CENTROID_HZ = 150;
    private static final double HUM_MAX_ZCR = 0.15;
    private static final double HUM_MAX_CENTROID_VARIANCE_RATIO = 0.25; // stddev / mean

    /**
     * Fingerprint band count and range - log-spaced so low-frequency detail
     * (where most household appliance noise lives) isn't drowned out by a
     * few wide high-frequency bands. Cloud Function matching assumes this
     * exact length; if you change it, bump the length there too.
     */
    static final int FINGERPRINT_BANDS = 16;
    private static final double FINGERPRINT_MIN_HZ = 50;

    private DspAudioAnalyzer() {
    }

    /** @param pcm16Mono little-endian 16-bit mono PCM, as accumulated over one episode. */
    public static AudioFeatures analyze(byte[] pcm16Mono, int sampleRate) {
        short[] samples = toShorts(pcm16Mono);
        AudioFeatures features = new AudioFeatures();
        int n = samples.length;
        features.durationMs = n > 0 ? (long) (n * 1000L / sampleRate) : 0;
        if (n == 0) {
            return features;
        }

        double sumSquares = 0;
        int peakAmplitude = 0;
        int peakIndex = 0;
        int zeroCrossings = 0;
        for (int i = 0; i < n; i++) {
            int s = samples[i];
            sumSquares += (double) s * s;
            int abs = Math.abs(s);
            if (abs > peakAmplitude) {
                peakAmplitude = abs;
                peakIndex = i;
            }
            if (i > 0 && (samples[i - 1] >= 0) != (s >= 0)) {
                zeroCrossings++;
            }
        }
        double rms = Math.sqrt(sumSquares / n);
        long attackMs = peakIndex * 1000L / sampleRate;

        features.rmsDb = rms > 1 ? 20 * Math.log10(rms / 32768.0) : -96.0;
        features.peakAmplitude = peakAmplitude;
        features.zeroCrossingRate = n > 1 ? zeroCrossings / (double) (n - 1) : 0;

        SpectrumSummary spectrum = analyzeSpectrum(samples, sampleRate);
        features.spectralCentroidHz = spectrum.averageCentroidHz;
        features.fingerprint = spectrum.fingerprint;

        features.soundType = classify(features, attackMs, spectrum.centroidVarianceRatio);
        return features;
    }

    private static PhysicalSoundType classify(AudioFeatures f, long attackMs, double centroidVarianceRatio) {
        if (f.peakAmplitude < MIN_PEAK_FOR_CLASSIFICATION) {
            return PhysicalSoundType.UNKNOWN;
        }
        if (f.peakAmplitude >= BANG_MIN_PEAK_AMPLITUDE && attackMs <= BANG_MAX_ATTACK_MS) {
            return PhysicalSoundType.LOUD_BANG;
        }
        if (f.durationMs <= CLICK_MAX_DURATION_MS && f.zeroCrossingRate >= CLICK_MIN_ZCR) {
            return PhysicalSoundType.QUICK_CLICK;
        }
        if (f.spectralCentroidHz > 0 && f.spectralCentroidHz <= RUMBLE_MAX_CENTROID_HZ) {
            return PhysicalSoundType.LOW_RUMBLE;
        }
        if (f.zeroCrossingRate <= HUM_MAX_ZCR && centroidVarianceRatio <= HUM_MAX_CENTROID_VARIANCE_RATIO) {
            return PhysicalSoundType.STEADY_HUM;
        }
        return PhysicalSoundType.UNKNOWN;
    }

    private static SpectrumSummary analyzeSpectrum(short[] samples, int sampleRate) {
        SpectrumSummary summary = new SpectrumSummary();
        if (samples.length < MIN_SAMPLES_FOR_SPECTRUM) {
            return summary;
        }

        int frameCount = Math.max(1, Math.min(MAX_FRAMES, samples.length / FRAME_SIZE));
        int stride = frameCount > 1 ? (samples.length - FRAME_SIZE) / (frameCount - 1) : 0;

        double[] centroids = new double[frameCount];
        int validFrames = 0;
        double centroidSum = 0;
        double[] bandEnergy = new double[FINGERPRINT_BANDS];
        double nyquistHz = sampleRate / 2.0;
        double bandLogRange = Math.log(nyquistHz / FINGERPRINT_MIN_HZ);

        double[] re = new double[FRAME_SIZE];
        double[] im = new double[FRAME_SIZE];
        for (int frame = 0; frame < frameCount; frame++) {
            int start = Math.min(frame * stride, Math.max(0, samples.length - FRAME_SIZE));
            fillFrame(samples, start, re, im);
            fft(re, im);

            double weightedSum = 0;
            double magnitudeSum = 0;
            for (int bin = 1; bin < FRAME_SIZE / 2; bin++) {
                double magnitude = Math.hypot(re[bin], im[bin]);
                double freqHz = bin * sampleRate / (double) FRAME_SIZE;
                weightedSum += freqHz * magnitude;
                magnitudeSum += magnitude;
                bandEnergy[bandIndexForFreq(freqHz, nyquistHz, bandLogRange)] += magnitude;
            }
            if (magnitudeSum <= 0) {
                continue;
            }
            double centroid = weightedSum / magnitudeSum;
            centroids[validFrames++] = centroid;
            centroidSum += centroid;
        }

        summary.fingerprint = normalize(bandEnergy);
        if (validFrames == 0) {
            return summary;
        }
        double mean = centroidSum / validFrames;
        double variance = 0;
        for (int i = 0; i < validFrames; i++) {
            double diff = centroids[i] - mean;
            variance += diff * diff;
        }
        variance /= validFrames;

        summary.averageCentroidHz = mean;
        summary.centroidVarianceRatio = mean > 0 ? Math.sqrt(variance) / mean : 1.0;
        return summary;
    }

    /** Maps a frequency to one of FINGERPRINT_BANDS log-spaced bands between FINGERPRINT_MIN_HZ and Nyquist. */
    private static int bandIndexForFreq(double freqHz, double nyquistHz, double bandLogRange) {
        double clamped = Math.max(FINGERPRINT_MIN_HZ, Math.min(nyquistHz, freqHz));
        double t = Math.log(clamped / FINGERPRINT_MIN_HZ) / bandLogRange;
        int band = (int) (t * FINGERPRINT_BANDS);
        return Math.max(0, Math.min(FINGERPRINT_BANDS - 1, band));
    }

    /** L2-normalizes so the fingerprint's shape - not the episode's loudness - drives similarity matching. */
    private static double[] normalize(double[] vector) {
        double sumSquares = 0;
        for (double v : vector) {
            sumSquares += v * v;
        }
        double norm = Math.sqrt(sumSquares);
        if (norm <= 0) {
            return vector;
        }
        double[] normalized = new double[vector.length];
        for (int i = 0; i < vector.length; i++) {
            normalized[i] = vector[i] / norm;
        }
        return normalized;
    }

    /** Applies a Hann window before the FFT; a rectangular window leaks energy across
     *  many bins for non-integer-cycle content, which skews the centroid badly for
     *  low-frequency/tonal signals (exactly what LOW_RUMBLE/STEADY_HUM depend on). */
    private static void fillFrame(short[] samples, int start, double[] re, double[] im) {
        for (int i = 0; i < FRAME_SIZE; i++) {
            int index = start + i;
            double sample = index < samples.length ? samples[index] : 0;
            double window = 0.5 * (1 - Math.cos(2 * Math.PI * i / (FRAME_SIZE - 1)));
            re[i] = sample * window;
            im[i] = 0;
        }
    }

    /** In-place radix-2 Cooley-Tukey FFT; re/im length must be a power of two. */
    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) {
                j &= ~bit;
            }
            j |= bit;
            if (i < j) {
                double tmp = re[i];
                re[i] = re[j];
                re[j] = tmp;
                tmp = im[i];
                im[i] = im[j];
                im[j] = tmp;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double angle = -2 * Math.PI / len;
            double wReal = Math.cos(angle);
            double wImag = Math.sin(angle);
            for (int i = 0; i < n; i += len) {
                double curReal = 1;
                double curImag = 0;
                for (int j = 0; j < len / 2; j++) {
                    double uRe = re[i + j];
                    double uIm = im[i + j];
                    double vRe = re[i + j + len / 2] * curReal - im[i + j + len / 2] * curImag;
                    double vIm = re[i + j + len / 2] * curImag + im[i + j + len / 2] * curReal;
                    re[i + j] = uRe + vRe;
                    im[i + j] = uIm + vIm;
                    re[i + j + len / 2] = uRe - vRe;
                    im[i + j + len / 2] = uIm - vIm;
                    double nextReal = curReal * wReal - curImag * wImag;
                    double nextImag = curReal * wImag + curImag * wReal;
                    curReal = nextReal;
                    curImag = nextImag;
                }
            }
        }
    }

    private static short[] toShorts(byte[] pcm16Mono) {
        short[] samples = new short[pcm16Mono.length / 2];
        for (int i = 0; i < samples.length; i++) {
            samples[i] = (short) ((pcm16Mono[i * 2] & 0xFF) | (pcm16Mono[i * 2 + 1] << 8));
        }
        return samples;
    }

    private static final class SpectrumSummary {
        double averageCentroidHz = 0;
        double centroidVarianceRatio = 1.0;
        double[] fingerprint = new double[FINGERPRINT_BANDS];
    }
}
