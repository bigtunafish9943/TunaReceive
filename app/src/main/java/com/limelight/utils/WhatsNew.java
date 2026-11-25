package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

/**
 * After an update, once: what changed since the version this TV last ran, from the release
 * notes on GitHub. The first start after installing only remembers the version.
 */
public final class WhatsNew {
    private WhatsNew() {}

    private static final String PREF_SEEN = "tuna_whatsnew_seen_code";
    private static final String RELEASES = "https://api.github.com/repos/bigtunafish9943/TunaReceive/releases?per_page=20";
    private static boolean checkedThisRun;

    public static void maybeShow(final Activity a) {
        if (checkedThisRun) {
            return;
        }
        checkedThisRun = true;
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(a);
        final int current = BuildConfig.VERSION_CODE;
        final int seen = prefs.getInt(PREF_SEEN, 0);
        if (seen == 0) {
            prefs.edit().putInt(PREF_SEEN, current).apply();
            return;
        }
        if (seen >= current) {
            return;
        }
        new Thread(() -> {
            final StringBuilder text = new StringBuilder();
            try {
                HttpURLConnection conn = TunaUpdater.open(RELEASES);
                String body;
                try (InputStream in = conn.getInputStream()) {
                    body = new String(readAll(in), StandardCharsets.UTF_8);
                } finally {
                    conn.disconnect();
                }
                JSONArray releases = new JSONArray(body);
                int shown = 0;
                for (int i = 0; i < releases.length() && shown < 8; i++) {
                    JSONObject r = releases.getJSONObject(i);
                    if (r.optBoolean("draft") || r.optBoolean("prerelease")) {
                        continue;
                    }
                    int code = TunaUpdater.versionCodeFromTag(r.optString("tag_name", ""));
                    if (code <= seen || code > current) {
                        continue;
                    }
                    String notes = clean(r.optString("body", ""));
                    if (notes.isEmpty()) {
                        continue;
                    }
                    text.append(r.optString("tag_name").replaceFirst("^v", "")).append('\n').append(notes).append("\n\n");
                    shown++;
                }
            } catch (Exception e) {
                // No GitHub just now: try again next time TunaReceive starts
                LimeLog.info("What's new: " + e);
                checkedThisRun = false;
                return;
            }
            a.runOnUiThread(() -> {
                prefs.edit().putInt(PREF_SEEN, current).apply();
                if (text.length() == 0 || a.isFinishing()) {
                    return;
                }
                UiHelper.styleDialog(new AlertDialog.Builder(a)
                        .setTitle(a.getString(R.string.whatsnew_title, BuildConfig.VERSION_NAME))
                        .setMessage(text.toString().trim())
                        .setPositiveButton(R.string.whatsnew_ok, null)
                        .show());
            });
        }, "TunaReceive what's new").start();
    }

    /** The notes without the lines that only say who made the change. */
    private static String clean(String body) {
        StringBuilder out = new StringBuilder();
        for (String line : body.split("\n")) {
            if (line.startsWith("Co-Authored-By:") || line.startsWith("Claude-Session:")) {
                continue;
            }
            out.append(line).append('\n');
        }
        String s = out.toString().trim();
        return s.length() > 1500 ? s.substring(0, 1500) + "…" : s;
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
