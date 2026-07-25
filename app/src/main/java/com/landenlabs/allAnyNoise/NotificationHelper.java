// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;

public class NotificationHelper {

    public static final String CHANNEL_LISTENING = "listening_service";
    public static final String CHANNEL_ALERTS = "noise_alerts";

    public static void createChannels(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }

        NotificationChannel listening = new NotificationChannel(
                CHANNEL_LISTENING,
                context.getString(R.string.notification_channel_listening_name),
                NotificationManager.IMPORTANCE_LOW);
        listening.setDescription(context.getString(R.string.notification_channel_listening_desc));
        manager.createNotificationChannel(listening);

        NotificationChannel alerts = new NotificationChannel(
                CHANNEL_ALERTS,
                context.getString(R.string.notification_channel_alerts_name),
                NotificationManager.IMPORTANCE_HIGH);
        alerts.setDescription(context.getString(R.string.notification_channel_alerts_desc));
        manager.createNotificationChannel(alerts);
    }

    public static Notification buildListeningNotification(Context context, String listenerName) {
        Intent openApp = new Intent(context, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(context, CHANNEL_LISTENING)
                .setContentTitle(context.getString(R.string.notification_listening_title))
                .setContentText(context.getString(R.string.notification_listening_text, listenerName))
                .setSmallIcon(R.drawable.ic_mic)
                .setOngoing(true)
                .setContentIntent(pendingIntent)
                .build();
    }
}
