// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

import com.landenlabs.allAnyNoise.battery.BatteryReportScheduler;

public class AnyNoiseApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createChannels(this);
        AppCompatDelegate.setDefaultNightMode(Prefs.isDarkThemeEnabled(this)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
        BatteryReportScheduler.schedule(this, Prefs.getBatteryReportIntervalHours(this));
    }
}
