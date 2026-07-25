// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise.battery;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.landenlabs.allAnyNoise.DeviceIdentity;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Periodic job that reports this device's battery snapshot to Firestore. */
public class BatteryReportWorker extends Worker {

    private static final String TAG = "BatteryReportWorker";

    public BatteryReportWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        BatteryStatus status = BatteryStatus.read(getApplicationContext());
        if (status == null) {
            return Result.retry();
        }

        Task<Void> write = DeviceIdentity.updateBatteryStatus(
                getApplicationContext(), status.levelPct, status.health, status.tempC);
        try {
            Tasks.await(write, 10, TimeUnit.SECONDS);
            return Result.success();
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            Log.w(TAG, "Failed to report battery status", e);
            return Result.retry();
        }
    }
}
