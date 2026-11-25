package com.limelight.utils;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;

import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Themes, named the same as in TunaStream. By default TunaReceive wears the theme chosen on the
 * PC (TunaStream reports it with its other details); Settings can turn that off and pick one.
 *
 * A theme is laid over each screen's own theme just before the screen is built, so every colour
 * on it follows. Screens already open when the theme changes are rebuilt when next shown.
 * Android 10 and later; older devices keep Deep Sea.
 */
public final class TunaTheme {
    public static final String PREF_MATCH_PC = "tuna_match_pc_theme";
    public static final String PREF_OWN = "tuna_theme";
    public static final String PREF_PC = "tuna_pc_theme";
    public static final String DEFAULT = "deepsea";

    /** Theme ids, as TunaStream names them. */
    public static final String[] IDS = {"deepsea", "reef", "kelp", "midnight", "gold",
            "halloween", "autumn", "winter", "summer"};
    private static final int[] STYLES = {
            R.style.TunaPaletteDeepSea, R.style.TunaPaletteReef, R.style.TunaPaletteKelp,
            R.style.TunaPaletteMidnight, R.style.TunaPaletteGold,
            R.style.TunaPaletteHalloween, R.style.TunaPaletteAutumn, R.style.TunaPaletteWinter,
            R.style.TunaPaletteSummer,
    };
    /** Picked in Settings: the season's theme in its season, Deep Sea otherwise (as on the PC). */
    public static final String SEASONAL = "seasonal";

    // Bumped whenever the theme in use changes; screens built before that are rebuilt
    private static int generation;
    private static final Map<Activity, Integer> built = new WeakHashMap<>();

    private TunaTheme() {
    }

    public static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q;
    }

    /** The theme to wear now: the PC's, unless Settings picked one. */
    public static String current(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String id = prefs.getBoolean(PREF_MATCH_PC, true)
                ? prefs.getString(PREF_PC, DEFAULT)
                : prefs.getString(PREF_OWN, DEFAULT);
        if (SEASONAL.equals(id)) {
            id = season();
        }
        return styleOf(id) != 0 ? id : DEFAULT;
    }

    /** The same seasons TunaStream uses. */
    static String season() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        int month = now.get(java.util.Calendar.MONTH) + 1;
        int day = now.get(java.util.Calendar.DAY_OF_MONTH);
        if (month == 10) {
            return "halloween";
        }
        if (month == 11) {
            return "autumn";
        }
        if (month == 12 || (month == 1 && day <= 15)) {
            return "winter";
        }
        if ((month == 6 && day >= 21) || month == 7 || month == 8) {
            return "summer";
        }
        return DEFAULT;
    }

    private static int styleOf(String id) {
        for (int i = 0; i < IDS.length; i++) {
            if (IDS[i].equals(id)) {
                return STYLES[i];
            }
        }
        return 0;
    }

    /** Lays the theme over every screen as it is built. Call once, from the Application. */
    public static void install(Application app) {
        if (!supported()) {
            return;
        }
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityPreCreated(Activity activity, Bundle savedInstanceState) {
                activity.getTheme().applyStyle(styleOf(current(activity)), true);
                built.put(activity, generation);
            }

            @Override
            public void onActivityResumed(Activity activity) {
                Integer at = built.get(activity);
                if (at != null && at != generation) {
                    // Built in another theme: build it again in this one
                    built.put(activity, generation);
                    activity.recreate();
                }
            }

            @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { }
            @Override public void onActivityStarted(Activity activity) { }
            @Override public void onActivityPaused(Activity activity) { }
            @Override public void onActivityStopped(Activity activity) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) { }
            @Override public void onActivityDestroyed(Activity activity) { }
        });
    }

    /** The theme setting changed: rebuild screens, starting with the one showing. */
    public static void changed(Activity showing) {
        if (!supported()) {
            return;
        }
        generation++;
        if (showing != null) {
            built.put(showing, generation);
            showing.recreate();
        }
    }

    /**
     * A PC reported its theme. Kept, and when TunaReceive is matching the PC and the theme is
     * new, the showing screen is rebuilt in it.
     */
    public static void fromPc(Activity showing, String id) {
        if (id == null || styleOf(id) == 0) {
            return;
        }
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(showing);
        if (id.equals(prefs.getString(PREF_PC, DEFAULT))) {
            return;
        }
        prefs.edit().putString(PREF_PC, id).apply();
        LimeLog.info("PC theme is now " + id);
        // The PC's own pictures (Desktop, Steam, ...) wear its theme too
        com.limelight.grid.assets.DiskAssetLoader.refreshAll(showing);
        if (prefs.getBoolean(PREF_MATCH_PC, true)) {
            changed(showing);
        }
    }
}
