package com.landenlabs.allAnyNoise;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Local persisted state shared between the UI, the foreground listening
 * service, and the boot receiver that restarts it.
 */
public class Prefs {

    private static final String FILE = "anynoise_prefs";

    private static final String KEY_DEVICE_ID = "device_id";
    private static final String KEY_LISTENER_ACTIVE = "listener_active";
    private static final String KEY_LISTENER_ID = "listener_id";
    private static final String KEY_LISTENER_NAME = "listener_name";
    private static final String KEY_THRESHOLD_AMPLITUDE = "threshold_amplitude";
    private static final String KEY_MIN_DURATION_MS = "min_duration_ms";
    private static final String KEY_RECORD_AUDIO_CLIP = "record_audio_clip";
    private static final String KEY_DARK_THEME = "dark_theme_enabled";
    private static final String KEY_BATTERY_REPORT_INTERVAL_HOURS = "battery_report_interval_hours";
    private static final String KEY_LIGHT_SENSITIVITY_LUX = "light_sensitivity_lux";

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String getDeviceId(Context context) {
        SharedPreferences p = prefs(context);
        String id = p.getString(KEY_DEVICE_ID, null);
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
            p.edit().putString(KEY_DEVICE_ID, id).apply();
        }
        return id;
    }

    public static boolean isListenerActive(Context context) {
        return prefs(context).getBoolean(KEY_LISTENER_ACTIVE, false);
    }

    public static void saveListenerConfig(Context context, boolean active, String listenerId,
                                           String listenerName, int thresholdAmplitude,
                                           long minDurationMs, boolean recordAudioClip) {
        prefs(context).edit()
                .putBoolean(KEY_LISTENER_ACTIVE, active)
                .putString(KEY_LISTENER_ID, listenerId)
                .putString(KEY_LISTENER_NAME, listenerName)
                .putInt(KEY_THRESHOLD_AMPLITUDE, thresholdAmplitude)
                .putLong(KEY_MIN_DURATION_MS, minDurationMs)
                .putBoolean(KEY_RECORD_AUDIO_CLIP, recordAudioClip)
                .apply();
    }

    public static void setListenerActive(Context context, boolean active) {
        prefs(context).edit().putBoolean(KEY_LISTENER_ACTIVE, active).apply();
    }

    public static String getListenerId(Context context) {
        return prefs(context).getString(KEY_LISTENER_ID, null);
    }

    public static String getListenerName(Context context) {
        return prefs(context).getString(KEY_LISTENER_NAME, "");
    }

    public static int getThresholdAmplitude(Context context) {
        return prefs(context).getInt(KEY_THRESHOLD_AMPLITUDE, 4000);
    }

    public static long getMinDurationMs(Context context) {
        return prefs(context).getLong(KEY_MIN_DURATION_MS, 3000);
    }

    public static boolean getRecordAudioClip(Context context) {
        return prefs(context).getBoolean(KEY_RECORD_AUDIO_CLIP, false);
    }

    public static boolean isDarkThemeEnabled(Context context) {
        return prefs(context).getBoolean(KEY_DARK_THEME, false);
    }

    public static void setDarkThemeEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_DARK_THEME, enabled).apply();
    }

    public static int getBatteryReportIntervalHours(Context context) {
        return prefs(context).getInt(KEY_BATTERY_REPORT_INTERVAL_HOURS, 6);
    }

    public static void setBatteryReportIntervalHours(Context context, int hours) {
        prefs(context).edit().putInt(KEY_BATTERY_REPORT_INTERVAL_HOURS, hours).apply();
    }

    public static int getLightSensitivityThresholdLux(Context context) {
        return prefs(context).getInt(KEY_LIGHT_SENSITIVITY_LUX, 50);
    }

    public static void setLightSensitivityThresholdLux(Context context, int thresholdLux) {
        prefs(context).edit().putInt(KEY_LIGHT_SENSITIVITY_LUX, thresholdLux).apply();
    }
}
