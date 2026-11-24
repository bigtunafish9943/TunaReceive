package com.limelight.utils;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.view.KeyEvent;

import com.limelight.LimeLog;
import com.limelight.binding.input.driver.AbstractController;
import com.limelight.binding.input.driver.UsbDriverListener;
import com.limelight.binding.input.driver.UsbDriverService;
import com.limelight.nvstream.input.ControllerPacket;
import com.limelight.preferences.PreferenceConfiguration;

/**
 * Lets a controller that only TunaReceive's own USB driver can read (such as 8BitDo's 2.4G
 * adapter on a Shield, which Android doesn't run) move around TunaReceive's screens: the
 * D-pad or left stick moves, A chooses, B goes back, Start opens the menu.
 *
 * The keys are fed into this app's own windows, so lists, buttons and pop-ups all respond as
 * they would to the remote. During a stream the stream screen takes the controller over.
 */
public final class UsbMenuNavigation implements UsbDriverListener {
    private static final int REPEAT_FIRST_MS = 400;
    private static final int REPEAT_EVERY_MS = 130;
    private static final float STICK_THRESHOLD = 0.6f;

    private final Activity activity;
    private final HandlerThread thread = new HandlerThread("TunaReceive menu controller");
    private final Handler keys;
    private final Instrumentation instrumentation = new Instrumentation();

    private UsbDriverService.UsbDriverBinder binder;
    private boolean bound;
    private volatile boolean active;
    private int lastButtons;
    private int heldDirection;  // a KEYCODE_DPAD_* or 0

    private final Runnable repeat = new Runnable() {
        @Override
        public void run() {
            if (heldDirection != 0 && active) {
                press(heldDirection);
                keys.postDelayed(this, REPEAT_EVERY_MS);
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            binder = (UsbDriverService.UsbDriverBinder) service;
            if (active) {
                binder.setListener(UsbMenuNavigation.this);
                binder.start();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    public UsbMenuNavigation(Activity activity) {
        this.activity = activity;
        thread.start();
        keys = new Handler(thread.getLooper());
    }

    /** From onResume: start reading USB controllers for this screen. */
    public void resume() {
        if (!PreferenceConfiguration.readPreferences(activity).usbDriver) {
            return;
        }
        active = true;
        if (!bound) {
            bound = activity.bindService(new Intent(activity, UsbDriverService.class), connection, Service.BIND_AUTO_CREATE);
        } else if (binder != null) {
            binder.setListener(this);
            binder.start();
        }
    }

    /** From onPause: let go, so a stream (or another screen) can take the controller. */
    public void pause() {
        active = false;
        keys.post(() -> {
            heldDirection = 0;
            lastButtons = 0;
            keys.removeCallbacks(repeat);
        });
        if (binder != null) {
            binder.setListener(null);
        }
    }

    /** From onDestroy. */
    public void destroy() {
        pause();
        if (bound) {
            try {
                activity.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Already unbound
            }
            bound = false;
        }
        thread.quitSafely();
    }

    // Runs on the key thread: Instrumentation must not be used on the main thread
    private void press(int keyCode) {
        try {
            instrumentation.sendKeyDownUpSync(keyCode);
        } catch (RuntimeException e) {
            // Injecting is only allowed into this app's own focused window; fall back to the activity
            LimeLog.info("Menu key went to the activity directly: " + e);
            activity.runOnUiThread(() -> {
                long now = android.os.SystemClock.uptimeMillis();
                activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
                activity.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
            });
        }
    }

    private boolean pressedNow(int buttons, int flag) {
        return (buttons & flag) != 0 && (lastButtons & flag) == 0;
    }

    @Override
    public void reportControllerState(int controllerId, int buttonFlags, float leftStickX, float leftStickY,
                                      float rightStickX, float rightStickY, float leftTrigger, float rightTrigger) {
        if (!active) {
            return;
        }
        final int buttons = buttonFlags;

        // Direction from the D-pad, or failing that the left stick (positive Y is down)
        int direction = 0;
        if ((buttons & ControllerPacket.UP_FLAG) != 0 || leftStickY < -STICK_THRESHOLD) {
            direction = KeyEvent.KEYCODE_DPAD_UP;
        } else if ((buttons & ControllerPacket.DOWN_FLAG) != 0 || leftStickY > STICK_THRESHOLD) {
            direction = KeyEvent.KEYCODE_DPAD_DOWN;
        } else if ((buttons & ControllerPacket.LEFT_FLAG) != 0 || leftStickX < -STICK_THRESHOLD) {
            direction = KeyEvent.KEYCODE_DPAD_LEFT;
        } else if ((buttons & ControllerPacket.RIGHT_FLAG) != 0 || leftStickX > STICK_THRESHOLD) {
            direction = KeyEvent.KEYCODE_DPAD_RIGHT;
        }
        final int newDirection = direction;

        keys.post(() -> {
            if (newDirection != heldDirection) {
                keys.removeCallbacks(repeat);
                heldDirection = newDirection;
                if (newDirection != 0) {
                    press(newDirection);
                    keys.postDelayed(repeat, REPEAT_FIRST_MS);
                }
            }
            if (pressedNow(buttons, ControllerPacket.A_FLAG)) {
                press(KeyEvent.KEYCODE_DPAD_CENTER);
            }
            if (pressedNow(buttons, ControllerPacket.B_FLAG)) {
                press(KeyEvent.KEYCODE_BACK);
            }
            if (pressedNow(buttons, ControllerPacket.PLAY_FLAG)) {
                press(KeyEvent.KEYCODE_MENU);
            }
            lastButtons = buttons;
        });
    }

    @Override
    public void reportControllerMotion(int controllerId, byte motionType, float motionX, float motionY, float motionZ) {
        // Not used for moving around menus
    }

    @Override
    public void deviceRemoved(AbstractController controller) {
        keys.post(() -> {
            heldDirection = 0;
            lastButtons = 0;
            keys.removeCallbacks(repeat);
        });
    }

    @Override
    public void deviceAdded(AbstractController controller) {
        LimeLog.info("A USB controller can now move around TunaReceive's screens");
    }
}
