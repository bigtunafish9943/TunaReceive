package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.TimeZone;

/** The PC's menu > Your week: play time, most-played games and how smooth it was, from TunaStream. */
public final class WeekRecap {
    private WeekRecap() {}

    public static void show(final Activity a, final ComputerDetails computer, final String uniqueId) {
        new Thread(() -> {
            String text;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(a));
                int tz = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60000;
                text = describe(a, new JSONObject(http.getTunaRecap(tz)));
            } catch (FileNotFoundException e) {
                text = a.getString(R.string.week_needs_tunastream);
            } catch (Exception e) {
                LimeLog.warning("Your week: " + e);
                text = a.getString(R.string.week_failed);
            }
            final String shown = text;
            a.runOnUiThread(() -> UiHelper.styleDialog(new AlertDialog.Builder(a)
                    .setTitle(R.string.week_title)
                    .setMessage(shown)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()));
        }, "TunaReceive week").start();
    }

    private static String time(Activity a, long seconds) {
        long minutes = Math.round(seconds / 60.0);
        if (minutes < 60) {
            return a.getString(R.string.week_minutes, minutes);
        }
        return a.getString(R.string.week_hours, minutes / 60, minutes % 60);
    }

    static String describe(Activity a, JSONObject r) {
        StringBuilder sb = new StringBuilder();
        long total = r.optLong("seconds");
        if (total <= 0) {
            sb.append(a.getString(R.string.week_nothing));
        } else {
            sb.append(a.getString(R.string.week_total, time(a, total), r.optInt("streams")));
            JSONArray top = r.optJSONArray("topApps");
            if (top != null && top.length() > 0) {
                sb.append("\n\n").append(a.getString(R.string.week_most_played));
                for (int i = 0; i < top.length() && i < 5; i++) {
                    JSONObject g = top.optJSONObject(i);
                    String name = g.optString("app");
                    sb.append("\n").append(i + 1).append(". ").append(name.isEmpty() ? a.getString(R.string.week_desktop) : name)
                            .append(": ").append(time(a, g.optLong("seconds")));
                }
            }
            int smooth = r.optInt("smoothPercent", -1);
            if (smooth >= 0) {
                sb.append("\n\n").append(a.getString(R.string.week_smooth, smooth));
            }
        }
        JSONArray reminders = r.optJSONArray("fpsReminders");
        if (reminders != null) {
            for (int i = 0; i < reminders.length(); i++) {
                JSONObject m = reminders.optJSONObject(i);
                sb.append("\n\n").append(a.getString(R.string.week_fps_reminder, m.optString("app"),
                        Math.round(m.optDouble("fps")), m.optInt("targetFps", 60)));
            }
        }
        JSONArray milestones = r.optJSONArray("milestones");
        if (milestones != null && milestones.length() > 0) {
            long weekAgo = System.currentTimeMillis() / 1000 - 7 * 86400;
            StringBuilder recent = new StringBuilder();
            for (int i = 0; i < milestones.length(); i++) {
                JSONObject m = milestones.optJSONObject(i);
                if (m.optLong("at") >= weekAgo) {
                    recent.append("\n• ").append(milestone(a, m.optString("id")));
                }
            }
            if (recent.length() > 0) {
                sb.append("\n\n").append(a.getString(R.string.week_milestones)).append(recent);
            }
        }
        return sb.toString();
    }

    private static String milestone(Activity a, String id) {
        if (id.startsWith("hours_")) {
            String n = id.substring(6);
            return "1".equals(n) ? a.getString(R.string.milestone_first_hour) : a.getString(R.string.milestone_hours, n);
        }
        if (id.startsWith("streams_")) {
            return a.getString(R.string.milestone_streams, id.substring(8));
        }
        switch (id) {
            case "first_stream": return a.getString(R.string.milestone_first_stream);
            case "first_4k": return a.getString(R.string.milestone_first_4k);
            case "marathon": return a.getString(R.string.milestone_marathon);
            case "smooth_10": return a.getString(R.string.milestone_smooth_10);
            case "smooth_hour": return a.getString(R.string.milestone_smooth_hour);
            case "full_rate_4k": return a.getString(R.string.milestone_full_rate_4k);
            default: return id;
        }
    }
}
