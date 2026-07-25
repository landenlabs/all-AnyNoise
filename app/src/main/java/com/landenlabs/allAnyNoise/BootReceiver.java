// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import androidx.core.content.ContextCompat;

import com.landenlabs.allAnyNoise.listen.NoiseListenerService;

public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        if (!Prefs.isListenerActive(context)) {
            return;
        }
        Intent serviceIntent = new Intent(context, NoiseListenerService.class);
        serviceIntent.setAction(NoiseListenerService.ACTION_START);
        ContextCompat.startForegroundService(context, serviceIntent);
    }
}
