package com.limelight.utils;

import android.app.Activity;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONObject;

import java.io.FileNotFoundException;

/** Asks the PC to save the last 30 seconds of the stream as a clip (View + L1). */
public final class Replays {
    private static long lastSavedMs;

    private Replays() {}

    public static void save(Activity activity, NvHTTP http) {
        long now = android.os.SystemClock.uptimeMillis();
        if (http == null || now - lastSavedMs < 3000) {
            return;
        }
        lastSavedMs = now;
        Toast.makeText(activity, R.string.replay_saving, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            int message;
            try {
                JSONObject answer = new JSONObject(http.saveTunaReplay());
                if (answer.optBoolean("saved")) {
                    message = R.string.replay_saved;
                } else if ("off".equals(answer.optString("reason"))) {
                    message = R.string.replay_off;
                } else {
                    message = R.string.replay_failed;
                }
            } catch (FileNotFoundException e) {
                message = R.string.replay_needs_newer_pc;
            } catch (Exception e) {
                LimeLog.warning("Replay: " + e);
                message = R.string.replay_failed;
            }
            final int shown = message;
            activity.runOnUiThread(() -> Toast.makeText(activity, shown, Toast.LENGTH_LONG).show());
        }, "Replay").start();
    }
}
