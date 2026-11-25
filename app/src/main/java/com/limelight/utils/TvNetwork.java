package com.limelight.utils;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;

/** How this TV is connected: wired, or Wi-Fi with its band, signal and link speed. */
public final class TvNetwork {
    public static final String WIRED = "wired";
    public static final String WIFI = "wifi";
    public static final String UNKNOWN = "unknown";

    public String kind = UNKNOWN;
    public String band = "";  // "2.4 GHz", "5 GHz" or "6 GHz"
    public int signal;  // 0 to 100
    public int rssi;  // dBm
    public int linkMbps;

    private TvNetwork() {}

    public static TvNetwork read(Context context) {
        TvNetwork n = new TvNetwork();
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && cm != null) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) {
                    n.kind = WIRED;
                    return n;
                }
            }
            WifiManager wifi = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            WifiInfo info = wifi != null ? wifi.getConnectionInfo() : null;
            if (info == null || info.getNetworkId() == -1) {
                return n;
            }
            n.kind = WIFI;
            n.rssi = info.getRssi();
            n.signal = WifiManager.calculateSignalLevel(info.getRssi(), 101);
            n.linkMbps = info.getLinkSpeed();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                int mhz = info.getFrequency();
                n.band = mhz >= 5925 ? "6 GHz" : mhz >= 4900 ? "5 GHz" : "2.4 GHz";
            }
        } catch (Exception e) {
            n.kind = UNKNOWN;
        }
        return n;
    }
}
