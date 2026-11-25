package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import com.limelight.R;
import com.limelight.nvstream.input.ControllerPacket;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The controller shortcuts TunaReceive uses during a stream, each changeable in Settings >
 * Controller shortcuts. A shortcut is a set of buttons pressed together, saved as text such as
 * "BACK+RB"; "HOME_HOLD" is holding the Home button, and "OFF" turns a shortcut off.
 */
public final class Shortcuts {
    public static final String MENU = "tuna_shortcut_menu";
    public static final String SCREENSHOT = "tuna_shortcut_screenshot";
    public static final String REPLAY = "tuna_shortcut_replay";
    public static final String STATS = "tuna_shortcut_stats";
    public static final String QUIT = "tuna_shortcut_quit";

    public static final String HOME_HOLD = "HOME_HOLD";
    public static final String OFF = "OFF";

    /** Every shortcut and what it does by default. */
    public static final String[][] ACTIONS = {
            {MENU, HOME_HOLD},
            {SCREENSHOT, "BACK+RB"},
            {REPLAY, "BACK+LB"},
            {STATS, "BACK+RS"},
            {QUIT, "PLAY+BACK+LB+RB"},
    };

    /** The triggers, as buttons for shortcuts only (they're analog in the controller packet). */
    public static final int LT_FLAG = 1 << 28;
    public static final int RT_FLAG = 1 << 29;

    private static final Map<String, Integer> BUTTONS = new LinkedHashMap<>();
    static {
        BUTTONS.put("BACK", ControllerPacket.BACK_FLAG);
        BUTTONS.put("PLAY", ControllerPacket.PLAY_FLAG);
        BUTTONS.put("LB", ControllerPacket.LB_FLAG);
        BUTTONS.put("RB", ControllerPacket.RB_FLAG);
        BUTTONS.put("LS", ControllerPacket.LS_CLK_FLAG);
        BUTTONS.put("RS", ControllerPacket.RS_CLK_FLAG);
        BUTTONS.put("A", ControllerPacket.A_FLAG);
        BUTTONS.put("B", ControllerPacket.B_FLAG);
        BUTTONS.put("X", ControllerPacket.X_FLAG);
        BUTTONS.put("Y", ControllerPacket.Y_FLAG);
        BUTTONS.put("UP", ControllerPacket.UP_FLAG);
        BUTTONS.put("DOWN", ControllerPacket.DOWN_FLAG);
        BUTTONS.put("LEFT", ControllerPacket.LEFT_FLAG);
        BUTTONS.put("RIGHT", ControllerPacket.RIGHT_FLAG);
        BUTTONS.put("LT", LT_FLAG);
        BUTTONS.put("RT", RT_FLAG);
        BUTTONS.put("HOME", ControllerPacket.SPECIAL_BUTTON_FLAG);
        BUTTONS.put("SHARE", ControllerPacket.MISC_FLAG);
        BUTTONS.put("TOUCHPAD", ControllerPacket.TOUCHPAD_FLAG);
        BUTTONS.put("P1", ControllerPacket.PADDLE1_FLAG);
        BUTTONS.put("P2", ControllerPacket.PADDLE2_FLAG);
        BUTTONS.put("P3", ControllerPacket.PADDLE3_FLAG);
        BUTTONS.put("P4", ControllerPacket.PADDLE4_FLAG);
    }

    /** Buttons games rarely use, which may be a shortcut on their own. */
    private static final int SPARE_BUTTONS = ControllerPacket.SPECIAL_BUTTON_FLAG | ControllerPacket.MISC_FLAG |
            ControllerPacket.TOUCHPAD_FLAG | ControllerPacket.PADDLE1_FLAG | ControllerPacket.PADDLE2_FLAG |
            ControllerPacket.PADDLE3_FLAG | ControllerPacket.PADDLE4_FLAG;

    /** The button a controller key press is, by the name shortcuts are saved with, or null. */
    public static String nameOf(android.view.KeyEvent event) {
        switch (event.getKeyCode()) {
            case android.view.KeyEvent.KEYCODE_BUTTON_A: return "A";
            case android.view.KeyEvent.KEYCODE_BUTTON_B: return "B";
            case android.view.KeyEvent.KEYCODE_BUTTON_X: return "X";
            case android.view.KeyEvent.KEYCODE_BUTTON_Y: return "Y";
            case android.view.KeyEvent.KEYCODE_BUTTON_L1: return "LB";
            case android.view.KeyEvent.KEYCODE_BUTTON_R1: return "RB";
            case android.view.KeyEvent.KEYCODE_BUTTON_L2: return "LT";
            case android.view.KeyEvent.KEYCODE_BUTTON_R2: return "RT";
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBL: return "LS";
            case android.view.KeyEvent.KEYCODE_BUTTON_THUMBR: return "RS";
            case android.view.KeyEvent.KEYCODE_BUTTON_START:
            case android.view.KeyEvent.KEYCODE_MENU: return "PLAY";
            case android.view.KeyEvent.KEYCODE_BUTTON_SELECT:
            case android.view.KeyEvent.KEYCODE_BACK: return "BACK";
            case android.view.KeyEvent.KEYCODE_BUTTON_MODE: return "HOME";
            case android.view.KeyEvent.KEYCODE_MEDIA_RECORD: return "SHARE";
            case android.view.KeyEvent.KEYCODE_BUTTON_1: return "TOUCHPAD";
            case android.view.KeyEvent.KEYCODE_DPAD_UP: return "UP";
            case android.view.KeyEvent.KEYCODE_DPAD_DOWN: return "DOWN";
            case android.view.KeyEvent.KEYCODE_DPAD_LEFT: return "LEFT";
            case android.view.KeyEvent.KEYCODE_DPAD_RIGHT: return "RIGHT";
            case android.view.KeyEvent.KEYCODE_UNKNOWN:
                // Paddles have no Android key code, only their evdev one
                switch (event.getScanCode()) {
                    case 0x2c4: return "P1";
                    case 0x2c5: return "P2";
                    case 0x2c6: return "P3";
                    case 0x2c7: return "P4";
                    default: return null;
                }
            default:
                return null;
        }
    }

    /** Button names joined in the order shortcuts are saved, e.g. "BACK+RB". */
    public static String comboOf(java.util.Collection<String> names) {
        List<String> ordered = new ArrayList<>();
        for (String name : BUTTONS.keySet()) {
            if (names.contains(name)) {
                ordered.add(name);
            }
        }
        return ordered.isEmpty() ? OFF : android.text.TextUtils.join("+", ordered);
    }

    /**
     * Why a combination can't be used for this shortcut, or null when it can. One button alone
     * is only allowed when games rarely use it (Home, Share, a paddle), as games would lose it.
     */
    public static String problem(Context c, String combo, String action) {
        int mask = mask(combo);
        if (OFF.equals(combo) || HOME_HOLD.equals(combo)) {
            return null;
        }
        if (mask == 0) {
            return c.getString(R.string.shortcut_problem_unknown);
        }
        if (Integer.bitCount(mask) == 1 && (mask & SPARE_BUTTONS) == 0) {
            return c.getString(R.string.shortcut_problem_one_button);
        }
        if ((mask & ControllerPacket.SPECIAL_BUTTON_FLAG) != 0 && !MENU.equals(action)
                && HOME_HOLD.equals(get(c, MENU))) {
            return c.getString(R.string.shortcut_problem_home);
        }
        return null;
    }

    /** The combinations offered in Settings (the Home hold is offered for the quick menu only). */
    public static final String[] CHOICES = {
            "BACK+RB", "BACK+LB", "BACK+RS", "BACK+LS", "BACK+Y", "BACK+X", "BACK+B", "BACK+A",
            "BACK+UP", "BACK+DOWN", "BACK+LEFT", "BACK+RIGHT", "PLAY+RB", "PLAY+LB", "LS+RS",
            "PLAY+BACK", "PLAY+BACK+LB+RB", OFF,
    };

    private Shortcuts() {}

    public static String defaultFor(String action) {
        for (String[] a : ACTIONS) {
            if (a[0].equals(action)) {
                return a[1];
            }
        }
        return OFF;
    }

    public static String get(Context c, String action) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(c);
        return prefs.getString(action, defaultFor(action));
    }

    /** The buttons of a combination, or 0 for none (off, or the Home hold). */
    public static int mask(String combo) {
        if (combo == null || OFF.equals(combo) || HOME_HOLD.equals(combo)) {
            return 0;
        }
        int mask = 0;
        for (String part : combo.split("\\+")) {
            Integer flag = BUTTONS.get(part.trim());
            if (flag == null) {
                return 0;
            }
            mask |= flag;
        }
        return mask;
    }

    /** "View + R1", in words for the screen. */
    public static String label(Context c, String actionOrCombo) {
        String combo = actionOrCombo.startsWith("tuna_shortcut_") ? get(c, actionOrCombo) : actionOrCombo;
        if (OFF.equals(combo)) {
            return c.getString(R.string.shortcut_off);
        }
        if (HOME_HOLD.equals(combo)) {
            return c.getString(R.string.shortcut_home_hold);
        }
        List<String> names = new ArrayList<>();
        for (String part : combo.split("\\+")) {
            names.add(buttonName(c, part));
        }
        return android.text.TextUtils.join(" + ", names);
    }

    private static String buttonName(Context c, String part) {
        switch (part) {
            case "BACK": return c.getString(R.string.button_view);
            case "PLAY": return c.getString(R.string.button_menu);
            case "LB": return "L1";
            case "RB": return "R1";
            case "LS": return "L3";
            case "RS": return "R3";
            case "UP": return c.getString(R.string.button_up);
            case "DOWN": return c.getString(R.string.button_down);
            case "LEFT": return c.getString(R.string.button_left);
            case "RIGHT": return c.getString(R.string.button_right);
            case "LT": return "L2";
            case "RT": return "R2";
            case "HOME": return c.getString(R.string.button_home);
            case "SHARE": return c.getString(R.string.button_share);
            case "TOUCHPAD": return c.getString(R.string.button_touchpad);
            default: return part;
        }
    }

    /** The shortcut that already uses this combination, other than the given one, or null. */
    public static String usedBy(Context c, String combo, String exceptAction) {
        if (OFF.equals(combo)) {
            return null;
        }
        int mask = mask(combo);
        for (String[] a : ACTIONS) {
            if (a[0].equals(exceptAction)) {
                continue;
            }
            String other = get(c, a[0]);
            if (other.equals(combo) || (mask != 0 && mask(other) == mask)) {
                return a[0];
            }
        }
        return null;
    }

    /** The masks in use during a stream, read once when it starts. */
    public static final class Active {
        public final int screenshot, replay, stats, quit, menu;
        public final boolean menuOnHomeHold;

        public Active(Context c) {
            // Shortcuts are recorded as the buttons are labelled; the game sees A and B (and X and
            // Y) swapped when "Swap A/B and X/Y" is on, and so does the shortcut check
            boolean flip = PreferenceManager.getDefaultSharedPreferences(c).getBoolean("checkbox_flip_face_buttons", false);
            screenshot = flipped(mask(get(c, SCREENSHOT)), flip);
            replay = flipped(mask(get(c, REPLAY)), flip);
            stats = flipped(mask(get(c, STATS)), flip);
            quit = flipped(mask(get(c, QUIT)), flip);
            String m = get(c, MENU);
            menuOnHomeHold = HOME_HOLD.equals(m);
            menu = flipped(mask(m), flip);
        }

        private static int flipped(int mask, boolean flip) {
            if (!flip) {
                return mask;
            }
            int out = mask & ~(ControllerPacket.A_FLAG | ControllerPacket.B_FLAG | ControllerPacket.X_FLAG | ControllerPacket.Y_FLAG);
            if ((mask & ControllerPacket.A_FLAG) != 0) out |= ControllerPacket.B_FLAG;
            if ((mask & ControllerPacket.B_FLAG) != 0) out |= ControllerPacket.A_FLAG;
            if ((mask & ControllerPacket.X_FLAG) != 0) out |= ControllerPacket.Y_FLAG;
            if ((mask & ControllerPacket.Y_FLAG) != 0) out |= ControllerPacket.X_FLAG;
            return out;
        }
    }
}
