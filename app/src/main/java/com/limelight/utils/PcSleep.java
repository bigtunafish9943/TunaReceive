package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONObject;

import java.io.FileNotFoundException;

/**
 * Puts the PC to sleep from the TV, the other half of waking it: done for the night without
 * walking to the PC. Asked first; TunaStream refuses while another device is streaming.
 */
public final class PcSleep {
    private PcSleep() {
    }

    public static void ask(final Activity a, final ComputerDetails computer, final String uniqueId) {
        UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.tuna_sleep_title, computer.name))
                .setMessage(R.string.tuna_sleep_message)
                .setPositiveButton(R.string.tuna_sleep_yes, (d, w) -> sleep(a, computer, uniqueId))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private static void sleep(final Activity a, final ComputerDetails computer, final String uniqueId) {
        new Thread(() -> {
            int message;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(a));
                JSONObject out = new JSONObject(http.tunaSleep());
                if (out.optBoolean("sleeping")) {
                    message = R.string.tuna_sleep_done;
                } else if ("someone else is streaming".equals(out.optString("reason"))) {
                    message = R.string.tuna_sleep_busy;
                } else if ("not allowed".equals(out.optString("reason"))) {
                    message = R.string.tuna_sleep_not_allowed;
                } else {
                    message = R.string.tuna_sleep_failed;
                }
            } catch (FileNotFoundException e) {
                message = R.string.tuna_sleep_update;
            } catch (Exception e) {
                LimeLog.warning("Sleep the PC: " + e);
                message = R.string.tuna_sleep_failed;
            }
            final int shown = message;
            a.runOnUiThread(() -> Toast.makeText(a, shown, Toast.LENGTH_LONG).show());
        }).start();
    }
}
