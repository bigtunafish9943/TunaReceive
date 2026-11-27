package com.limelight.utils;

import android.app.Activity;
import android.preference.PreferenceManager;
import android.util.SparseIntArray;
import android.widget.Toast;

import com.limelight.R;
import com.limelight.binding.input.KeyboardTranslator;
import com.limelight.nvstream.NvConnection;
import com.limelight.nvstream.input.KeyboardPacket;
import com.limelight.nvstream.jni.MoonBridge;

/**
 * Low-battery save: a warning when a controller's battery runs low, and, just before it dies,
 * the game is paused (Esc, which pauses most games) so a flat controller doesn't cost a fight.
 * Settings > Pause before a controller goes flat turns it off.
 */
public class LowBattery {
    public static final String PREF = "checkbox_low_battery_save";
    private static final int WARN_AT = 15;
    private static final int PAUSE_AT = 4;
    private static final int RESET_ABOVE = 25;

    private final Activity activity;
    private final NvConnection conn;
    /** Per controller number: 0 nothing yet, 1 warned, 2 paused. */
    private final SparseIntArray done = new SparseIntArray();

    public LowBattery(Activity activity, NvConnection conn) {
        this.activity = activity;
        this.conn = conn;
    }

    public void note(int controller, byte state, int percent) {
        if (percent < 0 || percent > 100) {
            return;
        }
        synchronized (done) {
            // Charging, or charged up again: start over
            if (state != MoonBridge.LI_BATTERY_STATE_DISCHARGING || percent > RESET_ABOVE) {
                done.delete(controller);
                return;
            }
            if (!PreferenceManager.getDefaultSharedPreferences(activity).getBoolean(PREF, true)) {
                return;
            }
            final int player = controller + 1;
            int already = done.get(controller, 0);
            if (percent <= PAUSE_AT && already < 2) {
                done.put(controller, 2);
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        activity.getString(R.string.tuna_battery_pause, player, percent), Toast.LENGTH_LONG).show());
                conn.sendKeyboardInput((short) KeyboardTranslator.VK_ESCAPE, KeyboardPacket.KEY_DOWN, (byte) 0, (byte) 0);
                conn.sendKeyboardInput((short) KeyboardTranslator.VK_ESCAPE, KeyboardPacket.KEY_UP, (byte) 0, (byte) 0);
            } else if (percent <= WARN_AT && already < 1) {
                done.put(controller, 1);
                activity.runOnUiThread(() -> Toast.makeText(activity,
                        activity.getString(R.string.tuna_battery_low, player, percent), Toast.LENGTH_LONG).show());
            }
        }
    }
}
