// ----------------------------------------------------------------------
// Copyright (c) 2026 LanDen Labs - Dennis Lang
// https://landenlabs.com
// ----------------------------------------------------------------------
package com.landenlabs.allAnyNoise;

import static org.junit.Assert.fail;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.gms.tasks.Tasks;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.messaging.FirebaseMessaging;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Socket;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Runs on-device to pin down exactly where connectivity breaks when a
 * device silently fails to appear in the `devices` Firestore collection:
 * DNS, raw TLS to Google's edge, or the actual Firestore RPC. Run with:
 *   ./gradlew connectedAndroidTest
 * or in Android Studio: right-click this file -> Run.
 * Each test failure message states which layer failed and why.
 */
@RunWith(AndroidJUnit4.class)
public class FirestoreConnectivityTest {

    private static final String HOST = "firestore.googleapis.com";
    private static final int PORT = 443;
    private static final long TIMEOUT_SECONDS = 15;

    @Test
    public void dnsResolvesFirestoreHost() {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(HOST);
            if (addresses.length == 0) {
                fail("DNS resolved " + HOST + " to zero addresses");
            }
        } catch (Exception e) {
            fail("DNS lookup for " + HOST + " failed: " + e);
        }
    }

    @Test
    public void rawTlsHandshakeToFirestoreHostSucceeds() {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(HOST, PORT), 10_000);
            SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
            try (SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                    socket, HOST, PORT, true)) {
                sslSocket.setSoTimeout(10_000);
                sslSocket.startHandshake();
            }
        } catch (Exception e) {
            fail("Raw TCP+TLS connection to " + HOST + ":" + PORT + " failed "
                    + "(this points to a network-level block - firewall, proxy, "
                    + "or DNS-based filtering upstream of the app): " + e);
        }
    }

    /**
     * Distinguishes "no connectivity at all" from a dual-stack IPv6 blackhole:
     * the device advertises a working IPv6 route (RA from the router) but
     * that route doesn't actually reach the internet, so every IPv6-first
     * connection attempt hangs for the full OS connect timeout before any
     * IPv4 fallback kicks in. One-shot calls (dnsResolvesFirestoreHost,
     * firestoreReadSucceeds) can survive that hang inside their timeout
     * window; Firestore's long-lived Watch stream and FCM's persistent
     * connection cannot, and repeatedly die with "Channel shutdownNow
     * invoked" in logcat as they keep retrying the same dead IPv6 path.
     */
    @Test
    public void ipv6RouteIsNotABlackhole() {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(HOST);
        } catch (Exception e) {
            fail("DNS lookup for " + HOST + " failed: " + e);
            return;
        }

        boolean ipv6Reachable = false;
        boolean ipv6Attempted = false;
        boolean ipv4Reachable = false;
        boolean ipv4Attempted = false;

        for (InetAddress address : addresses) {
            boolean isIpv6 = address instanceof Inet6Address;
            if (isIpv6 && ipv6Attempted) {
                continue;
            }
            if (!isIpv6 && ipv4Attempted) {
                continue;
            }
            boolean reachable = canConnect(address);
            if (isIpv6) {
                ipv6Attempted = true;
                ipv6Reachable = reachable;
            } else {
                ipv4Attempted = true;
                ipv4Reachable = reachable;
            }
        }

        if (ipv6Attempted && !ipv6Reachable) {
            if (ipv4Reachable) {
                fail("IPv6 blackhole detected: " + HOST + " has a routable IPv6 "
                        + "address but a TCP connection to it never completes, "
                        + "while IPv4 connects fine. The device's IPv6 default "
                        + "route (likely from the Wi-Fi router's Router "
                        + "Advertisement) doesn't actually reach the internet. "
                        + "This breaks long-lived connections (Firestore listeners, "
                        + "FCM) even though one-shot calls may still succeed by "
                        + "surviving the hang. Fix the router/ISP's IPv6 uplink, "
                        + "or reconnect Wi-Fi with a static IP config to suppress "
                        + "the bad IPv6 route.");
            } else {
                fail("Neither IPv4 nor IPv6 could connect to " + HOST
                        + " - this is a full network block, not an IPv6-specific issue.");
            }
        }
    }

    private boolean canConnect(InetAddress address) {
        try (Socket socket = new Socket()) {
            socket.connect(new java.net.InetSocketAddress(address, PORT), 5_000);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    public void firestoreReadSucceeds() {
        FirebaseFirestore db = FirebaseFirestore.getInstance();
        try {
            Tasks.await(
                    db.collection("devices").limit(1).get(),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            fail("Firestore read did not complete within " + TIMEOUT_SECONDS
                    + "s - raw TLS to the host may work but the Firestore gRPC "
                    + "stream never established (matches 'Channel shutdownNow "
                    + "invoked' in logcat). Likely a mid-stream filter (DPI, "
                    + "SNI-based blocking) rather than a full network block.");
        } catch (ExecutionException e) {
            fail("Firestore read failed: " + e.getCause());
        } catch (Exception e) {
            fail("Firestore read failed: " + e);
        }
    }

    @Test
    public void fcmTokenFetchSucceeds() {
        try {
            String token = Tasks.await(
                    FirebaseMessaging.getInstance().getToken(),
                    TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (token == null || token.isEmpty()) {
                fail("FCM returned an empty token");
            }
        } catch (TimeoutException e) {
            fail("FCM token fetch did not complete within " + TIMEOUT_SECONDS + "s");
        } catch (ExecutionException e) {
            fail("FCM token fetch failed: " + e.getCause());
        } catch (Exception e) {
            fail("FCM token fetch failed: " + e);
        }
    }
}
