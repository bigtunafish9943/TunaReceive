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
            default: return part;
        }
    }

    /** The shortcut that already uses this combination, other than the given one, or null. */
    public static String usedBy(Context c, String combo, String exceptAction) {
        if (OFF.equals(combo)) {
            return null;
        }
        for (String[] a : ACTIONS) {
            if (!a[0].equals(exceptAction) && get(c, a[0]).equals(combo)) {
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
            screenshot = mask(get(c, SCREENSHOT));
            replay = mask(get(c, REPLAY));
            stats = mask(get(c, STATS));
            quit = mask(get(c, QUIT));
            String m = get(c, MENU);
            menuOnHomeHold = HOME_HOLD.equals(m);
            menu = mask(m);
        }
    }
}
