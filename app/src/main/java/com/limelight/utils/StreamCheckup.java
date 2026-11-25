package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;
import android.view.Display;

import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A quick check before each game (Settings: "Check settings before each game"): measures the
 * connection to the PC for about a second, looks at what this TV can show and decode and at how
 * the last stream went, and suggests settings that would play better. Each suggestion can be
 * taken or left; nothing changes unless it's chosen. A suggestion turned down isn't made again
 * for a week, unless things change enough to suggest something different. Runs from
 * ServerHelper.doStart, so every way of starting a game goes through it.
 */
public final class StreamCheckup {
    private StreamCheckup() {}

    public static final String PREF_ENABLED = "tuna_checkup_before_game";
    private static final String PREF_DECLINED = "tuna_checkup_declined";
    private static final long DECLINED_FOR_MS = 7L * 24 * 60 * 60 * 1000;

    private static final class Suggestion {
        final String key;
        final Object value;  // String, Boolean or Integer (kbps)
        final String text;   // what changes, and why

        Suggestion(String key, Object value, String text) {
            this.key = key;
            this.value = value;
            this.text = text;
        }

        String id() {
            return key + "=" + value;
        }
    }

    /** Check, offer what's worth changing, then start (always, whatever is picked). */
    public static void beforeStart(final Activity a, final ComputerDetails computer, final String uniqueId, final Runnable start) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(a);
        if (!prefs.getBoolean(PREF_ENABLED, true) || uniqueId == null || computer.serverCert == null) {
            start.run();
            return;
        }
        final boolean[] skipped = {false};
        final AlertDialog progress = UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(R.string.checkup_title)
                .setMessage(R.string.checkup_measuring)
                .setNegativeButton(R.string.checkup_skip, (d, w) -> {
                    skipped[0] = true;
                    start.run();
                })
                .setCancelable(false)
                .show());

        new Thread(() -> {
            int mbps = -1;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(a));
                mbps = http.measureDownloadMbpsQuick();
            } catch (Exception e) {
                // An older TunaStream, a game already streaming, or the network: no speed this time
                LimeLog.info("Check before the game: no speed measured: " + e);
            }
            final List<Suggestion> list = suggestions(a, mbps);
            a.runOnUiThread(() -> {
                if (skipped[0]) {
                    return;
                }
                if (progress.isShowing()) {
                    progress.dismiss();
                }
                if (a.isFinishing()) {
                    return;
                }
                if (list.isEmpty()) {
                    start.run();
                } else {
                    offer(a, list, start);
                }
            });
        }, "TunaReceive check before game").start();
    }

    private static void offer(final Activity a, final List<Suggestion> list, final Runnable start) {
        final String[] items = new String[list.size()];
        final boolean[] chosen = new boolean[list.size()];
        for (int i = 0; i < items.length; i++) {
            items[i] = list.get(i).text;
            chosen[i] = true;
        }
        UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(a.getResources().getQuantityString(R.plurals.checkup_found, list.size(), list.size()))
                .setMultiChoiceItems(items, chosen, (d, which, checked) -> chosen[which] = checked)
                .setPositiveButton(R.string.checkup_use_checked, (d, w) -> {
                    apply(a, list, chosen);
                    start.run();
                })
                .setNeutralButton(R.string.checkup_use_all, (d, w) -> {
                    boolean[] all = new boolean[list.size()];
                    java.util.Arrays.fill(all, true);
                    apply(a, list, all);
                    start.run();
                })
                .setNegativeButton(R.string.checkup_keep_mine, (d, w) -> {
                    apply(a, list, new boolean[list.size()]);
                    start.run();
                })
                .setCancelable(false)
                .show());
    }

    /** Saves the chosen ones; the others are remembered as turned down for a week. */
    private static void apply(Context c, List<Suggestion> list, boolean[] chosen) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
        SharedPreferences.Editor edit = prefs.edit();
        JSONObject declined = declined(prefs);
        int changed = 0;
        for (int i = 0; i < list.size(); i++) {
            Suggestion s = list.get(i);
            if (!chosen[i]) {
                try {
                    declined.put(s.id(), System.currentTimeMillis());
                } catch (Exception ignored) {
                }
                continue;
            }
            changed++;
            if (s.value instanceof Boolean) {
                edit.putBoolean(s.key, (Boolean) s.value);
            } else if (s.value instanceof Integer) {
                edit.putInt(s.key, (Integer) s.value);
            } else {
                edit.putString(s.key, (String) s.value);
            }
        }
        edit.putString(PREF_DECLINED, declined.toString()).apply();
        if (changed > 0) {
            android.widget.Toast.makeText(c, c.getResources().getQuantityString(R.plurals.checkup_changed, changed, changed),
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    private static JSONObject declined(SharedPreferences prefs) {
        try {
            JSONObject all = new JSONObject(prefs.getString(PREF_DECLINED, "{}"));
            // Forget ones older than a week
            JSONObject kept = new JSONObject();
            long now = System.currentTimeMillis();
            for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                String k = it.next();
                if (now - all.optLong(k, 0) < DECLINED_FOR_MS) {
                    kept.put(k, all.optLong(k));
                }
            }
            return kept;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static List<Suggestion> suggestions(Activity a, int measuredMbps) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(a);
        PreferenceConfiguration config = PreferenceConfiguration.readPreferences(a);
        List<Suggestion> out = new ArrayList<>();
        String resolution = prefs.getString("list_resolution", PreferenceConfiguration.DEFAULT_RESOLUTION);
        String fps = prefs.getString("list_fps", PreferenceConfiguration.DEFAULT_FPS);
        int width = parse(resolution, 0);
        int height = parse(resolution, 1);
        int fpsNow = (int) Float.parseFloat(fps);

        // What the TV can show
        int tvWidth = 0, tvHeight = 0;
        float tvMaxRate = 0;
        Display display = a.getWindowManager().getDefaultDisplay();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && display != null) {
            for (Display.Mode m : display.getSupportedModes()) {
                int w = Math.max(m.getPhysicalWidth(), m.getPhysicalHeight());
                int h = Math.min(m.getPhysicalWidth(), m.getPhysicalHeight());
                if ((long) w * h > (long) tvWidth * tvHeight) {
                    tvWidth = w;
                    tvHeight = h;
                }
            }
            for (Display.Mode m : display.getSupportedModes()) {
                if (Math.max(m.getPhysicalWidth(), m.getPhysicalHeight()) == tvWidth) {
                    tvMaxRate = Math.max(tvMaxRate, m.getRefreshRate());
                }
            }
        }

        // The picture size: the TV's own, or smaller when the connection can't carry it
        String bestSize = null;
        if (tvWidth >= 3840 && hasValue(a, R.array.resolution_values, "3840x2160")) {
            bestSize = measuredMbps < 0 || measuredMbps >= 60 ? "3840x2160" : measuredMbps >= 35 ? "2560x1440" : "1920x1080";
        } else if (tvWidth >= 2560) {
            bestSize = measuredMbps < 0 || measuredMbps >= 35 ? "2560x1440" : "1920x1080";
        } else if (tvWidth >= 1920) {
            bestSize = "1920x1080";
        }
        if (bestSize != null && !bestSize.equals(resolution) && config.resolutionScaleFactor == 100) {
            int bw = parse(bestSize, 0);
            // Only bigger when the TV can show it, smaller when the TV can't, or the connection is short
            boolean tooBigForTv = width > tvWidth;
            boolean tvCanDoMore = bw > width;
            boolean tooMuchForConnection = measuredMbps >= 0 && bw < width;
            if (tooBigForTv || tvCanDoMore || tooMuchForConnection) {
                out.add(new Suggestion("list_resolution", bestSize, a.getString(
                        tooBigForTv ? R.string.checkup_size_tv : tvCanDoMore ? R.string.checkup_size_up : R.string.checkup_size_down,
                        label(a, resolution), label(a, bestSize), measuredMbps)));
                width = bw;
                height = parse(bestSize, 1);
            }
        }

        // The frame rate: no more than the TV can show
        if (tvMaxRate > 0 && fpsNow > Math.round(tvMaxRate)) {
            String best = Math.round(tvMaxRate) >= 60 ? "60" : "30";
            out.add(new Suggestion("list_fps", best, a.getString(R.string.checkup_fps_tv, fpsNow, best)));
            fpsNow = Integer.parseInt(best);
        }

        // The bitrate: a share of what was measured, as "Fit picture quality to this connection" does
        if (measuredMbps > 0) {
            int current = config.bitrate / 1000;
            SharedPreferences copy = prefs;
            int suggested = ConnectionTuner.suggestMbpsFor(copy, measuredMbps, width + "x" + height, String.valueOf(fpsNow));
            // Only worth a word when it's a real difference
            if (Math.abs(suggested - current) * 100 >= Math.max(1, current) * 25) {
                out.add(new Suggestion("seekbar_bitrate_kbps", suggested * 1000, a.getString(
                        suggested < current ? R.string.checkup_bitrate_down : R.string.checkup_bitrate_up,
                        current, suggested, measuredMbps)));
            }
        }

        // Adjusting to the Wi-Fi, when the last stream struggled
        JSONObject last = lastReport(prefs);
        if (!prefs.getBoolean("tuna_adaptive_quality", true) && last != null && last.optInt("seconds", 0) >= 60 &&
                last.optInt("losingSeconds", 0) * 100 >= last.optInt("seconds", 1) * 3) {
            out.add(new Suggestion("tuna_adaptive_quality", true, a.getString(R.string.checkup_adaptive)));
        }

        // HDR and 10-bit only help when this TV can show and decode them
        if (prefs.getBoolean("checkbox_enable_hdr", false) && !tvHasHdr(display)) {
            out.add(new Suggestion("checkbox_enable_hdr", false, a.getString(R.string.checkup_hdr_off)));
        }
        if (prefs.getBoolean("tuna_10bit", true) && !decodesMain10()) {
            out.add(new Suggestion("tuna_10bit", false, a.getString(R.string.checkup_10bit_off)));
        }

        // Leave out what was turned down this week
        JSONObject declined = declined(prefs);
        List<Suggestion> kept = new ArrayList<>();
        for (Suggestion s : out) {
            if (!declined.has(s.id())) {
                kept.add(s);
            }
        }
        return kept;
    }

    private static JSONObject lastReport(SharedPreferences prefs) {
        try {
            JSONArray all = new JSONArray(prefs.getString("tuna_stream_reports", "[]"));
            return all.length() > 0 ? all.getJSONObject(0) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean tvHasHdr(Display display) {
        if (display == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return false;
        }
        Display.HdrCapabilities hdr = display.getHdrCapabilities();
        return hdr != null && hdr.getSupportedHdrTypes().length > 0;
    }

    private static boolean decodesMain10() {
        try {
            for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (info.isEncoder()) {
                    continue;
                }
                for (String type : info.getSupportedTypes()) {
                    if (!"video/hevc".equalsIgnoreCase(type)) {
                        continue;
                    }
                    for (MediaCodecInfo.CodecProfileLevel p : info.getCapabilitiesForType(type).profileLevels) {
                        if (p.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10
                                || p.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10HDR10) {
                            return true;
                        }
                    }
                }
            }
        } catch (Exception e) {
            return true;  // Can't tell: don't suggest anything
        }
        return false;
    }

    private static int parse(String size, int part) {
        try {
            return Integer.parseInt(size.split("x")[part]);
        } catch (Exception e) {
            return 0;
        }
    }

    private static boolean hasValue(Context c, int array, String value) {
        for (String v : c.getResources().getStringArray(array)) {
            if (v.equals(value)) {
                return true;
            }
        }
        return false;
    }

    /** "4K" for 3840x2160, as the settings name it. */
    private static String label(Context c, String size) {
        String[] names = c.getResources().getStringArray(R.array.resolution_names);
        String[] values = c.getResources().getStringArray(R.array.resolution_values);
        for (int i = 0; i < Math.min(names.length, values.length); i++) {
            if (values[i].equals(size)) {
                return names[i];
            }
        }
        return size;
    }
}
