const { onDocumentCreated } = require("firebase-functions/v2/firestore");
const { defineString } = require("firebase-functions/params");
const logger = require("firebase-functions/logger");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();

const SHEETS_WEBHOOK_URL = defineString("SHEETS_WEBHOOK_URL", { default: "" });

/**
 * Fires whenever the Android app writes a noiseEvents/{eventId} document.
 * Fans the event out as an FCM push to every device that hasn't muted that
 * listener, then (best-effort) logs a row to a Google Sheet via an Apps
 * Script webhook.
 */
exports.onNoiseEventCreated = onDocumentCreated("noiseEvents/{eventId}", async (event) => {
  const snapshot = event.data;
  if (!snapshot) {
    return;
  }
  const noiseEvent = snapshot.data();
  const { listenerId, listenerName, durationSec, soundType } = noiseEvent;
  const db = getFirestore();

  await Promise.all([
    notifySubscribers(db, listenerId, listenerName, durationSec, soundType, event.params.eventId),
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

async function notifySubscribers(db, listenerId, listenerName, durationSec, soundType, eventId) {
  const devicesSnap = await db.collection("devices").get();

  const targets = [];
  devicesSnap.forEach((doc) => {
    const device = doc.data();
    const muted = device.mutedListenerIds || [];
    if (device.fcmToken && !muted.includes(listenerId)) {
      targets.push({ deviceId: doc.id, token: device.fcmToken });
    }
  });

  if (targets.length === 0) {
    logger.info(`No subscribers to notify for listener ${listenerId}`);
    return;
  }

  const response = await getMessaging().sendEachForMulticast({
    tokens: targets.map((t) => t.token),
    notification: {
      title: `Noise detected: ${listenerName}`,
      body: `${describeSoundType(soundType)} for ${Math.round(durationSec)}s`,
    },
    data: { listenerId, eventId, soundType: soundType || "UNKNOWN" },
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
      }),
    });
  } catch (err) {
    logger.error("Failed to log noise event to Sheets webhook", err);
  }
}
