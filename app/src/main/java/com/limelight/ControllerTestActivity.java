package com.limelight;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;

import com.limelight.binding.input.driver.AbstractController;
import com.limelight.binding.input.driver.MenuControllerBridge;
import com.limelight.binding.input.driver.UsbDriverListener;
import com.limelight.nvstream.input.ControllerPacket;

import java.util.Locale;

/**
 * Test my controller: every button, both sticks and triggers drawn live, with how far each
 * stick rests from the centre, so drift and a sticking button show at a glance. Works for
 * Android's own controllers and the ones TunaReceive drives itself (8BitDo's adapter).
 * Back on the remote, or B held for a second, leaves.
 */
public class ControllerTestActivity extends Activity implements UsbDriverListener {
    private PadView pad;
    private long bHeldSince;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        pad = new PadView(this);
        pad.setFocusable(true);
        setContentView(pad);
        pad.requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The adapter TunaReceive drives: its raw state here instead of remote-like presses
        MenuControllerBridge.rawListener = this;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (MenuControllerBridge.rawListener == this) {
            MenuControllerBridge.rawListener = null;
        }
    }

    // ------------------------------------------------------------------ Android's controllers

    private static boolean fromPad(InputDevice d) {
        return d != null && ((d.getSources() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (d.getSources() & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK);
    }

    private static int flagOf(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_BUTTON_A: return ControllerPacket.A_FLAG;
            case KeyEvent.KEYCODE_BUTTON_B: return ControllerPacket.B_FLAG;
            case KeyEvent.KEYCODE_BUTTON_X: return ControllerPacket.X_FLAG;
            case KeyEvent.KEYCODE_BUTTON_Y: return ControllerPacket.Y_FLAG;
            case KeyEvent.KEYCODE_BUTTON_L1: return ControllerPacket.LB_FLAG;
            case KeyEvent.KEYCODE_BUTTON_R1: return ControllerPacket.RB_FLAG;
            case KeyEvent.KEYCODE_BUTTON_THUMBL: return ControllerPacket.LS_CLK_FLAG;
            case KeyEvent.KEYCODE_BUTTON_THUMBR: return ControllerPacket.RS_CLK_FLAG;
            case KeyEvent.KEYCODE_BUTTON_START: return ControllerPacket.PLAY_FLAG;
            case KeyEvent.KEYCODE_BUTTON_SELECT: return ControllerPacket.BACK_FLAG;
            case KeyEvent.KEYCODE_BUTTON_MODE: return ControllerPacket.SPECIAL_BUTTON_FLAG;
            case KeyEvent.KEYCODE_DPAD_UP: return ControllerPacket.UP_FLAG;
            case KeyEvent.KEYCODE_DPAD_DOWN: return ControllerPacket.DOWN_FLAG;
            case KeyEvent.KEYCODE_DPAD_LEFT: return ControllerPacket.LEFT_FLAG;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return ControllerPacket.RIGHT_FLAG;
            default: return 0;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        InputDevice d = event.getDevice();
        if (fromPad(d)) {
            int flag = flagOf(event.getKeyCode());
            if (flag != 0 || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2 || event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R2) {
                boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
                if (flag != 0) {
                    pad.buttons = down ? (pad.buttons | flag) : (pad.buttons & ~flag);
                } else if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2) {
                    pad.lt = down ? 1 : 0;
                } else {
                    pad.rt = down ? 1 : 0;
                }
                pad.name = d.getName();
                holdBToLeave(pad.buttons);
                pad.invalidate();
                return true;
            }
        }
        // The remote's Back leaves; everything else is the remote's
        return super.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent e) {
        InputDevice d = e.getDevice();
        if (fromPad(d) && e.getAction() == MotionEvent.ACTION_MOVE) {
            pad.lx = e.getAxisValue(MotionEvent.AXIS_X);
            pad.ly = e.getAxisValue(MotionEvent.AXIS_Y);
            pad.rx = e.getAxisValue(MotionEvent.AXIS_Z);
            pad.ry = e.getAxisValue(MotionEvent.AXIS_RZ);
            pad.lt = Math.max(e.getAxisValue(MotionEvent.AXIS_LTRIGGER), e.getAxisValue(MotionEvent.AXIS_BRAKE));
            pad.rt = Math.max(e.getAxisValue(MotionEvent.AXIS_RTRIGGER), e.getAxisValue(MotionEvent.AXIS_GAS));
            // A D-pad sent as a hat
            float hx = e.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hy = e.getAxisValue(MotionEvent.AXIS_HAT_Y);
            int dpad = (hx < -0.5f ? ControllerPacket.LEFT_FLAG : 0) | (hx > 0.5f ? ControllerPacket.RIGHT_FLAG : 0)
                    | (hy < -0.5f ? ControllerPacket.UP_FLAG : 0) | (hy > 0.5f ? ControllerPacket.DOWN_FLAG : 0);
            int dpadMask = ControllerPacket.LEFT_FLAG | ControllerPacket.RIGHT_FLAG | ControllerPacket.UP_FLAG | ControllerPacket.DOWN_FLAG;
            if (d.getMotionRange(MotionEvent.AXIS_HAT_X) != null) {
                pad.buttons = (pad.buttons & ~dpadMask) | dpad;
            }
            pad.name = d.getName();
            pad.noteRest();
            pad.invalidate();
            return true;
        }
        return super.dispatchGenericMotionEvent(e);
    }

    private void holdBToLeave(int buttons) {
        if ((buttons & ControllerPacket.B_FLAG) != 0) {
            if (bHeldSince == 0) {
                bHeldSince = SystemClock.uptimeMillis();
                pad.postDelayed(() -> {
                    if (bHeldSince != 0 && SystemClock.uptimeMillis() - bHeldSince >= 1000) {
                        finish();
                    }
                }, 1050);
            }
        } else {
            bHeldSince = 0;
        }
    }

    // ------------------------------------------------------------------ TunaReceive's own driver

    @Override
    public void reportControllerState(int controllerId, int buttonFlags, float leftStickX, float leftStickY,
                                      float rightStickX, float rightStickY, float leftTrigger, float rightTrigger) {
        runOnUiThread(() -> {
            pad.buttons = buttonFlags;
            pad.lx = leftStickX;
            // The driver's Y is up-positive; drawn down-positive like Android's
            pad.ly = -leftStickY;
            pad.rx = rightStickX;
            pad.ry = -rightStickY;
            pad.lt = leftTrigger;
            pad.rt = rightTrigger;
            pad.name = getString(R.string.tuna_test_driver_pad);
            holdBToLeave(buttonFlags);
            pad.noteRest();
            pad.invalidate();
        });
    }

    @Override
    public void reportControllerMotion(int controllerId, byte motionType, float motionX, float motionY, float motionZ) {
    }

    @Override
    public void deviceRemoved(AbstractController controller) {
    }

    @Override
    public void deviceAdded(AbstractController controller) {
    }

    // ------------------------------------------------------------------ drawing

    private static class PadView extends View {
        int buttons;
        float lx, ly, rx, ry, lt, rt;
        String name;
        // Where each stick rests: the largest distance from centre while it's let go
        float leftRest, rightRest;
        private long restSince;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float dp;
        private final int on = Color.rgb(63, 182, 232);
        private final int off = Color.rgb(40, 52, 70);

        PadView(Context context) {
            super(context);
            dp = getResources().getDisplayMetrics().density;
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeWidth(2 * dp);
            line.setColor(Color.rgb(90, 110, 140));
            text.setColor(Color.WHITE);
            text.setTextAlign(Paint.Align.CENTER);
        }

        /** While both sticks are near the middle, how far from it they settle. */
        void noteRest() {
            float l = (float) Math.hypot(lx, ly);
            float r = (float) Math.hypot(rx, ry);
            if (l < 0.3f && r < 0.3f) {
                long now = SystemClock.uptimeMillis();
                if (restSince == 0) {
                    restSince = now;
                } else if (now - restSince > 400) {
                    leftRest = l;
                    rightRest = r;
                }
            } else {
                restSince = 0;
            }
        }

        private boolean has(int flag) {
            return (buttons & flag) != 0;
        }

        private void button(Canvas c, float x, float y, float r, boolean pressed, String label) {
            fill.setColor(pressed ? on : off);
            c.drawCircle(x, y, r, fill);
            text.setTextSize(r * 0.8f);
            text.setColor(pressed ? Color.rgb(4, 18, 31) : Color.WHITE);
            c.drawText(label, x, y + r * 0.28f, text);
        }

        private void box(Canvas c, float x, float y, float w, float h, boolean pressed, String label) {
            fill.setColor(pressed ? on : off);
            c.drawRoundRect(new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), 10 * dp, 10 * dp, fill);
            text.setTextSize(h * 0.42f);
            text.setColor(pressed ? Color.rgb(4, 18, 31) : Color.WHITE);
            c.drawText(label, x, y + h * 0.15f, text);
        }

        private void trigger(Canvas c, float x, float y, float w, float h, float value, String label) {
            fill.setColor(off);
            c.drawRoundRect(new RectF(x - w / 2, y - h / 2, x + w / 2, y + h / 2), 8 * dp, 8 * dp, fill);
            fill.setColor(on);
            float v = Math.max(0, Math.min(1, value));
            c.drawRoundRect(new RectF(x - w / 2, y - h / 2, x - w / 2 + w * v, y + h / 2), 8 * dp, 8 * dp, fill);
            text.setTextSize(h * 0.5f);
            text.setColor(Color.WHITE);
            c.drawText(label + " " + Math.round(v * 100) + "%", x, y + h * 0.18f, text);
        }

        private void stick(Canvas c, float x, float y, float r, float sx, float sy, boolean click, float rest) {
            line.setColor(Color.rgb(90, 110, 140));
            c.drawCircle(x, y, r, line);
            // The usual deadzone, for comparison
            line.setColor(Color.rgb(60, 75, 98));
            c.drawCircle(x, y, r * 0.1f, line);
            fill.setColor(click ? Color.WHITE : on);
            c.drawCircle(x + sx * r, y + sy * r, r * 0.16f, fill);
            text.setTextSize(15 * dp);
            text.setColor(rest > 0.1f ? Color.rgb(255, 170, 90) : Color.rgb(170, 190, 210));
            c.drawText(getContext().getString(R.string.tuna_test_rest, String.format(Locale.US, "%.2f", rest)),
                    x, y + r + 26 * dp, text);
        }

        @Override
        protected void onDraw(Canvas c) {
            c.drawColor(Color.rgb(8, 14, 24));
            float w = getWidth();
            float h = getHeight();
            float u = Math.min(w / 16f, h / 9f);

            text.setTextSize(22 * dp);
            text.setColor(Color.WHITE);
            c.drawText(name != null ? name : getContext().getString(R.string.tuna_test_press), w / 2, u * 0.9f, text);

            trigger(c, w * 0.22f, u * 1.8f, u * 3f, u * 0.7f, lt, "LT");
            trigger(c, w * 0.78f, u * 1.8f, u * 3f, u * 0.7f, rt, "RT");
            box(c, w * 0.22f, u * 2.8f, u * 3f, u * 0.7f, has(ControllerPacket.LB_FLAG), "LB");
            box(c, w * 0.78f, u * 2.8f, u * 3f, u * 0.7f, has(ControllerPacket.RB_FLAG), "RB");

            stick(c, w * 0.22f, u * 5f, u * 1.4f, lx, ly, has(ControllerPacket.LS_CLK_FLAG), leftRest);
            stick(c, w * 0.62f, u * 6.9f, u * 1.1f, rx, ry, has(ControllerPacket.RS_CLK_FLAG), rightRest);

            float dx = w * 0.38f, dy = u * 6.9f, ds = u * 0.55f;
            box(c, dx, dy - ds * 1.1f, ds, ds, has(ControllerPacket.UP_FLAG), "▲");
            box(c, dx, dy + ds * 1.1f, ds, ds, has(ControllerPacket.DOWN_FLAG), "▼");
            box(c, dx - ds * 1.1f, dy, ds, ds, has(ControllerPacket.LEFT_FLAG), "◀");
            box(c, dx + ds * 1.1f, dy, ds, ds, has(ControllerPacket.RIGHT_FLAG), "▶");

            float fx = w * 0.78f, fy = u * 5f, fr = u * 0.42f;
            button(c, fx, fy + fr * 2.1f, fr, has(ControllerPacket.A_FLAG), "A");
            button(c, fx + fr * 2.1f, fy, fr, has(ControllerPacket.B_FLAG), "B");
            button(c, fx - fr * 2.1f, fy, fr, has(ControllerPacket.X_FLAG), "X");
            button(c, fx, fy - fr * 2.1f, fr, has(ControllerPacket.Y_FLAG), "Y");

            box(c, w * 0.43f, u * 4.2f, u * 1.1f, u * 0.5f, has(ControllerPacket.BACK_FLAG), "View");
            button(c, w * 0.5f, u * 4.2f, u * 0.35f, has(ControllerPacket.SPECIAL_BUTTON_FLAG), "⌂");
            box(c, w * 0.57f, u * 4.2f, u * 1.1f, u * 0.5f, has(ControllerPacket.PLAY_FLAG), "Menu");

            text.setTextSize(15 * dp);
            text.setColor(Color.rgb(150, 170, 195));
            c.drawText(getContext().getString(R.string.tuna_test_hint), w / 2, h - u * 0.4f, text);
        }
    }
}
