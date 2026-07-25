// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { defineString } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

const SHEETS_WEBHOOK_URL = defineString("SHEETS_WEBHOOK_URL", { default: "" });

/**
 * Fires whenever the Android app writes a noiseEvents/{eventId} document.
 * Tries to auto-match the event's spectral fingerprint to an existing
 * human-named soundLabels centroid, then fans the event out as an FCM push
 * to every device that hasn't muted that listener or that sound label, then
 * (best-effort) logs a row to a Google Sheet via an Apps Script webhook.
 */
exports.onNoiseEventCreated = onDocumentCreated("noiseEvents/{eventId}", async (event) => {
  const snapshot = event.data;
  if (!snapshot) {
    return;
  }
  const noiseEvent = snapshot.data();
  const { listenerId, listenerName, durationSec, soundType, fingerprint } = noiseEvent;
  const db = getFirestore();

  const label = await matchSoundLabel(db, soundType, fingerprint);
  if (label) {
    noiseEvent.soundLabelId = label.id;
    noiseEvent.soundLabelName = label.name;
    await snapshot.ref.update({
      soundLabelId: label.id,
      soundLabelName: label.name,
      labelSource: "auto",
    });
  }

  await Promise.all([
    notifySubscribers(db, listenerId, listenerName, durationSec, soundType, event.params.eventId,
        label ? label.id : null, label ? label.name : null),
    logToSheet(noiseEvent),
  ]);
});

const SOUND_TYPE_LABELS = {
  QUICK_CLICK: "Quick click",
  STEADY_HUM: "Steady hum",
  LOUD_BANG: "Loud bang",
  LOW_RUMBLE: "Low rumble",
  UNKNOWN: "Sustained sound",
};

function describeSoundType(soundType) {
  return SOUND_TYPE_LABELS[soundType] || SOUND_TYPE_LABELS.UNKNOWN;
}

// Cosine similarity at/above this is considered "the same sound". Unvalidated
// against real recordings yet - same caveat as DspAudioAnalyzer's own DSP
// thresholds; expect to tune this once labeling is in real use.
const LABEL_MATCH_THRESHOLD = 0.90;

/**
 * Finds the best-matching existing soundLabels doc for this event's
 * fingerprint, restricted to labels sharing the same coarse physicalSoundType
 * (cheap pre-filter, and avoids ever auto-merging e.g. a LOUD_BANG into a
 * label that was named from a STEADY_HUM episode). On a match, folds this
 * fingerprint into the label's running-mean centroid. Returns null if there's
 * no candidate above LABEL_MATCH_THRESHOLD (including: no fingerprint on the
 * event, or no labels created yet).
 */
async function matchSoundLabel(db, physicalSoundType, fingerprint) {
  if (!physicalSoundType || !Array.isArray(fingerprint) || fingerprint.length === 0) {
    return null;
  }

  const candidatesSnap = await db.collection("soundLabels")
      .where("physicalSoundType", "==", physicalSoundType)
      .get();
  if (candidatesSnap.empty) {
    return null;
  }

  let best = null;
  let bestSimilarity = -1;
  candidatesSnap.forEach((doc) => {
    const similarity = cosineSimilarity(fingerprint, doc.data().centroid);
    if (similarity !== null && similarity > bestSimilarity) {
      bestSimilarity = similarity;
      best = { id: doc.id, name: doc.data().name };
    }
  });

  if (!best || bestSimilarity < LABEL_MATCH_THRESHOLD) {
    return null;
  }

  await foldFingerprintIntoCentroid(db, best.id, fingerprint);
  return best;
}

function cosineSimilarity(a, b) {
  if (!Array.isArray(a) || !Array.isArray(b) || a.length === 0 || a.length !== b.length) {
    return null;
  }
  let dot = 0;
  let normA = 0;
  let normB = 0;
  for (let i = 0; i < a.length; i++) {
    dot += a[i] * b[i];
    normA += a[i] * a[i];
    normB += b[i] * b[i];
  }
  if (normA <= 0 || normB <= 0) {
    return null;
  }
  return dot / (Math.sqrt(normA) * Math.sqrt(normB));
}

/** Runs as a transaction: multiple devices' events can match the same label concurrently. */
async function foldFingerprintIntoCentroid(db, labelId, fingerprint) {
  const ref = db.collection("soundLabels").doc(labelId);
  await db.runTransaction(async (tx) => {
    const snap = await tx.get(ref);
    if (!snap.exists) {
      return;
    }
    const data = snap.data();
    const sampleCount = data.sampleCount || 0;
    const centroid = Array.isArray(data.centroid) && data.centroid.length === fingerprint.length
        ? data.centroid
        : fingerprint;
    const merged = centroid.map((v, i) => (v * sampleCount + fingerprint[i]) / (sampleCount + 1));
    const norm = Math.sqrt(merged.reduce((sum, v) => sum + v * v, 0));
    const normalized = norm > 0 ? merged.map((v) => v / norm) : merged;
    tx.update(ref, {
      centroid: normalized,
      sampleCount: sampleCount + 1,
      updatedAt: FieldValue.serverTimestamp(),
    });
  });
}

async function notifySubscribers(db, listenerId, listenerName, durationSec, soundType, eventId,
    soundLabelId, soundLabelName) {
  const devicesSnap = await db.collection("devices").get();

  const targets = [];
  devicesSnap.forEach((doc) => {
    const device = doc.data();
    const mutedListeners = device.mutedListenerIds || [];
    const mutedLabels = device.mutedSoundLabelIds || [];
    const listenerMuted = mutedListeners.includes(listenerId);
    const labelMuted = !!soundLabelId && mutedLabels.includes(soundLabelId);
    if (device.fcmToken && !listenerMuted && !labelMuted) {
      targets.push({ deviceId: doc.id, token: device.fcmToken });
    }
  });

  if (targets.length === 0) {
    logger.info(`No subscribers to notify for listener ${listenerId}`);
    return;
  }

  const soundDescription = soundLabelName || describeSoundType(soundType);
  const response = await getMessaging().sendEachForMulticast({
    tokens: targets.map((t) => t.token),
    notification: {
      title: `Noise detected: ${listenerName}`,
      body: `${soundDescription} for ${Math.round(durationSec)}s`,
    },
    data: {
      listenerId,
      eventId,
      soundType: soundType || "UNKNOWN",
      soundLabelId: soundLabelId || "",
      soundLabelName: soundLabelName || "",
    },
  });

  const staleTokenUpdates = [];
  response.responses.forEach((result, index) => {
    const errorCode = result.error && result.error.code;
    if (!result.success && errorCode === "messaging/registration-token-not-registered") {
      staleTokenUpdates.push(
        db.collection("devices").doc(targets[index].deviceId).update({ fcmToken: null })
      );
    }
  });
  await Promise.all(staleTokenUpdates);
}

async function logToSheet(noiseEvent) {
  const webhookUrl = SHEETS_WEBHOOK_URL.value();
  if (!webhookUrl) {
    return;
  }
  try {
    await fetch(webhookUrl, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        timestamp: noiseEvent.startedAt && noiseEvent.startedAt.toDate
          ? noiseEvent.startedAt.toDate().toISOString()
          : new Date().toISOString(),
        listenerName: noiseEvent.listenerName,
        durationSec: noiseEvent.durationSec,
        audioUrl: noiseEvent.audioUrl || "",
        soundType: noiseEvent.soundType || "",
        soundLabelName: noiseEvent.soundLabelName || "",
      }),
    });
  } catch (err) {
    logger.error("Failed to log noise event to Sheets webhook", err);
  }
}
