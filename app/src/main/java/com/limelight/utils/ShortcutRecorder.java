package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.limelight.R;

import java.util.HashSet;
import java.util.Set;

/**
 * Settings > Controller shortcuts: change one shortcut by pressing it on the controller, put it
 * back to how it came, or turn it off. Any button the controller reports can be used (triggers,
 * Home, Share and paddles included), so it works with whatever controller is in hand.
 */
public final class ShortcutRecorder {
    private ShortcutRecorder() {}

    private static final long GIVE_UP_AFTER_MS = 15000;

    /** The choices for one shortcut. onChanged runs after it was saved. */
    public static void choose(final Activity a, final String action, final int title, final Runnable onChanged) {
        final String current = Shortcuts.get(a, action);
        final String fallback = Shortcuts.defaultFor(action);
        final boolean menu = Shortcuts.MENU.equals(action);
        java.util.List<String> labels = new java.util.ArrayList<>();
        final java.util.List<Runnable> actions = new java.util.ArrayList<>();

        labels.add(a.getString(R.string.shortcut_record));
        actions.add(() -> record(a, action, title, onChanged));
        if (!fallback.equals(current)) {
            labels.add(a.getString(R.string.shortcut_reset, Shortcuts.label(a, fallback)));
            actions.add(() -> save(a, action, fallback, onChanged));
        }
        if (menu && !Shortcuts.HOME_HOLD.equals(current) && !Shortcuts.HOME_HOLD.equals(fallback)) {
            labels.add(Shortcuts.label(a, Shortcuts.HOME_HOLD));
            actions.add(() -> save(a, action, Shortcuts.HOME_HOLD, onChanged));
        }
        if (!Shortcuts.OFF.equals(current)) {
            labels.add(a.getString(R.string.shortcut_turn_off));
            actions.add(() -> save(a, action, Shortcuts.OFF, onChanged));
        }

        UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.shortcut_choose_title, a.getString(title), Shortcuts.label(a, current)))
                .setItems(labels.toArray(new String[0]), (d, which) -> actions.get(which).run())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private static void save(Activity a, String action, String combo, Runnable onChanged) {
        String other = Shortcuts.usedBy(a, combo, action);
        if (other != null) {
            // The other shortcut gives way: two shortcuts never share buttons
            PreferenceManager.getDefaultSharedPreferences(a).edit().putString(other, Shortcuts.OFF).apply();
            Toast.makeText(a, a.getString(R.string.shortcut_moved, Shortcuts.label(a, combo)), Toast.LENGTH_LONG).show();
        }
        PreferenceManager.getDefaultSharedPreferences(a).edit().putString(action, combo).apply();
        if (onChanged != null) {
            onChanged.run();
        }
    }

    /** Wait for buttons to be pressed together and let go, then save them. */
    private static void record(final Activity a, final String action, final int title, final Runnable onChanged) {
        final int pad = Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 24, a.getResources().getDisplayMetrics()));
        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, pad, pad, pad);
        box.setMinimumWidth(pad * 18);

        TextView heading = new TextView(a);
        heading.setText(a.getString(title));
        heading.setTextColor(ContextCompat.getColor(a, R.color.ts_text));
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(heading);

        final TextView message = new TextView(a);
        message.setText(R.string.shortcut_record_hint);
        message.setTextColor(ContextCompat.getColor(a, R.color.ts_text_secondary));
        message.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        message.setPadding(0, pad / 2, 0, 0);
        box.addView(message);

        final TextView pressed = new TextView(a);
        pressed.setTextColor(ContextCompat.getColor(a, R.color.ts_accent));
        pressed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        pressed.setTypeface(Typeface.DEFAULT_BOLD);
        pressed.setGravity(Gravity.CENTER);
        pressed.setPadding(0, pad, 0, pad / 2);
        pressed.setText("…");
        box.addView(pressed);

        TextView cancelHint = new TextView(a);
        cancelHint.setText(R.string.shortcut_record_cancel);
        cancelHint.setTextColor(ContextCompat.getColor(a, R.color.ts_text_secondary));
        cancelHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        box.addView(cancelHint);

        final Set<String> down = new HashSet<>();
        final Set<String> together = new HashSet<>();
        final Handler handler = new Handler(Looper.getMainLooper());

        final Dialog dialog = new Dialog(a, R.style.TunaDialog) {
            private final Runnable giveUp = this::dismiss;

            @Override
            protected void onStart() {
                super.onStart();
                handler.postDelayed(giveUp, GIVE_UP_AFTER_MS);
            }

            @Override
            protected void onStop() {
                super.onStop();
                handler.removeCallbacks(giveUp);
            }

            private boolean fromController(InputDevice device) {
                if (device == null) {
                    return false;
                }
                int sources = device.getSources();
                return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                        || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
            }

            private void changed() {
                handler.removeCallbacks(giveUp);
                handler.postDelayed(giveUp, GIVE_UP_AFTER_MS);
                if (!down.isEmpty()) {
                    pressed.setText(Shortcuts.label(a, Shortcuts.comboOf(together)));
                    return;
                }
                if (together.isEmpty()) {
                    return;
                }
                // Everything let go: that's the shortcut
                String combo = Shortcuts.comboOf(together);
                together.clear();
                String problem = Shortcuts.problem(a, combo, action);
                if (problem != null) {
                    message.setText(problem);
                    pressed.setText(Shortcuts.label(a, combo));
                    return;
                }
                save(a, action, combo, onChanged);
                Toast.makeText(a, a.getString(R.string.shortcut_saved, a.getString(title), Shortcuts.label(a, combo)),
                        Toast.LENGTH_SHORT).show();
                dismiss();
            }

            @Override
            public boolean dispatchKeyEvent(KeyEvent event) {
                if (!fromController(event.getDevice())) {
                    // The remote: Back cancels, anything else is ignored
                    if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                        dismiss();
                    }
                    return true;
                }
                String name = Shortcuts.nameOf(event);
                if (name == null) {
                    return true;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) {
                        down.add(name);
                        together.add(name);
                        changed();
                    }
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    down.remove(name);
                    changed();
                }
                return true;
            }

            @Override
            public boolean dispatchGenericMotionEvent(MotionEvent event) {
                if (!fromController(event.getDevice())) {
                    return super.dispatchGenericMotionEvent(event);
                }
                // Analog triggers, and D-pads that report as a hat
                axis(event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f || event.getAxisValue(MotionEvent.AXIS_BRAKE) > 0.5f, "LT");
                axis(event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f || event.getAxisValue(MotionEvent.AXIS_GAS) > 0.5f, "RT");
                float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
                float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
                axis(hatX < -0.5f, "LEFT");
                axis(hatX > 0.5f, "RIGHT");
                axis(hatY < -0.5f, "UP");
                axis(hatY > 0.5f, "DOWN");
                return true;
            }

            private void axis(boolean on, String name) {
                if (on && !down.contains(name)) {
                    down.add(name);
                    together.add(name);
                    changed();
                } else if (!on && down.contains(name)) {
                    down.remove(name);
                    changed();
                }
            }
        };
        dialog.setContentView(box);
        dialog.setCanceledOnTouchOutside(true);
        dialog.show();
    }
}
