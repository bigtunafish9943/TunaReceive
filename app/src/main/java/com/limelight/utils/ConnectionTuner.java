package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.HostHttpResponseException;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.preferences.SetupActivity;

import java.io.FileNotFoundException;

/**
 * Measures how fast data reaches this device from the PC, then suggests a picture quality that
 * leaves room for Wi-Fi hiccups. Stutter on a clean connection usually means the stream asks for
 * a little more than the Wi-Fi can carry, so the suggestion is a fraction of what was measured.
 */
public final class ConnectionTuner {
    private ConnectionTuner() {}

    /**
     * @param quiet after pairing: say nothing if the PC can't run the test, and call onDone either way
     */
    public static void run(final Activity activity, final ComputerDetails computer, final String uniqueId,
                           final boolean quiet, final Runnable onDone) {
        final AlertDialog progress = UiHelper.styleDialog(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.tuner_measuring_title, computer.name))
                .setMessage(R.string.tuner_measuring_body)
                .setCancelable(false)
                .show());

        new Thread(() -> {
            int mbps = -1;
            int failure = 0;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(activity));
                mbps = http.measureDownloadMbps();
            } catch (FileNotFoundException e) {
                failure = R.string.tuner_needs_tunastream;
            } catch (HostHttpResponseException e) {
                failure = e.getErrorCode() == 503 ? R.string.tuner_stream_running : R.string.tuner_failed;
            } catch (Exception e) {
                LimeLog.warning("Connection test failed: " + e);
                failure = R.string.tuner_failed;
            }
            final int measured = mbps;
            final int error = failure;
            activity.runOnUiThread(() -> {
                if (progress.isShowing()) {
                    progress.dismiss();
                }
                if (activity.isFinishing()) {
                    return;
                }
                if (measured <= 0) {
                    if (quiet) {
                        if (onDone != null) {
                            onDone.run();
                        }
                    } else {
                        UiHelper.styleDialog(new AlertDialog.Builder(activity)
                                .setTitle(R.string.tuner_failed_title)
                                .setMessage(error != 0 ? error : R.string.tuner_failed)
                                .setPositiveButton(android.R.string.ok, (d, w) -> { if (onDone != null) onDone.run(); })
                                .setCancelable(false)
                                .show());
                    }
                    return;
                }
                offer(activity, computer, measured, onDone);
            });
        }, "TunaReceive connection test").start();
    }

    private static void offer(Activity activity, ComputerDetails computer, int measuredMbps, Runnable onDone) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        final int current = PreferenceConfiguration.readPreferences(activity).bitrate / 1000;
        final int suggested = suggestMbps(prefs, measuredMbps);

        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.tuner_result_title, measuredMbps))
                .setCancelable(false);
        if (suggested == current) {
            dialog.setMessage(activity.getString(R.string.tuner_result_same, computer.name, current))
                    .setPositiveButton(android.R.string.ok, (d, w) -> { if (onDone != null) onDone.run(); });
        } else {
            dialog.setMessage(activity.getString(suggested < current
                            ? R.string.tuner_result_lower : R.string.tuner_result_higher,
                            computer.name, suggested, current))
                    .setPositiveButton(activity.getString(R.string.tuner_use, suggested), (d, w) -> {
                        prefs.edit().putInt("seekbar_bitrate_kbps", suggested * 1000).apply();
                        if (onDone != null) onDone.run();
                    })
                    .setNegativeButton(activity.getString(R.string.tuner_keep, current), (d, w) -> {
                        if (onDone != null) onDone.run();
                    });
        }
        UiHelper.styleDialog(dialog.show());
    }

    /** A share of the measured speed that depends on the Smoother/Sharper answer in setup. */
    static int suggestMbps(SharedPreferences prefs, int measuredMbps) {
        String picture = prefs.getString(SetupActivity.PICTURE_PREF, "balanced");
        double share;
        if ("smooth".equals(picture)) {
            share = 0.25;
        } else if ("sharp".equals(picture)) {
            share = 0.6;
        } else {
            share = 0.4;
        }
        // No higher than this picture size and frame rate can make good use of
        int ceiling = 2 * PreferenceConfiguration.getDefaultBitrate(
                prefs.getString("list_resolution", PreferenceConfiguration.DEFAULT_RESOLUTION),
                prefs.getString("list_fps", PreferenceConfiguration.DEFAULT_FPS)) / 1000;
        return Math.max(5, Math.min(ceiling, (int) Math.round(measuredMbps * share)));
    }
}
