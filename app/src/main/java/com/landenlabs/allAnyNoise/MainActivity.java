// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;

import com.landenlabs.allAnyNoise.battery.BatteryStatus;
import com.landenlabs.allAnyNoise.history.HistoryFragment;
import com.landenlabs.allAnyNoise.listen.ListenFragment;
import com.landenlabs.allAnyNoise.subscribe.SubscriptionsFragment;
import com.landenlabs.allAnyNoise.users.UsersFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), result -> {
                // Nothing further to do here; ListenFragment re-checks permissions before starting.
            });

    private TextView batteryPercentView;

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateBatteryPercent();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        batteryPercentView = findViewById(R.id.toolbar_battery_percent);

        DeviceIdentity.registerDevice(this);
        requestRuntimePermissions();
        maybePromptBatteryOptimizationExemption();

        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            Fragment fragment;
            if (item.getItemId() == R.id.nav_subscriptions) {
                fragment = new SubscriptionsFragment();
            } else if (item.getItemId() == R.id.nav_users) {
                fragment = new UsersFragment();
            } else if (item.getItemId() == R.id.nav_history) {
                fragment = new HistoryFragment();
            } else {
                fragment = new ListenFragment();
            }
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .commit();
            return true;
        });

        if (savedInstanceState == null) {
            bottomNav.setSelectedItemId(R.id.nav_listen);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateBatteryPercent();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
    }

    @Override
    protected void onPause() {
        unregisterReceiver(batteryReceiver);
        super.onPause();
    }

    private void updateBatteryPercent() {
        BatteryStatus batteryStatus = BatteryStatus.read(this);
        if (batteryStatus != null && batteryStatus.levelPct >= 0) {
            batteryPercentView.setText(getString(R.string.toolbar_battery_percent, batteryStatus.levelPct));
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void requestRuntimePermissions() {
        List<String> needed = new ArrayList<>();
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        if (!needed.isEmpty()) {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void maybePromptBatteryOptimizationExemption() {
        PowerManager powerManager = getSystemService(PowerManager.class);
        if (powerManager == null || powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.battery_optimization_title)
                .setMessage(R.string.battery_optimization_message)
                .setPositiveButton(R.string.battery_optimization_action, (dialog, which) -> {
                    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                    intent.setData(Uri.parse("package:" + getPackageName()));
                    startActivity(intent);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
