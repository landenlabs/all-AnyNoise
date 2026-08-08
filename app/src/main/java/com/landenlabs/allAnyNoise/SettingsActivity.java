// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
import com.landenlabs.allAnyNoise.battery.BatteryReportScheduler;
import com.landenlabs.allAnyNoise.history.SheetViewActivity;

import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class SettingsActivity extends AppCompatActivity {

    private static final String GITHUB_URL = "https://github.com/landenlabs/all-AnyNoise";

    // Google Public DNS's IPv6 anycast address — a literal IP, so connecting
    // to it exercises the IPv6 route without waiting on a DNS lookup first.
    private static final String IPV6_PROBE_HOST = "2001:4860:4860::8888";
    private static final int IPV6_PROBE_PORT = 53;
    private static final int IPV6_PROBE_TIMEOUT_MS = 3000;

    // Threshold bounds for the sensitivity sliders below — mirror the inverted
    // amplitude-threshold mapping used for audio sensitivity in ListenFragment
    // (higher slider progress = more sensitive = lower threshold).
    private static final int MIN_THRESHOLD_AMPLITUDE = 800;
    private static final int MAX_THRESHOLD_AMPLITUDE = 8000;
    private static final int MIN_THRESHOLD_LUX = 10;
    private static final int MAX_THRESHOLD_LUX = 100;
    private static final int MIN_THRESHOLD_VIBRATION = 1;
    private static final int MAX_THRESHOLD_VIBRATION = 8;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView tvNetworkType;
    private TextView tvIpv6Address;
    private TextView tvIpv6Status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        SwitchMaterial switchDarkTheme = findViewById(R.id.switch_dark_theme);
        switchDarkTheme.setChecked(Prefs.isDarkThemeEnabled(this));
        switchDarkTheme.setOnCheckedChangeListener((buttonView, checked) -> {
            Prefs.setDarkThemeEnabled(this, checked);
            AppCompatDelegate.setDefaultNightMode(
                    checked ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_NO);
            recreate();
        });

        setUpBatteryIntervalSpinner();
        setUpSensitivitySeekBar(R.id.seek_audio_sensitivity, MIN_THRESHOLD_AMPLITUDE, MAX_THRESHOLD_AMPLITUDE,
                () -> Prefs.getThresholdAmplitude(this),
                threshold -> Prefs.setThresholdAmplitude(this, threshold));
        setUpSensitivitySeekBar(R.id.seek_light_sensitivity, MIN_THRESHOLD_LUX, MAX_THRESHOLD_LUX,
                () -> Prefs.getLightSensitivityThresholdLux(this),
                threshold -> Prefs.setLightSensitivityThresholdLux(this, threshold));
        setUpSensitivitySeekBar(R.id.seek_vibration_sensitivity, MIN_THRESHOLD_VIBRATION, MAX_THRESHOLD_VIBRATION,
                () -> Prefs.getVibrationSensitivityThreshold(this),
                threshold -> Prefs.setVibrationSensitivityThreshold(this, threshold));

        Button buttonViewSheet = findViewById(R.id.button_view_sheet);
        buttonViewSheet.setOnClickListener(v ->
                startActivity(new Intent(this, SheetViewActivity.class)));

        tvNetworkType = findViewById(R.id.tv_network_type);
        tvIpv6Address = findViewById(R.id.tv_network_ipv6_address);
        tvIpv6Status = findViewById(R.id.tv_network_ipv6_status);
        Button buttonRetestNetwork = findViewById(R.id.button_retest_network);
        buttonRetestNetwork.setOnClickListener(v -> checkNetworkStatus());

        checkNetworkStatus();
        setUpAbout();
    }

    private void setUpAbout() {
        TextView tvAboutVersion = findViewById(R.id.tv_about_version);
        tvAboutVersion.setText(getString(R.string.about_version, BuildConfig.VERSION_NAME));

        Button buttonAboutGithub = findViewById(R.id.button_about_github);
        buttonAboutGithub.setOnClickListener(v ->
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL))));
    }

    private void setUpBatteryIntervalSpinner() {
        int[] hourValues = getResources().getIntArray(R.array.battery_interval_hours);
        Spinner spinner = findViewById(R.id.spinner_battery_interval);

        int currentHours = Prefs.getBatteryReportIntervalHours(this);
        int selection = 0;
        for (int i = 0; i < hourValues.length; i++) {
            if (hourValues[i] == currentHours) {
                selection = i;
                break;
            }
        }
        spinner.setSelection(selection);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, android.view.View view, int position, long id) {
                int hours = hourValues[position];
                Prefs.setBatteryReportIntervalHours(SettingsActivity.this, hours);
                BatteryReportScheduler.schedule(SettingsActivity.this, hours);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private void setUpSensitivitySeekBar(int seekBarId, int minThreshold, int maxThreshold,
                                          IntSupplier getThreshold, IntConsumer saveThreshold) {
        SeekBar seekBar = findViewById(seekBarId);

        int savedThreshold = getThreshold.getAsInt();
        int seekValue = Math.round((maxThreshold - savedThreshold) * 9f / (maxThreshold - minThreshold));
        seekBar.setProgress(Math.max(0, Math.min(9, seekValue)));

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                int threshold = maxThreshold
                        - Math.round(seekBar.getProgress() * (maxThreshold - minThreshold) / 9f);
                saveThreshold.accept(threshold);
            }
        });
    }

    private void checkNetworkStatus() {
        tvNetworkType.setText(getString(R.string.network_status_connection, describeActiveNetwork()));
        tvIpv6Address.setText(describeDeviceIpv6Address());
        tvIpv6Status.setTextColor(getTextColor());
        tvIpv6Status.setText(R.string.network_status_ipv6_checking);

        executor.execute(() -> {
            Ipv6ProbeResult result = probeIpv6();
            runOnUiThread(() -> showIpv6ProbeResult(result));
        });
    }

    private void showIpv6ProbeResult(Ipv6ProbeResult result) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        if (result.reachable) {
            tvIpv6Status.setTextColor(ContextCompat.getColor(this, R.color.anynoise_status_ok));
            tvIpv6Status.setText(getString(R.string.network_status_ipv6_reachable, result.elapsedMs));
        } else {
            tvIpv6Status.setTextColor(ContextCompat.getColor(this, R.color.anynoise_status_bad));
            tvIpv6Status.setText(R.string.network_status_ipv6_unreachable);
        }
    }

    private int getTextColor() {
        return tvNetworkType.getCurrentTextColor();
    }

    private String describeActiveNetwork() {
        ConnectivityManager cm = ContextCompat.getSystemService(this, ConnectivityManager.class);
        if (cm == null) {
            return getString(R.string.network_status_type_none);
        }
        Network network = cm.getActiveNetwork();
        NetworkCapabilities capabilities = network != null ? cm.getNetworkCapabilities(network) : null;
        if (capabilities == null) {
            return getString(R.string.network_status_type_none);
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return getString(R.string.network_status_type_wifi);
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return getString(R.string.network_status_type_cellular);
        } else if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
            return getString(R.string.network_status_type_ethernet);
        }
        return getString(R.string.network_status_type_other);
    }

    // Looks for a routable (non-link-local, non-loopback) IPv6 address on any
    // active interface — its presence doesn't guarantee internet reachability,
    // but its absence usually explains why one would fail.
    private String describeDeviceIpv6Address() {
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                if (!iface.isUp() || iface.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addresses = iface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (address instanceof Inet6Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()
                            && !address.isMulticastAddress()) {
                        String hostAddress = address.getHostAddress();
                        int scopeSeparator = hostAddress.indexOf('%');
                        if (scopeSeparator >= 0) {
                            hostAddress = hostAddress.substring(0, scopeSeparator);
                        }
                        return getString(R.string.network_status_ipv6_address, hostAddress);
                    }
                }
            }
        } catch (SocketException ignored) {
            // Fall through to "none detected".
        }
        return getString(R.string.network_status_ipv6_address_none);
    }

    private static Ipv6ProbeResult probeIpv6() {
        long start = System.nanoTime();
        try {
            InetAddress address = InetAddress.getByName(IPV6_PROBE_HOST);
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(address, IPV6_PROBE_PORT), IPV6_PROBE_TIMEOUT_MS);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            return new Ipv6ProbeResult(true, elapsedMs);
        } catch (IOException e) {
            return new Ipv6ProbeResult(false, 0);
        }
    }

    private static class Ipv6ProbeResult {
        final boolean reachable;
        final long elapsedMs;

        Ipv6ProbeResult(boolean reachable, long elapsedMs) {
            this.reachable = reachable;
            this.elapsedMs = elapsedMs;
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
