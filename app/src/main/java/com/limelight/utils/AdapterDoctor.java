package com.limelight.utils;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.input.driver.EightBitDoController;

/**
 * Mends an 8BitDo 2.4G adapter that has been left with no driver at all: TunaReceive took it
 * over for a game (to rumble it) and was closed or stopped before giving it back, after which
 * the controller works nowhere, not in TunaHome, not in TunaReceive, not in a game, until the
 * adapter is unplugged. When TunaReceive's main screen opens and the adapter is plugged in but
 * Android has no controller for it (and TunaReceive isn't driving it), Android's driver is asked
 * back, and if that doesn't take, the adapter is reset so Android sets it up again. If neither
 * works, the viewer is told to unplug it once.
 */
public final class AdapterDoctor {
    private static final String ACTION = "com.tunastream.receive.ADAPTER_DOCTOR_PERMISSION";
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile boolean busy;
    private static long lastCheck;

    private AdapterDoctor() {
    }

    /** From the main screen's onResume. Does nothing when the adapter is fine or not there. */
    public static void check(final Activity activity) {
        long now = android.os.SystemClock.uptimeMillis();
        if (busy || now - lastCheck < 10_000) {
            return;
        }
        lastCheck = now;
        final Context app = activity.getApplicationContext();
        // A moment for Android to finish setting the adapter up after a game let go of it
        main.postDelayed(() -> look(app), 3000);
    }

    private static void look(Context app) {
        if (busy || Game.instance != null || EightBitDoController.active.get() > 0) {
            return;
        }
        UsbManager usb = (UsbManager) app.getSystemService(Context.USB_SERVICE);
        if (usb == null) {
            return;
        }
        UsbDevice adapter = null;
        try {
            for (UsbDevice dev : usb.getDeviceList().values()) {
                if (EightBitDoController.canClaimDevice(dev)) {
                    adapter = dev;
                }
            }
        } catch (Exception e) {
            return;
        }
        if (adapter == null || EightBitDoController.androidHasIt()) {
            return;
        }
        LimeLog.warning("8BitDo: the adapter is plugged in but Android has no controller for it; mending it");
        if (usb.hasPermission(adapter)) {
            mend(app, usb, adapter);
            return;
        }
        // Android asks once whether TunaReceive may use the adapter
        busy = true;
        final UsbDevice wanted = adapter;
        BroadcastReceiver answer = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    app.unregisterReceiver(this);
                } catch (Exception ignored) {
                }
                busy = false;
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    mend(app, usb, wanted);
                } else {
                    unplugHint(app);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(answer, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            app.registerReceiver(answer, filter);
        }
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        try {
            usb.requestPermission(adapter, PendingIntent.getBroadcast(app, 7,
                    new Intent(ACTION).setPackage(app.getPackageName()), flags));
        } catch (Exception e) {
            busy = false;
            unplugHint(app);
        }
    }

    private static void mend(final Context app, UsbManager usb, UsbDevice adapter) {
        final UsbDeviceConnection connection;
        try {
            connection = usb.openDevice(adapter);
        } catch (Exception e) {
            unplugHint(app);
            return;
        }
        if (connection == null) {
            unplugHint(app);
            return;
        }
        busy = true;
        // Asks for Android's driver, then resets the adapter if that doesn't take; closes it after
        EightBitDoController.giveBack(connection, 0);
        main.postDelayed(() -> {
            busy = false;
            if (EightBitDoController.androidHasIt()) {
                LimeLog.info("8BitDo: adapter mended");
                Toast.makeText(app, R.string.tuna_adapter_mended, Toast.LENGTH_LONG).show();
            } else {
                unplugHint(app);
            }
        }, 7000);
    }

    private static void unplugHint(Context app) {
        LimeLog.warning("8BitDo: couldn't mend the adapter; it needs unplugging");
        main.post(() -> Toast.makeText(app, R.string.tuna_adapter_unplug, Toast.LENGTH_LONG).show());
    }
}
