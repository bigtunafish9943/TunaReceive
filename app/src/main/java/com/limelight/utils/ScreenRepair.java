package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONObject;

import java.io.FileNotFoundException;

/**
 * The PC's menu > Repair the PC's virtual screen: asks TunaStream to reinstall its virtual
 * display driver, waits for it to finish and says whether streams can have their own screen again.
 */
public final class ScreenRepair {
    private ScreenRepair() {}

    public static void run(final Activity activity, final ComputerDetails computer, final String uniqueId) {
        final AlertDialog progress = new AlertDialog.Builder(activity)
                .setTitle(R.string.repair_title)
                .setMessage(R.string.repair_running)
                .setCancelable(false)
                .show();

        new Thread(() -> {
            int result;
            String detail = null;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(activity));
                JSONObject start = new JSONObject(http.repairTunaVdisplay());
                if (!start.optBoolean("started", false) && !start.optBoolean("repairing", false)) {
                    result = R.string.repair_refused;
                    detail = start.optString("error", null);
                } else {
                    // The repair reinstalls a driver; give it up to two minutes
                    JSONObject status = start;
                    for (int i = 0; i < 60 && status.optBoolean("repairing", true); i++) {
                        Thread.sleep(2000);
                        status = new JSONObject(http.getTunaVdisplay());
                    }
                    result = status.optBoolean("ok", false) ? R.string.repair_done : R.string.repair_still_broken;
                }
            } catch (FileNotFoundException e) {
                result = R.string.repair_needs_tunastream;
            } catch (Exception e) {
                LimeLog.warning("Virtual screen repair failed: " + e);
                result = R.string.tuner_failed;
            }
            final int message = result;
            final String extra = detail;
            activity.runOnUiThread(() -> {
                if (progress.isShowing()) {
                    progress.dismiss();
                }
                if (activity.isFinishing()) {
                    return;
                }
                String text = activity.getString(message);
                if (extra != null && !extra.isEmpty()) {
                    text = text + "\n\n" + extra;
                }
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.repair_title)
                        .setMessage(text)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "TunaReceive screen repair").start();
    }
}
