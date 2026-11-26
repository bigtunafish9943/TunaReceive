package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Trophies: TunaStream's milestones, as a shelf in the PC's menu, and a pop-up with a little
 * fanfare when a stream has just earned one.
 */
public final class Trophies {
    private Trophies() {
    }

    /** Every trophy, in the order the shelf shows them. */
    private static final String[] ALL = {
            "first_stream", "first_4k", "hours_1", "hours_10", "hours_25", "hours_50", "hours_100", "hours_250",
            "hours_500", "hours_1000", "streams_10", "streams_50", "streams_100", "streams_500", "marathon",
            "smooth_hour", "smooth_10", "full_rate_4k", "game_night", "phone_player", "night_owl", "early_bird",
            "streak_3", "streak_7", "variety_5", "variety_10",
    };

    /** When the last stream ended (elapsed time), so the games screen knows to look for new ones. */
    private static long streamEndedAt;

    public static void streamEnded() {
        streamEndedAt = SystemClock.elapsedRealtime();
    }

    private static String seenKey(ComputerDetails computer) {
        return "trophies_seen_" + computer.uuid;
    }

    private static JSONArray fetch(Activity a, ComputerDetails computer, String uniqueId) throws Exception {
        NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(a));
        int tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
        JSONArray m = new JSONObject(http.getTunaRecap(tz)).optJSONArray("milestones");
        return m != null ? m : new JSONArray();
    }

    /** Back on the games after a stream: any trophy it earned pops up. */
    public static void checkAfterStream(final Activity a, final ComputerDetails computer, final String uniqueId) {
        if (computer == null || uniqueId == null || streamEndedAt == 0
                || SystemClock.elapsedRealtime() - streamEndedAt > 3 * 60_000) {
            return;
        }
        streamEndedAt = 0;
        // A moment for TunaStream to note the stream that just ended
        new Handler(Looper.getMainLooper()).postDelayed(() -> new Thread(() -> {
            try {
                JSONArray all = fetch(a, computer, uniqueId);
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(a);
                long seen = prefs.getLong(seenKey(computer), -1);
                long newest = seen;
                final List<String> fresh = new ArrayList<>();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject t = all.optJSONObject(i);
                    if (t == null) continue;
                    long at = t.optLong("at");
                    newest = Math.max(newest, at);
                    if (seen >= 0 && at > seen) {
                        fresh.add(WeekRecap.milestone(a, t.optString("id")));
                    }
                }
                prefs.edit().putLong(seenKey(computer), Math.max(newest, 0)).apply();
                // The first look only learns what's there: no flood of old trophies
                if (fresh.isEmpty() || seen < 0) {
                    return;
                }
                a.runOnUiThread(() -> celebrate(a, fresh));
            } catch (Exception e) {
                LimeLog.warning("Trophies: " + e);
            }
        }, "TunaReceive trophies").start(), 2500);
    }

    private static void celebrate(Activity a, List<String> names) {
        if (a.isFinishing()) return;
        StringBuilder text = new StringBuilder();
        for (String n : names) {
            text.append("🏆  ").append(n).append('\n');
        }
        try {
            final ToneGenerator tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 70);
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 400);
            new Handler(Looper.getMainLooper()).postDelayed(tone::release, 800);
        } catch (Exception e) {
            // No sound, still the trophy
        }
        UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(a.getResources().getQuantityString(R.plurals.trophy_new, names.size(), names.size()))
                .setMessage(text.toString().trim())
                .setPositiveButton(android.R.string.ok, null)
                .show());
    }

    /** The PC's menu > Trophies: everything earned, with when, then what's still to get. */
    public static void showShelf(final Activity a, final ComputerDetails computer, final String uniqueId) {
        new Thread(() -> {
            String text;
            String title = a.getString(R.string.trophy_shelf);
            try {
                JSONArray all = fetch(a, computer, uniqueId);
                Map<String, Long> earned = new HashMap<>();
                long newest = 0;
                for (int i = 0; i < all.length(); i++) {
                    JSONObject t = all.optJSONObject(i);
                    if (t != null) {
                        earned.put(t.optString("id"), t.optLong("at"));
                        newest = Math.max(newest, t.optLong("at"));
                    }
                }
                PreferenceManager.getDefaultSharedPreferences(a).edit().putLong(seenKey(computer), newest).apply();
                StringBuilder got = new StringBuilder();
                StringBuilder toGet = new StringBuilder();
                DateFormat date = DateFormat.getDateInstance(DateFormat.MEDIUM);
                int count = 0;
                for (String id : ALL) {
                    Long at = earned.get(id);
                    if (at != null) {
                        count++;
                        got.append("🏆  ").append(WeekRecap.milestone(a, id)).append("  ·  ")
                                .append(date.format(new Date(at * 1000))).append('\n');
                    } else {
                        toGet.append("○  ").append(WeekRecap.milestone(a, id)).append('\n');
                    }
                }
                title = a.getString(R.string.trophy_shelf_count, count, ALL.length);
                text = (got.length() > 0 ? got.toString() : a.getString(R.string.trophy_none) + "\n")
                        + (toGet.length() > 0 ? "\n" + a.getString(R.string.trophy_to_get) + "\n" + toGet : "");
            } catch (FileNotFoundException e) {
                text = a.getString(R.string.week_needs_tunastream);
            } catch (Exception e) {
                LimeLog.warning("Trophy shelf: " + e);
                text = a.getString(R.string.week_failed);
            }
            final String shownTitle = title;
            final String shown = text.trim();
            a.runOnUiThread(() -> UiHelper.styleDialog(new AlertDialog.Builder(a)
                    .setTitle(shownTitle)
                    .setMessage(shown)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()));
        }, "TunaReceive trophy shelf").start();
    }
}
