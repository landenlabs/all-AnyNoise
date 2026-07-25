package com.landenlabs.allAnyNoise.subscribe;

import com.landenlabs.allAnyNoise.model.NoiseEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Client-side-only, display-time clustering of not-yet-labeled noiseEvents by
 * cosine similarity of their spectral fingerprint - no Firestore schema or
 * Cloud Function involvement. Greedy single-linkage: each event joins the
 * most similar existing group above the threshold, or starts a new one.
 * Group identity/numbering is NOT persisted - it's recomputed from whatever
 * events are currently in the queue, purely to help a human triage similar
 * pending sounds together before naming them.
 */
public final class FingerprintGrouper {

    // Same cutoff as functions/index.js's LABEL_MATCH_THRESHOLD, same caveat:
    // unvalidated against real recordings. Kept as a separate constant since
    // this one only affects a local display grouping, not real auto-tagging.
    static final double GROUP_MATCH_THRESHOLD = 0.90;

    private FingerprintGrouper() {
    }

    public static final class Group {
        public final List<NoiseEvent> events = new ArrayList<>();
        private double[] centroidSum;
        private int count;

        private void add(NoiseEvent event, double[] fingerprint) {
            events.add(event);
            if (fingerprint.length == 0) {
                return;
            }
            if (centroidSum == null) {
                centroidSum = new double[fingerprint.length];
            }
            if (fingerprint.length != centroidSum.length) {
                return;
            }
            for (int i = 0; i < fingerprint.length; i++) {
                centroidSum[i] += fingerprint[i];
            }
            count++;
        }

        private double[] centroid() {
            if (centroidSum == null || count == 0) {
                return new double[0];
            }
            double[] mean = new double[centroidSum.length];
            for (int i = 0; i < mean.length; i++) {
                mean[i] = centroidSum[i] / count;
            }
            return normalize(mean);
        }
    }

    public static List<Group> group(List<NoiseEvent> events) {
        List<Group> groups = new ArrayList<>();
        for (NoiseEvent event : events) {
            double[] fingerprint = toArray(event.fingerprint);
            Group best = null;
            double bestSimilarity = -1;
            if (fingerprint.length > 0) {
                for (Group candidate : groups) {
                    double[] centroid = candidate.centroid();
                    if (centroid.length == 0) {
                        continue;
                    }
                    double similarity = cosineSimilarity(fingerprint, centroid);
                    if (similarity > bestSimilarity) {
                        bestSimilarity = similarity;
                        best = candidate;
                    }
                }
            }
            if (best == null || bestSimilarity < GROUP_MATCH_THRESHOLD) {
                best = new Group();
                groups.add(best);
            }
            best.add(event, fingerprint);
        }
        return groups;
    }

    private static double[] toArray(List<Double> list) {
        if (list == null || list.isEmpty()) {
            return new double[0];
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < list.size(); i++) {
            out[i] = list.get(i);
        }
        return out;
    }

    static double cosineSimilarity(double[] a, double[] b) {
        if (a.length == 0 || b.length == 0 || a.length != b.length) {
            return -1;
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA <= 0 || normB <= 0) {
            return -1;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }

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
}
