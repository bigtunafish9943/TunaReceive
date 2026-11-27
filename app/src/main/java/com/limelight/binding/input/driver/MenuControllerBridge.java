package com.limelight.binding.input.driver;

import android.app.Activity;
import android.app.Application;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;

import com.limelight.Game;
import com.limelight.LimeLog;
import com.limelight.nvstream.input.ControllerPacket;

/**
 * Controllers TunaReceive drives itself (8BitDo's 2.4G adapter, and USB pads Android can't
 * run) work all over TunaReceive, not only in a game: while any of its screens is in front, the
 * adapter is TunaReceive's, and its D-pad, left stick and buttons move around the screens like
 * a remote. A game screen takes the controller over from here as before.
 *
 * Settings > "Controllers in TunaReceive's menus" turns this on; it's off unless chosen, which
 * leaves the adapter to Android outside a game (Android runs it as a controller everywhere). The
 * adapter is let go once no TunaReceive screen is in front, so Android has it back elsewhere.
 */
public class MenuControllerBridge implements Application.ActivityLifecycleCallbacks, UsbDriverListener {
    public static final String PREF = "checkbox_usb_menus";

    private static final long RELEASE_DELAY_MS = 600;
    private static final long REPEAT_FIRST_MS = 400;
    private static final long REPEAT_NEXT_MS = 110;
    private static final float STICK_ON = 0.55f;
    private static final float STICK_OFF = 0.35f;

    private final Application app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Handler inject;
    private final Instrumentation instrumentation = new Instrumentation();

    /** The TunaReceive screen in front, other than a game; null when none is. */
    private volatile Activity front;
    private boolean bound;
    private UsbDriverService.UsbDriverBinder binder;

    private static final class Pad {
        int buttons;
        int stickDirs;
        int heldDir;
        long heldSince;
        int repeats;
    }

    private final SparseArray<Pad> pads = new SparseArray<>();

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (!bound) {
                return;
            }
            binder = (UsbDriverService.UsbDriverBinder) service;
            binder.setListener(MenuControllerBridge.this);
            binder.setClaimForRumble(true);
            binder.start();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            binder = null;
        }
    };

    private final Runnable release = this::unbind;

    private MenuControllerBridge(Application app) {
        this.app = app;
        HandlerThread thread = new HandlerThread("MenuControllerBridge");
        thread.start();
        inject = new Handler(thread.getLooper());
    }

    public static void install(Application app) {
        // Off from now on unless chosen again: holding the adapter in the menus is what left it
        // with no driver when TunaReceive was closed, and Android runs it fine outside a game
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        if (!prefs.getBoolean("tuna_usb_menus_reset", false)) {
            prefs.edit().putBoolean(PREF, false).putBoolean("tuna_usb_menus_reset", true).apply();
        }
        app.registerActivityLifecycleCallbacks(new MenuControllerBridge(app));
    }

    private boolean wanted() {
        android.content.SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        return prefs.getBoolean("checkbox_usb_driver", true) && prefs.getBoolean(PREF, false);
    }

    private void bind() {
        main.removeCallbacks(release);
        if (bound) {
            return;
        }
        bound = true;
        try {
            app.bindService(new Intent(app, UsbDriverService.class), connection, Context.BIND_AUTO_CREATE);
        } catch (Exception e) {
            bound = false;
            LimeLog.warning("Menu controller: couldn't start the USB driver: " + e);
        }
    }

    private void unbind() {
        if (!bound) {
            return;
        }
        bound = false;
        binder = null;
        stopRepeat();
        synchronized (pads) {
            pads.clear();
        }
        try {
            app.unbindService(connection);
        } catch (Exception e) {
            // Already gone
        }
    }

    // ------------------------------------------------------------------ screens

    @Override
    public void onActivityResumed(Activity activity) {
        if (activity instanceof Game) {
            // The game takes the controller over: nothing more from here
            front = null;
            stopRepeat();
            return;
        }
        if (!wanted()) {
            front = null;
            unbind();
            return;
        }
        front = activity;
        bind();
        // Back from a game, which had the controller: it's this screen's again
        if (binder != null) {
            binder.setListener(this);
        }
    }

    @Override
    public void onActivityPaused(Activity activity) {
        if (activity == front) {
            front = null;
            stopRepeat();
            // Another TunaReceive screen usually follows at once: keep the controller for it.
            // Otherwise (Home, another app) Android gets the adapter back shortly.
            main.postDelayed(release, RELEASE_DELAY_MS);
        }
    }

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }

    // ------------------------------------------------------------------ the controller

    private static final int UP = 1, DOWN = 2, LEFT = 4, RIGHT = 8;

    /** A screen that wants the controller's raw state (Test my controller) rather than remote-like presses. */
    public static volatile UsbDriverListener rawListener;

    @Override
    public void reportControllerState(final int controllerId, final int buttonFlags, final float leftStickX, final float leftStickY,
                                      float rightStickX, float rightStickY, float leftTrigger, float rightTrigger) {
        UsbDriverListener raw = rawListener;
        if (raw != null) {
            raw.reportControllerState(controllerId, buttonFlags, leftStickX, leftStickY, rightStickX, rightStickY, leftTrigger, rightTrigger);
            return;
        }
        // Comes from the USB thread: handled on the main thread, with the screens
        main.post(() -> handleState(controllerId, buttonFlags, leftStickX, leftStickY));
    }

    private void handleState(int controllerId, int buttonFlags, float leftStickX, float leftStickY) {
        if (!bound) {
            return;
        }
        Pad pad;
        synchronized (pads) {
            pad = pads.get(controllerId);
            if (pad == null) {
                pad = new Pad();
                pads.put(controllerId, pad);
            }
        }
        if (front == null) {
            pad.buttons = buttonFlags;
            return;
        }

        // Buttons: pressed and let go as they are, so holding A still opens a menu
        int changed = buttonFlags ^ pad.buttons;
        button(changed, buttonFlags, ControllerPacket.A_FLAG, KeyEvent.KEYCODE_DPAD_CENTER);
        button(changed, buttonFlags, ControllerPacket.B_FLAG, KeyEvent.KEYCODE_BACK);
        button(changed, buttonFlags, ControllerPacket.X_FLAG, KeyEvent.KEYCODE_BUTTON_X);
        button(changed, buttonFlags, ControllerPacket.Y_FLAG, KeyEvent.KEYCODE_BUTTON_Y);
        button(changed, buttonFlags, ControllerPacket.LB_FLAG, KeyEvent.KEYCODE_BUTTON_L1);
        button(changed, buttonFlags, ControllerPacket.RB_FLAG, KeyEvent.KEYCODE_BUTTON_R1);
        button(changed, buttonFlags, ControllerPacket.PLAY_FLAG, KeyEvent.KEYCODE_MENU);
        button(changed, buttonFlags, ControllerPacket.BACK_FLAG, KeyEvent.KEYCODE_BUTTON_SELECT);
        pad.buttons = buttonFlags;

        // Directions: the D-pad, or the left stick pushed most of the way
        int stick = pad.stickDirs;
        stick = axis(stick, -leftStickY, UP, DOWN);
        stick = axis(stick, leftStickX, RIGHT, LEFT);
        pad.stickDirs = stick;
        int dirs = stick;
        if ((buttonFlags & ControllerPacket.UP_FLAG) != 0) dirs |= UP;
        if ((buttonFlags & ControllerPacket.DOWN_FLAG) != 0) dirs |= DOWN;
        if ((buttonFlags & ControllerPacket.LEFT_FLAG) != 0) dirs |= LEFT;
        if ((buttonFlags & ControllerPacket.RIGHT_FLAG) != 0) dirs |= RIGHT;
        // One direction at a time, the first one held winning
        int dir = (pad.heldDir != 0 && (dirs & pad.heldDir) != 0) ? pad.heldDir : first(dirs);
        if (dir != pad.heldDir) {
            final int old = pad.heldDir;
            pad.heldDir = dir;
            pad.repeats = 0;
            pad.heldSince = SystemClock.uptimeMillis();
            if (old != 0) {
                send(keyOf(old), false, 0);
            }
            stopRepeat();
            if (dir != 0) {
                send(keyOf(dir), true, 0);
                startRepeat(pad, dir);
            }
        }
    }

    private static int axis(int dirs, float value, int positive, int negative) {
        if (value > STICK_ON) {
            dirs = (dirs | positive) & ~negative;
        } else if (value < -STICK_ON) {
            dirs = (dirs | negative) & ~positive;
        } else if (Math.abs(value) < STICK_OFF) {
            dirs &= ~(positive | negative);
        }
        return dirs;
    }

    private static int first(int dirs) {
        if ((dirs & UP) != 0) return UP;
        if ((dirs & DOWN) != 0) return DOWN;
        if ((dirs & LEFT) != 0) return LEFT;
        if ((dirs & RIGHT) != 0) return RIGHT;
        return 0;
    }

    private static int keyOf(int dir) {
        switch (dir) {
            case UP: return KeyEvent.KEYCODE_DPAD_UP;
            case DOWN: return KeyEvent.KEYCODE_DPAD_DOWN;
            case LEFT: return KeyEvent.KEYCODE_DPAD_LEFT;
            default: return KeyEvent.KEYCODE_DPAD_RIGHT;
        }
    }

    private void button(int changed, int now, int flag, int keyCode) {
        if ((changed & flag) != 0) {
            send(keyCode, (now & flag) != 0, 0);
        }
    }

    // Held direction: repeats like a remote's held D-pad
    private Runnable repeater;

    private void startRepeat(final Pad pad, final int dir) {
        repeater = new Runnable() {
            @Override
            public void run() {
                if (front == null || pad.heldDir != dir) {
                    return;
                }
                pad.repeats++;
                send(keyOf(dir), true, pad.repeats);
                main.postDelayed(this, REPEAT_NEXT_MS);
            }
        };
        main.postDelayed(repeater, REPEAT_FIRST_MS);
    }

    private void stopRepeat() {
        if (repeater != null) {
            main.removeCallbacks(repeater);
            repeater = null;
        }
    }

    private final SparseArray<Long> downTimes = new SparseArray<>();

    /**
     * Into TunaReceive's own window, through Android's input like a remote's press, so moving
     * between things, long presses and dialogs behave as they do with the remote. Android allows
     * this only into the app's own windows; if it won't, the screen gets the press directly.
     */
    private void send(final int keyCode, final boolean down, final int repeat) {
        final long now = SystemClock.uptimeMillis();
        long downTime;
        synchronized (downTimes) {
            if (down && repeat == 0) {
                downTimes.put(keyCode, now);
            }
            Long t = downTimes.get(keyCode);
            downTime = t != null ? t : now;
        }
        int source = (keyCode >= KeyEvent.KEYCODE_DPAD_UP && keyCode <= KeyEvent.KEYCODE_DPAD_CENTER)
                ? InputDevice.SOURCE_DPAD : InputDevice.SOURCE_GAMEPAD;
        final KeyEvent event = new KeyEvent(downTime, now, down ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP,
                keyCode, repeat, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, source);
        inject.post(() -> {
            if (front == null && down) {
                return;
            }
            try {
                instrumentation.sendKeySync(event);
            } catch (Exception e) {
                main.post(() -> {
                    Activity a = front;
                    if (a != null) {
                        a.dispatchKeyEvent(event);
                    }
                });
            }
        });
    }

    @Override
    public void reportControllerMotion(int controllerId, byte motionType, float motionX, float motionY, float motionZ) {
    }

    @Override
    public void deviceRemoved(AbstractController controller) {
        synchronized (pads) {
            pads.remove(controller.getControllerId());
        }
    }

    @Override
    public void deviceAdded(AbstractController controller) {
    }
}
