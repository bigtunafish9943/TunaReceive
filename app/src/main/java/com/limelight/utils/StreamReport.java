package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.video.MediaCodecDecoderRenderer;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/**
 * How the last stream went, in plain words. While a stream runs this tallies, once a second, how
 * the picture arrived and what picture quality that adjusts itself did about it. When the stream
 * ends the tally is saved, and the PC's menu shows it: so a game that felt rough can be looked at
 * afterwards, instead of watching numbers on top of the game.
 *
 * It sits between the decoder and AdaptiveQuality, passing every reading on unchanged.
 */
public final class StreamReport implements MediaCodecDecoderRenderer.StreamHealthListener {
    private static final String PREF = "tuna_stream_reports";
    private static final int KEEP = 5;
    // A second counts as reduced quality when the bitrate is below this share of the setting
    private static final double REDUCED_SHARE = 0.95;

    private final MediaCodecDecoderRenderer.StreamHealthListener next;
    private final AdaptiveQuality quality;
    private final long startedAtWallMs = System.currentTimeMillis();
    private final String pcName;
    private final String appName;
    private final int width;
    private final int height;
    private final int fps;
    private final int settingKbps;
    private final int reconnectAttempt;

    // Tally, touched only on the decoder's thread until finish()
    private int seconds;
    private int losingSeconds;
    private int slowSeconds;
    private int reducedSeconds;
    private int smallerSeconds;
    private double lossPctSum;
    private long kbpsSum;
    private int lowestKbps;
    private int smallestScale = 100;
    private int lowered;
    private int raised;
    private int sizeChanges;
    private int lastKbps;
    private int lastScale = 100;

    private volatile String ending;
    private volatile boolean finished;

    public StreamReport(MediaCodecDecoderRenderer.StreamHealthListener next, AdaptiveQuality quality,
                        String pcName, String appName, int width, int height, int fps, int settingKbps,
                        int reconnectAttempt) {
        this.next = next;
        this.quality = quality;
        this.pcName = pcName != null ? pcName : "";
        this.appName = appName != null ? appName : "";
        this.width = width;
        this.height = height;
        this.fps = Math.max(1, fps);
        this.settingKbps = settingKbps;
        this.reconnectAttempt = reconnectAttempt;
        this.lowestKbps = settingKbps;
        this.lastKbps = settingKbps;
    }

    @Override
    public void onStreamHealth(float lossPct, float receivedFps, float slowFramePct) {
        if (next != null) {
            next.onStreamHealth(lossPct, receivedFps, slowFramePct);
        }
        tally(lossPct, slowFramePct);
    }

    private synchronized void tally(float lossPct, float slowFramePct) {
        if (finished) {
            return;
        }

        seconds++;
        lossPctSum += lossPct;
        if (lossPct > 1f) {
            losingSeconds++;
        }
        if (slowFramePct >= 30f) {
            slowSeconds++;
        }

        int kbps = quality != null ? quality.currentKbps() : settingKbps;
        int scale = quality != null ? quality.scalePercent() : 100;
        kbpsSum += kbps;
        lowestKbps = Math.min(lowestKbps, kbps);
        smallestScale = Math.min(smallestScale, scale);
        if (kbps < settingKbps * REDUCED_SHARE) {
            reducedSeconds++;
        }
        if (scale < 100) {
            smallerSeconds++;
        }
        if (kbps < lastKbps) {
            lowered++;
        } else if (kbps > lastKbps) {
            raised++;
        }
        if (scale != lastScale) {
            sizeChanges++;
        }
        lastKbps = kbps;
        lastScale = scale;
    }

    /** The stream dropped with this error, rather than being closed. */
    public void setDropped(int errorCode) {
        ending = errorCode == -1 ? "dropped" : "error " + errorCode;
    }

    /** Saves the report. Call once, as the stream closes, while the decoder's figures are still there. */
    public synchronized void finish(Context context, MediaCodecDecoderRenderer decoder) {
        if (finished) {
            return;
        }
        finished = true;
        if (seconds < 10) {
            // A stream that never got going says nothing useful
            return;
        }
        try {
            JSONObject r = new JSONObject();
            r.put("at", startedAtWallMs);
            r.put("pc", pcName);
            r.put("app", appName);
            r.put("width", width);
            r.put("height", height);
            r.put("fps", fps);
            r.put("seconds", seconds);
            r.put("settingKbps", settingKbps);
            r.put("avgKbps", seconds > 0 ? (int) (kbpsSum / seconds) : settingKbps);
            r.put("lowestKbps", lowestKbps);
            r.put("smallestScale", smallestScale);
            r.put("reducedSeconds", reducedSeconds);
            r.put("smallerSeconds", smallerSeconds);
            r.put("losingSeconds", losingSeconds);
            r.put("slowSeconds", slowSeconds);
            r.put("avgLossPct", seconds > 0 ? lossPctSum / seconds : 0);
            r.put("lowered", lowered);
            r.put("raised", raised);
            r.put("sizeChanges", sizeChanges);
            r.put("reconnect", reconnectAttempt);
            r.put("ending", ending != null ? ending : "closed");
            if (decoder != null) {
                r.put("latencyMs", decoder.getAverageEndToEndLatency());
                r.put("decodeMs", decoder.getAverageDecoderLatency());
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            JSONArray old = new JSONArray(prefs.getString(PREF, "[]"));
            JSONArray kept = new JSONArray();
            kept.put(r);
            for (int i = 0; i < old.length() && kept.length() < KEEP; i++) {
                kept.put(old.get(i));
            }
            prefs.edit().putString(PREF, kept.toString()).apply();
        } catch (JSONException e) {
            LimeLog.warning("Couldn't save the stream report: " + e);
        }
    }

    /** Shows the most recent report, from the PC's menu. */
    public static void show(Activity activity) {
        JSONObject r = null;
        try {
            JSONArray all = new JSONArray(PreferenceManager.getDefaultSharedPreferences(activity).getString(PREF, "[]"));
            if (all.length() > 0) {
                r = all.getJSONObject(0);
            }
        } catch (JSONException e) {
            LimeLog.warning("Couldn't read the stream report: " + e);
        }

        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.report_title)
                .setPositiveButton(android.R.string.ok, null);
        if (r == null) {
            dialog.setMessage(R.string.report_none);
        } else {
            dialog.setMessage(describe(activity, r));
        }
        UiHelper.styleDialog(dialog.show());
    }

    /** The report as a few plain sentences: what happened first, then the figures. */
    static String describe(Context c, JSONObject r) {
        int seconds = Math.max(1, r.optInt("seconds"));
        int fps = Math.max(1, r.optInt("fps", 60));
        int setting = r.optInt("settingKbps");
        int losing = r.optInt("losingSeconds");
        int slow = r.optInt("slowSeconds");
        int reduced = r.optInt("reducedSeconds");
        int smaller = r.optInt("smallerSeconds");
        int decodeMs = r.optInt("decodeMs");
        String ending = r.optString("ending", "closed");

        StringBuilder sb = new StringBuilder();
        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(new Date(r.optLong("at")));
        String app = r.optString("app");
        sb.append(c.getString(R.string.report_header, app.isEmpty() ? c.getString(R.string.report_a_game) : app,
                r.optString("pc"), when, minutes(c, seconds)));
        sb.append("\n\n");

        // What happened, worst first
        boolean anything = false;
        if (!"closed".equals(ending)) {
            sb.append(c.getString("dropped".equals(ending) ? R.string.report_dropped : R.string.report_error, ending)).append("\n\n");
            anything = true;
        }
        if (losing * 100 >= seconds * 3) {
            sb.append(c.getString(R.string.report_losing, pct(losing, seconds))).append("\n\n");
            anything = true;
        }
        if (reduced * 100 >= seconds * 5) {
            sb.append(c.getString(R.string.report_reduced, minutes(c, reduced), minutes(c, seconds),
                    Math.round(r.optInt("lowestKbps") / 1000f), Math.round(setting / 1000f))).append("\n\n");
            anything = true;
        }
        if (smaller > 0) {
            sb.append(c.getString(R.string.report_smaller, minutes(c, smaller), r.optInt("smallestScale"))).append("\n\n");
            anything = true;
        }
        if (decodeMs > 1000 / fps) {
            sb.append(c.getString(R.string.report_decode, decodeMs)).append("\n\n");
            anything = true;
        } else if (slow * 100 >= seconds * 10 && losing * 100 < seconds * 3) {
            sb.append(c.getString(R.string.report_slow, pct(slow, seconds))).append("\n\n");
            anything = true;
        }
        if (!anything) {
            sb.append(c.getString(R.string.report_smooth)).append("\n\n");
        }

        // The figures
        sb.append(c.getString(R.string.report_figures,
                r.optInt("width") + "x" + r.optInt("height"), fps,
                Math.round(setting / 1000f), Math.round(r.optInt("avgKbps") / 1000f),
                r.optInt("lowered"), r.optInt("raised"),
                String.format(java.util.Locale.ROOT, "%.1f", r.optDouble("avgLossPct", 0)),
                r.optInt("latencyMs"), decodeMs));
        if (r.optInt("reconnect") > 0) {
            sb.append("\n").append(c.getString(R.string.report_reconnected));
        }
        return sb.toString();
    }

    private static int pct(int part, int whole) {
        return Math.max(1, Math.round(part * 100f / whole));
    }

    private static String minutes(Context c, int seconds) {
        if (seconds < 90) {
            return c.getString(R.string.report_seconds, seconds);
        }
        return c.getString(R.string.report_minutes, Math.round(seconds / 60f));
    }
}
