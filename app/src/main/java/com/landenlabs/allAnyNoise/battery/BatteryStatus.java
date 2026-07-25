// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.battery;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;

/**
 * Snapshot of the device's battery level/health/temperature, read from the
 * sticky {@code ACTION_BATTERY_CHANGED} broadcast. No permission required.
 */
public class BatteryStatus {

    public final long levelPct;
    public final String health;
    public final double tempC;

    private BatteryStatus(long levelPct, String health, double tempC) {
        this.levelPct = levelPct;
        this.health = health;
        this.tempC = tempC;
    }

    /** Returns null if the sticky battery broadcast isn't available yet. */
    public static BatteryStatus read(Context context) {
        Intent batteryStatus = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryStatus == null) {
            return null;
        }

        int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        long levelPct = (level >= 0 && scale > 0) ? Math.round(level * 100.0 / scale) : -1;

        int healthCode = batteryStatus.getIntExtra(BatteryManager.EXTRA_HEALTH,
                BatteryManager.BATTERY_HEALTH_UNKNOWN);
        String health = healthLabel(healthCode);

        int tempTenthsC = batteryStatus.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        double tempC = tempTenthsC != Integer.MIN_VALUE ? tempTenthsC / 10.0 : Double.NaN;

        return new BatteryStatus(levelPct, health, tempC);
    }

    private static String healthLabel(int healthCode) {
        switch (healthCode) {
            case BatteryManager.BATTERY_HEALTH_GOOD:
                return "Good";
            case BatteryManager.BATTERY_HEALTH_OVERHEAT:
                return "Overheat";
            case BatteryManager.BATTERY_HEALTH_DEAD:
                return "Dead";
            case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE:
                return "Over voltage";
            case BatteryManager.BATTERY_HEALTH_COLD:
                return "Cold";
            case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE:
                return "Unspecified failure";
            default:
                return "Unknown";
        }
    }
}
