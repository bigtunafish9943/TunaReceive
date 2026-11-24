package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Build;
import android.os.CombinedVibration;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.view.InputDevice;

import com.limelight.LimeLog;
import com.limelight.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Settings > Check my controller: lists the controllers this device sees, says whether Android
 * gives apps a way to rumble each one, and tests it. Over Bluetooth, Android only offers rumble
 * for controllers its own drivers understand; when it doesn't, no app can rumble that controller.
 */
public final class ControllerCheck {
    private ControllerCheck() {}

    private static boolean isController(InputDevice device) {
        if (device == null || device.isVirtual()) {
            return false;
        }
        int sources = device.getSources();
        return (sources & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (sources & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }

    @SuppressWarnings("deprecation")
    private static boolean hasRumble(InputDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return device.getVibratorManager().getVibratorIds().length > 0;
        }
        Vibrator vibrator = device.getVibrator();
        return vibrator != null && vibrator.hasVibrator();
    }

    @SuppressWarnings("deprecation")
    private static void rumble(InputDevice device) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = device.getVibratorManager();
            if (manager.getVibratorIds().length > 0) {
                manager.vibrate(CombinedVibration.createParallel(VibrationEffect.createOneShot(700, 255)));
            }
            return;
        }
        Vibrator vibrator = device.getVibrator();
        if (vibrator == null || !vibrator.hasVibrator()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(700, 255));
        } else {
            vibrator.vibrate(700);
        }
    }

    public static void show(Activity activity) {
        StringBuilder text = new StringBuilder();
        final List<InputDevice> withRumble = new ArrayList<>();
        boolean anyWithout = false;

        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (!isController(device)) {
                continue;
            }
            boolean rumble = hasRumble(device);
            if (rumble) {
                withRumble.add(device);
            } else {
                anyWithout = true;
            }
            String ids = String.format(Locale.ROOT, "%04x:%04x", device.getVendorId(), device.getProductId());
            LimeLog.info("Controller check: " + device.getName() + " " + ids + " rumble=" + rumble);
            if (text.length() > 0) {
                text.append("\n\n");
            }
            text.append(device.getName()).append('\n')
                    .append(activity.getString(R.string.controller_check_id, ids)).append('\n')
                    .append(activity.getString(rumble ? R.string.controller_check_rumble_yes : R.string.controller_check_rumble_no));
        }

        if (text.length() == 0) {
            text.append(activity.getString(R.string.controller_check_none));
        } else if (anyWithout) {
            text.append("\n\n").append(activity.getString(R.string.controller_check_no_rumble_explained));
        }

        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(R.string.controller_check_title)
                .setMessage(text.toString())
                .setNegativeButton(android.R.string.ok, null);
        if (!withRumble.isEmpty()) {
            // Set below so pressing it doesn't close the box
            dialog.setPositiveButton(R.string.controller_check_test, null);
        }
        AlertDialog shown = dialog.show();
        if (!withRumble.isEmpty()) {
            shown.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                for (InputDevice device : withRumble) {
                    try {
                        rumble(device);
                    } catch (RuntimeException e) {
                        LimeLog.warning("Rumble test failed on " + device.getName() + ": " + e);
                    }
                }
            });
        }
    }
}
