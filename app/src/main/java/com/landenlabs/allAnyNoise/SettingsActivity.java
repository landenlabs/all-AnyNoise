package com.landenlabs.allAnyNoise;

import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.Toolbar;
import androidx.core.content.ContextCompat;

import com.google.android.material.switchmaterial.SwitchMaterial;
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

public class SettingsActivity extends AppCompatActivity {

    // Google Public DNS's IPv6 anycast address — a literal IP, so connecting
    // to it exercises the IPv6 route without waiting on a DNS lookup first.
    private static final String IPV6_PROBE_HOST = "2001:4860:4860::8888";
    private static final int IPV6_PROBE_PORT = 53;
    private static final int IPV6_PROBE_TIMEOUT_MS = 3000;

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

        Button buttonViewSheet = findViewById(R.id.button_view_sheet);
        buttonViewSheet.setOnClickListener(v ->
                startActivity(new Intent(this, SheetViewActivity.class)));

        tvNetworkType = findViewById(R.id.tv_network_type);
        tvIpv6Address = findViewById(R.id.tv_network_ipv6_address);
        tvIpv6Status = findViewById(R.id.tv_network_ipv6_status);
        Button buttonRetestNetwork = findViewById(R.id.button_retest_network);
        buttonRetestNetwork.setOnClickListener(v -> checkNetworkStatus());

        checkNetworkStatus();
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
