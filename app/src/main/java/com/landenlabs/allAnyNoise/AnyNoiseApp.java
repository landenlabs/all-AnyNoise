package com.landenlabs.allAnyNoise;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

public class AnyNoiseApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationHelper.createChannels(this);
        AppCompatDelegate.setDefaultNightMode(Prefs.isDarkThemeEnabled(this)
                ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_NO);
    }
}
