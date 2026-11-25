package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.os.Build;

import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.TreeMap;

/**
 * PC tools > Send logs to PC: TunaReceive's recent log, with this TV's details and settings,
 * kept on the PC where TunaStream's Troubleshooting page shows it. No cable or adb needed.
 * Android only lets an app read its own log, so nothing from other apps is included.
 */
public final class SendLogs {
    private SendLogs() {}

    private static final int MAX_CHARS = 1_500_000;

    public static void send(final Activity a, final ComputerDetails computer, final String uniqueId) {
        final AlertDialog progress = UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(R.string.send_logs_title)
                .setMessage(R.string.send_logs_sending)
                .setCancelable(false)
                .show());
        new Thread(() -> {
            String message;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(a));
                String answer = http.sendTunaLog(collect(a));
                message = new JSONObject(answer).optBoolean("saved")
                        ? a.getString(R.string.send_logs_done, computer.name)
                        : a.getString(R.string.send_logs_failed);
            } catch (FileNotFoundException e) {
                message = a.getString(R.string.send_logs_needs_update);
            } catch (Exception e) {
                LimeLog.warning("Sending logs failed: " + e);
                message = a.getString(R.string.send_logs_failed);
            }
            final String shown = message;
            a.runOnUiThread(() -> {
                if (progress.isShowing()) {
                    progress.dismiss();
                }
                if (a.isFinishing()) {
                    return;
                }
                UiHelper.styleDialog(new AlertDialog.Builder(a)
                        .setTitle(R.string.send_logs_title)
                        .setMessage(shown)
                        .setPositiveButton(android.R.string.ok, null)
                        .show());
            });
        }, "TunaReceive send logs").start();
    }

    private static String collect(Activity a) {
        StringBuilder out = new StringBuilder();
        out.append("TunaReceive ").append(BuildConfig.VERSION_NAME).append(" (").append(BuildConfig.VERSION_CODE).append(")\n");
        out.append("Device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        out.append("Sent: ").append(new java.util.Date()).append("\n\n");

        // The settings, without anything private (pairing keys and the like aren't preferences)
        out.append("Settings:\n");
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(a);
        for (Map.Entry<String, ?> e : new TreeMap<>(prefs.getAll()).entrySet()) {
            String key = e.getKey();
            String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("pass") || lower.contains("token") || lower.contains("secret") || lower.contains("cert")) {
                continue;
            }
            String value = String.valueOf(e.getValue());
            if (value.length() > 200) {
                value = value.substring(0, 200) + "…";
            }
            out.append("  ").append(key).append(" = ").append(value).append('\n');
        }
        out.append("\nLog:\n");

        StringBuilder log = new StringBuilder();
        try {
            String[] command = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                    ? new String[]{"logcat", "-d", "-v", "threadtime", "-t", "5000", "--pid", String.valueOf(android.os.Process.myPid())}
                    : new String[]{"logcat", "-d", "-v", "threadtime", "-t", "5000"};
            Process p = Runtime.getRuntime().exec(command);
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    log.append(line).append('\n');
                }
            }
            p.destroy();
        } catch (Exception e) {
            log.append("(The log couldn't be read: ").append(e).append(")\n");
        }
        // Keep the newest part when it's long
        if (log.length() > MAX_CHARS) {
            log.delete(0, log.length() - MAX_CHARS);
        }
        out.append(log);
        return out.toString();
    }
}
