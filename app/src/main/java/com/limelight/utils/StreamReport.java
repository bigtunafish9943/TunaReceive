package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * How the last stream went, in plain words. While a stream runs this tallies, once a second, how
 * the picture arrived and what picture quality that adjusts itself did about it. When the stream
 * ends the tally is saved, and the PC's menu shows it: so a game that felt rough can be looked at
 * afterwards, instead of watching numbers on top of the game.
 *
 * It sits between the decoder and AdaptiveQuality, passing every reading on unchanged.
 *
 * Every five seconds it also tells TunaStream what this device sees, so the PC's stream health
 * shows both ends side by side. A PC whose TunaStream doesn't know about that answers "not
 * found", and it stops sending.
 */
public final class StreamReport implements MediaCodecDecoderRenderer.StreamHealthListener {
    private static final String PREF = "tuna_stream_reports";
    private static final int KEEP = 5;
    // A second counts as reduced quality when the bitrate is below this share of the setting
    private static final double REDUCED_SHARE = 0.95;

    private static final int REPORT_EVERY_SECONDS = 5;

    private final MediaCodecDecoderRenderer.StreamHealthListener next;
    private final NvHTTP http;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();
    private volatile boolean hostListens = true;

    /** Messages and sounds sent from TunaStream's home page, carried by the answers to the reports. */
    public interface PcMessageListener {
        /** Called on the report thread. text may be empty when only a sound was sent. */
        void onPcMessage(long id, String text, String sound);
    }

    private volatile PcMessageListener messageListener;
    private long lastMessageId;

    public void setPcMessageListener(PcMessageListener listener) {
        this.messageListener = listener;
    }

    // The last few seconds, for the report to the PC
    private int recentSeconds;
    private float recentLoss;
    private float recentSlow;
    private float recentFps;
    private float recentDecode;

    // The PC's side of the stream, from its answers to the reports (newer TunaStream only)
    private int pcSamples;
    private int gameSlowSamples;
    private double gameSlowFpsSum;
    private double pcMsSum;
    private double pcMsWorst;
    private double pcSkippedSum;
    private int fecSum;
    private boolean pcSplit;
    private volatile MediaCodecDecoderRenderer decoder;
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

    public StreamReport(NvHTTP http, MediaCodecDecoderRenderer.StreamHealthListener next, AdaptiveQuality quality,
                        String pcName, String appName, int width, int height, int fps, int settingKbps,
                        int reconnectAttempt) {
        this.http = http;
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

    // ---------- Live figures, for the stream details overlay ----------
    private final float[] lateHistory = new float[30];
    private int lateHistoryCount;
    private volatile float liveFps, liveLoss, liveSlow, liveDecode;
    private double fpsSum;
    private int fpsSeconds;
    private int lossSeconds;
    private volatile JSONObject latestPc;

    /** Figures for the overlay: now, the stream's average, the last 30 seconds and the PC's latest. */
    public static final class Live {
        public float fps, averageFps, lossPct, latePct, latePct30, decodeMs;
        public int targetFps, width, height, lossSeconds, kbps, settingKbps, scalePercent;
        public JSONObject pc;
    }

    public synchronized Live live() {
        Live l = new Live();
        l.fps = liveFps;
        l.averageFps = fpsSeconds > 0 ? (float) (fpsSum / fpsSeconds) : 0;
        l.lossPct = liveLoss;
        l.latePct = liveSlow;
        float sum = 0;
        int n = Math.min(lateHistoryCount, lateHistory.length);
        for (int i = 0; i < n; i++) {
            sum += lateHistory[i];
        }
        l.latePct30 = n > 0 ? sum / n : 0;
        l.decodeMs = liveDecode;
        l.targetFps = fps;
        l.width = width;
        l.height = height;
        l.lossSeconds = lossSeconds;
        l.kbps = quality != null ? quality.currentKbps() : settingKbps;
        l.settingKbps = settingKbps;
        l.scalePercent = quality != null ? quality.scalePercent() : 100;
        l.pc = latestPc;
        return l;
    }

    private synchronized void keepLive(float lossPct, float receivedFps, float slowFramePct, float decodeMs) {
        liveFps = receivedFps;
        liveLoss = lossPct;
        liveSlow = slowFramePct;
        liveDecode = decodeMs;
        if (receivedFps > 0) {
            fpsSum += receivedFps;
            fpsSeconds++;
        }
        if (lossPct > 0) {
            lossSeconds++;
        }
        lateHistory[lateHistoryCount % lateHistory.length] = slowFramePct;
        lateHistoryCount++;
    }

    @Override
    public void onStreamHealth(float lossPct, float receivedFps, float slowFramePct, float decodeMs) {
        if (next != null) {
            next.onStreamHealth(lossPct, receivedFps, slowFramePct, decodeMs);
        }
        keepLive(lossPct, receivedFps, slowFramePct, decodeMs);
        tally(lossPct, slowFramePct);
        tellPc(lossPct, receivedFps, slowFramePct, decodeMs);
    }

    private volatile int hdrPeakNits;

    /** How bright the TV says it can go in HDR, for the PC (0 when it doesn't say). */
    public void setHdrPeak(android.view.Display display) {
        if (display == null || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.N) {
            return;
        }
        android.view.Display.HdrCapabilities hdr = display.getHdrCapabilities();
        if (hdr != null && hdr.getSupportedHdrTypes().length > 0) {
            float peak = hdr.getDesiredMaxLuminance();
            if (peak > 0 && peak < 10000) {
                hdrPeakNits = Math.round(peak);
            }
        }
    }

    /** The decoder whose delay figures go into the reports to the PC. */
    public void setDecoder(MediaCodecDecoderRenderer decoder) {
        this.decoder = decoder;
    }

    private void tellPc(float lossPct, float receivedFps, float slowFramePct, float decodeMs) {
        if (http == null || !hostListens || finished) {
            return;
        }
        recentSeconds++;
        recentLoss += lossPct;
        recentSlow += slowFramePct;
        recentFps += receivedFps;
        recentDecode += decodeMs;
        if (recentSeconds < REPORT_EVERY_SECONDS) {
            return;
        }
        final String query = String.format(Locale.ROOT,
                "loss=%.2f&slow=%.1f&fps=%.1f&decode=%.2f&latency=%d&kbps=%d&setting=%d&hdrpeak=%d",
                recentLoss / recentSeconds, recentSlow / recentSeconds, recentFps / recentSeconds,
                recentDecode / recentSeconds, decoder != null ? decoder.getAverageEndToEndLatency() : 0,
                quality != null ? quality.currentKbps() : settingKbps, settingKbps, hdrPeakNits);
        recentSeconds = 0;
        recentLoss = recentSlow = recentFps = recentDecode = 0;
        try {
            sender.execute(() -> {
                try {
                    String answer = http.sendTunaReport(query);
                    JSONObject json = answer != null ? new JSONObject(answer) : null;
                    JSONObject pc = json != null ? json.optJSONObject("pc") : null;
                    if (pc != null) {
                        keepPc(pc);
                    }
                    org.json.JSONArray messages = json != null ? json.optJSONArray("messages") : null;
                    PcMessageListener listener = messageListener;
                    if (messages != null && listener != null) {
                        for (int i = 0; i < messages.length(); i++) {
                            JSONObject m = messages.optJSONObject(i);
                            if (m == null) {
                                continue;
                            }
                            long id = m.optLong("id", 0);
                            // Shown once each, even if the PC sends one again
                            if (id != 0 && id <= lastMessageId) {
                                continue;
                            }
                            lastMessageId = Math.max(lastMessageId, id);
                            listener.onPcMessage(id, m.optString("text", ""), m.optString("sound", ""));
                        }
                    }
                } catch (FileNotFoundException e) {
                    hostListens = false;
                } catch (Exception e) {
                    // A missed report doesn't matter; the next one comes in five seconds
                }
            });
        } catch (RejectedExecutionException e) {
            // The stream is closing
        }
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

    private synchronized void keepPc(JSONObject pc) {
        latestPc = pc;
        if (finished) {
            return;
        }
        pcSamples++;
        pcMsSum += pc.optDouble("pcMsAvg", 0);
        pcMsWorst = Math.max(pcMsWorst, pc.optDouble("pcMsMax", 0));
        pcSkippedSum += pc.optDouble("skippedPerSec", 0);
        fecSum += pc.optInt("fecPercent", 0);
        pcSplit |= pc.optBoolean("splitFrames", false);
        // The game itself drawing too few frames: the PC had none to spare
        double pcFps = pc.optDouble("fps", 0);
        if (fps >= 60 && pcFps >= 20 && pcFps < 59 && pc.optDouble("skippedPerSec", 0) < 1) {
            gameSlowSamples++;
            gameSlowFpsSum += pcFps;
        }
    }

    /** The stream dropped with this error, rather than being closed. */
    public void setDropped(int errorCode) {
        ending = errorCode == -1 ? "dropped" : "error " + errorCode;
    }

    /**
     * Saves the report. Call once, as the stream closes, while the decoder's figures are still there.
     * @return true when the stream was closed normally but had enough trouble to be worth a look
     */
    public synchronized boolean finish(Context context, MediaCodecDecoderRenderer decoder) {
        if (finished) {
            return false;
        }
        finished = true;
        sender.shutdownNow();
        if (seconds < 10) {
            // A stream that never got going says nothing useful
            return false;
        }
        boolean troubled = ending == null && seconds >= 60 &&
                (losingSeconds * 100 >= seconds * 5 || reducedSeconds * 100 >= seconds * 30);
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
            if (pcSamples > 0) {
                r.put("pcMs", pcMsSum / pcSamples);
                r.put("pcMsWorst", pcMsWorst);
                r.put("pcSkipped", pcSkippedSum / pcSamples);
                r.put("fecPercent", fecSum / pcSamples);
                r.put("pcSplit", pcSplit);
                if (gameSlowSamples * 2 >= pcSamples && gameSlowSamples >= 6) {
                    r.put("gameFps", gameSlowFpsSum / gameSlowSamples);
                }
            }

            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            JSONArray old = new JSONArray(prefs.getString(PREF, "[]"));
            JSONArray kept = new JSONArray();
            kept.put(r);
            for (int i = 0; i < old.length() && kept.length() < KEEP; i++) {
                kept.put(old.get(i));
            }
            prefs.edit().putString(PREF, kept.toString()).apply();
            keepGameTip(prefs, r);
        } catch (JSONException e) {
            LimeLog.warning("Couldn't save the stream report: " + e);
            return false;
        }
        return troubled;
    }

    private static final String TIPS_PREF = "tuna_game_tips";

    /** Remembers, per game, what the last long enough stream of it suggests, for next time. */
    private static void keepGameTip(SharedPreferences prefs, JSONObject r) throws JSONException {
        int seconds = r.optInt("seconds");
        String app = r.optString("app");
        if (seconds < 120 || app.isEmpty()) {
            return;
        }
        JSONObject tips = new JSONObject(prefs.getString(TIPS_PREF, "{}"));
        JSONObject t = new JSONObject();
        t.put("at", r.optLong("at"));
        t.put("width", r.optInt("width"));
        t.put("fps", r.optInt("fps"));
        if (r.has("gameFps")) {
            t.put("gameFps", r.optDouble("gameFps"));
        }
        t.put("smallerShare", r.optInt("smallerSeconds") * 100 / seconds);
        t.put("reducedShare", r.optInt("reducedSeconds") * 100 / seconds);
        tips.put(r.optString("pc") + "|" + app, t);
        prefs.edit().putString(TIPS_PREF, tips.toString()).apply();
    }

    /**
     * A suggestion for this game from how it went last time, or null when it went fine. Only a
     * suggestion: nothing is changed.
     */
    public static String tipFor(Context c, String pcName, String appName) {
        try {
            JSONObject tips = new JSONObject(PreferenceManager.getDefaultSharedPreferences(c).getString(TIPS_PREF, "{}"));
            JSONObject t = tips.optJSONObject(pcName + "|" + appName);
            if (t == null) {
                return null;
            }
            if (t.has("gameFps")) {
                return c.getString(R.string.tip_game_fps, appName, Math.round(t.optDouble("gameFps")), t.optInt("fps"));
            }
            if (t.optInt("width") >= 3840 && t.optInt("smallerShare") >= 20) {
                return c.getString(R.string.tip_smaller, appName, t.optInt("smallerShare"));
            }
            if (t.optInt("reducedShare") >= 30) {
                return c.getString(R.string.tip_reduced, appName, t.optInt("reducedShare"));
            }
        } catch (JSONException e) {
            LimeLog.warning("Couldn't read the game tips: " + e);
        }
        return null;
    }

    /** Shows the most recent report, from the PC's menu, with a button to the one before. */
    public static void show(Activity activity) {
        JSONArray all = new JSONArray();
        try {
            all = new JSONArray(PreferenceManager.getDefaultSharedPreferences(activity).getString(PREF, "[]"));
        } catch (JSONException e) {
            LimeLog.warning("Couldn't read the stream reports: " + e);
        }
        show(activity, all, 0);
    }

    /** Shows report number index (0 is the newest). Earlier and Later step through the rest. */
    private static void show(Activity activity, JSONArray all, int index) {
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setPositiveButton(android.R.string.ok, null);
        JSONObject r = all.optJSONObject(index);
        if (r == null) {
            dialog.setTitle(R.string.report_title).setMessage(R.string.report_none);
        } else {
            dialog.setTitle(index == 0 ? activity.getString(R.string.report_title)
                    : activity.getString(R.string.report_title_older, index + 1, all.length()))
                    .setMessage(describe(activity, r));
            if (index + 1 < all.length()) {
                dialog.setNeutralButton(R.string.report_earlier, (d, w) -> show(activity, all, index + 1));
            }
            if (index > 0) {
                dialog.setNegativeButton(R.string.report_later, (d, w) -> show(activity, all, index - 1));
            }
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
        if (r.has("pcMs") && r.optDouble("pcMs") > 800.0 / fps) {
            sb.append(c.getString(R.string.report_pc_slow, Math.round(r.optDouble("pcMs")))).append("\n\n");
            anything = true;
        }
        if (r.has("gameFps")) {
            sb.append(c.getString(R.string.report_game_slow, Math.round(r.optDouble("gameFps")), fps)).append("\n\n");
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
        if (r.has("pcMs")) {
            sb.append("\n").append(c.getString(r.optBoolean("pcSplit") ? R.string.report_pc_figures_split : R.string.report_pc_figures,
                    String.format(java.util.Locale.ROOT, "%.1f", r.optDouble("pcMs")),
                    String.format(java.util.Locale.ROOT, "%.1f", r.optDouble("pcMsWorst")),
                    r.optInt("fecPercent")));
        }
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
