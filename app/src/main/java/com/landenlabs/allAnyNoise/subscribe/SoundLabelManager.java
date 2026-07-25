// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.subscribe;

import android.content.Context;

import androidx.annotation.NonNull;

import com.landenlabs.allAnyNoise.DeviceIdentity;
import com.google.firebase.firestore.DocumentReference;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.WriteBatch;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Assigns a human-entered name to a noiseEvents doc: merges into an existing
 * soundLabels doc with the same (lowercased) name and physicalSoundType if
 * one exists, folding this event's fingerprint into its running-mean
 * centroid, or creates a new soundLabels doc seeded with this fingerprint.
 * Mirrors the centroid-fold math in functions/index.js's foldFingerprintIntoCentroid -
 * keep the two in sync if either changes.
 *
 * <p>The lookup-by-name query runs outside any transaction (Firestore's
 * client SDK can't run queries inside a transaction), so two devices naming
 * the same not-yet-labeled sound with the same brand-new name at the same
 * instant could create two labels instead of merging into one. Accepted as a
 * rare, low-stakes race for this single-household, no-auth app - same risk
 * tier as the rest of the trust-by-deviceId model.
 */
public final class SoundLabelManager {

    private SoundLabelManager() {
    }

    public interface OnNameSavedListener {
        void onSaved();

        void onFailed(@NonNull Exception e);
    }

    public static void nameEvent(Context context, String eventId, String physicalSoundType,
                                  List<Double> fingerprint, String enteredName,
                                  OnNameSavedListener callback) {
        String name = enteredName == null ? "" : enteredName.trim();
        if (name.isEmpty()) {
            callback.onFailed(new IllegalArgumentException("Name required"));
            return;
        }
        String soundType = physicalSoundType != null ? physicalSoundType : "UNKNOWN";
        List<Double> safeFingerprint = fingerprint != null ? fingerprint : new ArrayList<>();
        String nameLower = name.toLowerCase(Locale.US);

        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("soundLabels")
                .whereEqualTo("nameLower", nameLower)
                .whereEqualTo("physicalSoundType", soundType)
                .limit(1)
                .get()
                .addOnSuccessListener(snapshot -> {
                    if (!snapshot.isEmpty()) {
                        mergeIntoExistingLabel(db, snapshot.getDocuments().get(0).getReference(),
                                eventId, safeFingerprint, callback);
                    } else {
                        createNewLabel(db, context, name, nameLower, soundType, safeFingerprint,
                                eventId, callback);
                    }
                })
                .addOnFailureListener(callback::onFailed);
    }

    private static void mergeIntoExistingLabel(FirebaseFirestore db, DocumentReference labelRef,
                                                String eventId, List<Double> fingerprint,
                                                OnNameSavedListener callback) {
        db.runTransaction(transaction -> {
            DocumentSnapshot fresh = transaction.get(labelRef);
            long sampleCount = fresh.contains("sampleCount") && fresh.getLong("sampleCount") != null
                    ? fresh.getLong("sampleCount") : 0;
            @SuppressWarnings("unchecked")
            List<Double> centroid = (List<Double>) fresh.get("centroid");
            List<Double> merged = foldFingerprint(centroid != null ? centroid : fingerprint, sampleCount, fingerprint);

            Map<String, Object> update = new HashMap<>();
            update.put("centroid", merged);
            update.put("sampleCount", sampleCount + 1);
            update.put("updatedAt", FieldValue.serverTimestamp());
            transaction.update(labelRef, update);
            return fresh.getString("name");
        }).addOnSuccessListener(name -> tagEvent(db, eventId, labelRef.getId(), name, callback))
                .addOnFailureListener(callback::onFailed);
    }

    private static void createNewLabel(FirebaseFirestore db, Context context, String name, String nameLower,
                                        String physicalSoundType, List<Double> fingerprint, String eventId,
                                        OnNameSavedListener callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("name", name);
        data.put("nameLower", nameLower);
        data.put("physicalSoundType", physicalSoundType);
        data.put("centroid", fingerprint);
        data.put("sampleCount", 1);
        data.put("createdByDeviceId", DeviceIdentity.getDeviceId(context));
        data.put("createdAt", FieldValue.serverTimestamp());
        data.put("updatedAt", FieldValue.serverTimestamp());

        db.collection("soundLabels").add(data)
                .addOnSuccessListener(ref -> tagEvent(db, eventId, ref.getId(), name, callback))
                .addOnFailureListener(callback::onFailed);
    }

    private static void tagEvent(FirebaseFirestore db, String eventId, String labelId, String labelName,
                                  OnNameSavedListener callback) {
        Map<String, Object> update = new HashMap<>();
        update.put("soundLabelId", labelId);
        update.put("soundLabelName", labelName);
        update.put("labelSource", "manual");
        db.collection("noiseEvents").document(eventId).update(update)
                .addOnSuccessListener(unused -> callback.onSaved())
                .addOnFailureListener(callback::onFailed);
    }

    public interface OnDeleteFinishedListener {
        void onFinished();

        void onFailed(@NonNull Exception e);
    }

    /** Renames an existing soundLabels doc; does not touch soundLabelName already copied onto past noiseEvents. */
    public static void renameLabel(String labelId, String enteredName, OnNameSavedListener callback) {
        String name = enteredName == null ? "" : enteredName.trim();
        if (name.isEmpty()) {
            callback.onFailed(new IllegalArgumentException("Name required"));
            return;
        }
        Map<String, Object> update = new HashMap<>();
        update.put("name", name);
        update.put("nameLower", name.toLowerCase(Locale.US));
        update.put("updatedAt", FieldValue.serverTimestamp());
        FirebaseFirestore.getInstance().collection("soundLabels").document(labelId)
                .update(update)
                .addOnSuccessListener(unused -> callback.onSaved())
                .addOnFailureListener(callback::onFailed);
    }

    /**
     * Deletes a soundLabels doc and reverts any noiseEvents tagged with it back to
     * unnamed (clearing soundLabelId/soundLabelName/labelSource) so they reappear in
     * the unnamed-sounds review queue instead of being permanently orphaned with a
     * dangling label reference.
     */
    public static void deleteLabel(Context context, String labelId, OnDeleteFinishedListener callback) {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection("noiseEvents").whereEqualTo("soundLabelId", labelId).get()
                .addOnSuccessListener(snapshot -> {
                    WriteBatch batch = db.batch();
                    for (DocumentSnapshot doc : snapshot.getDocuments()) {
                        Map<String, Object> revert = new HashMap<>();
                        revert.put("soundLabelId", FieldValue.delete());
                        revert.put("soundLabelName", FieldValue.delete());
                        revert.put("labelSource", FieldValue.delete());
                        batch.update(doc.getReference(), revert);
                    }
                    batch.delete(db.collection("soundLabels").document(labelId));
                    batch.commit()
                            .addOnSuccessListener(unused -> {
                                DeviceIdentity.setSoundLabelMuted(context, labelId, false);
                                callback.onFinished();
                            })
                            .addOnFailureListener(callback::onFailed);
                })
                .addOnFailureListener(callback::onFailed);
    }

    /** Running-mean fold, re-normalized - mirrors functions/index.js's foldFingerprintIntoCentroid. */
    private static List<Double> foldFingerprint(List<Double> centroid, long sampleCount, List<Double> fingerprint) {
        int n = fingerprint.size();
        double[] merged = new double[n];
        double sumSquares = 0;
        for (int i = 0; i < n; i++) {
            double c = centroid != null && i < centroid.size() ? centroid.get(i) : 0;
            double v = (c * sampleCount + fingerprint.get(i)) / (sampleCount + 1);
            merged[i] = v;
            sumSquares += v * v;
        }
        double norm = Math.sqrt(sumSquares);
        List<Double> result = new ArrayList<>(n);
        for (double v : merged) {
            result.add(norm > 0 ? v / norm : v);
        }
        return result;
    }
}
