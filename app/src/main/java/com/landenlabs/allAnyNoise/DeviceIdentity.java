package com.landenlabs.allAnyNoise;

import android.content.Context;
import android.os.Build;
import android.util.Log;

import com.google.firebase.firestore.FieldValue;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessaging;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * Registers this install as a "device" document in Firestore so the Cloud
 * Functions backend can push notifications to it, and manages this device's
 * per-listener opt-outs.
 */
public class DeviceIdentity {

    private static final String TAG = "DeviceIdentity";
    private static final String COLLECTION = "devices";

    public static String getDeviceId(Context context) {
        return Prefs.getDeviceId(context);
    }

    /** Ensures a devices/{deviceId} document exists with a current FCM token. */
    public static void registerDevice(Context context) {
        FirebaseMessaging.getInstance().getToken().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                Log.w(TAG, "Failed to fetch FCM token", task.getException());
                return;
            }
            updateFcmToken(context, task.getResult());
        });
    }

    public static void updateFcmToken(Context context, String token) {
        String deviceId = getDeviceId(context);
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        db.collection(COLLECTION).document(deviceId).get().addOnSuccessListener(snapshot -> {
            if (snapshot.exists()) {
                Map<String, Object> update = new HashMap<>();
                update.put("fcmToken", token);
                update.put("displayName", displayName());
                update.put("updatedAt", FieldValue.serverTimestamp());
                db.collection(COLLECTION).document(deviceId).update(update);
            } else {
                Map<String, Object> data = new HashMap<>();
                data.put("fcmToken", token);
                data.put("displayName", displayName());
                data.put("mutedListenerIds", new ArrayList<String>());
                data.put("updatedAt", FieldValue.serverTimestamp());
                db.collection(COLLECTION).document(deviceId).set(data);
            }
        });
    }

    public static void setListenerMuted(Context context, String listenerId, boolean muted) {
        String deviceId = getDeviceId(context);
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        Object update = muted
                ? FieldValue.arrayUnion(listenerId)
                : FieldValue.arrayRemove(listenerId);
        db.collection(COLLECTION).document(deviceId)
                .update("mutedListenerIds", update, "updatedAt", FieldValue.serverTimestamp());
    }

    private static String displayName() {
        return Build.MANUFACTURER + " " + Build.MODEL;
    }
}
