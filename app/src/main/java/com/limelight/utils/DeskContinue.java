package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.SystemClock;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Continue on the TV: when a game is being played at the PC, opening its games here offers to
 * carry on with it on the TV. A stream starts and TunaStream moves the game's window onto it,
 * without restarting the game.
 */
public final class DeskContinue {
    private DeskContinue() {
    }

    /** Games turned down lately ("Not now"), by name, so the offer isn't made again for a while. */
    private static final Map<String, Long> declined = new HashMap<>();
    private static final long QUIET_MS = 20 * 60_000;

    /** Starts the stream that the game will move onto (the PC's Desktop). */
    public interface Starter {
        boolean startDesktop();
    }

    private static NvHTTP http(Activity a, ComputerDetails computer, String uniqueId) throws Exception {
        return new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(a));
    }

    public static void check(final Activity a, final ComputerDetails computer, final String uniqueId, final Starter starter) {
        if (computer == null || uniqueId == null || computer.runningGameId != 0) {
            return;
        }
        new Thread(() -> {
            try {
                JSONObject desk = new JSONObject(http(a, computer, uniqueId).tunaDesk());
                if (!desk.optBoolean("playing")) {
                    return;
                }
                final String name = desk.optString("name");
                final String id = desk.optString("id");
                synchronized (declined) {
                    Long when = declined.get(name);
                    if (when != null && SystemClock.elapsedRealtime() - when < QUIET_MS) {
                        return;
                    }
                }
                a.runOnUiThread(() -> offer(a, computer, uniqueId, name, id, starter));
            } catch (java.io.FileNotFoundException e) {
                // An older TunaStream: nothing to offer
            } catch (Exception e) {
                LimeLog.warning("Continue on the TV: " + e);
            }
        }, "TunaReceive desk").start();
    }

    private static void offer(final Activity a, final ComputerDetails computer, final String uniqueId,
                              final String name, final String id, final Starter starter) {
        if (a.isFinishing()) {
            return;
        }
        UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.tuna_desk_title, name))
                .setMessage(R.string.tuna_desk_message)
                .setPositiveButton(R.string.tuna_desk_yes, (d, w) -> new Thread(() -> {
                    boolean marked = false;
                    try {
                        marked = new JSONObject(http(a, computer, uniqueId).tunaDeskContinue(id)).optBoolean("ok");
                    } catch (Exception e) {
                        LimeLog.warning("Continue on the TV: " + e);
                    }
                    final boolean ok = marked;
                    a.runOnUiThread(() -> {
                        if (!ok) {
                            Toast.makeText(a, R.string.tuna_desk_failed, Toast.LENGTH_LONG).show();
                        } else if (!starter.startDesktop()) {
                            Toast.makeText(a, R.string.tuna_desk_no_desktop, Toast.LENGTH_LONG).show();
                        }
                    });
                }, "TunaReceive continue").start())
                .setNegativeButton(R.string.tuna_desk_no, (d, w) -> {
                    synchronized (declined) {
                        declined.put(name, SystemClock.elapsedRealtime());
                    }
                })
                .show());
    }
}
