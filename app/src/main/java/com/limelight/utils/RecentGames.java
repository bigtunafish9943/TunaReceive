package com.limelight.utils;

import android.content.Context;
import android.content.SharedPreferences;

/** Remembers when each game on each PC was last started, so the games list can put them first. */
public final class RecentGames {
    private static final String FILE = "tuna_recent_games";

    private RecentGames() {}

    private static String key(String pcUuid, int appId) {
        return pcUuid + "/" + appId;
    }

    public static void recordPlayed(Context context, String pcUuid, int appId) {
        if (pcUuid == null) {
            return;
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putLong(key(pcUuid, appId), System.currentTimeMillis()).apply();
    }

    /** When it was last started from this device, or 0 if never. */
    public static long lastPlayed(Context context, String pcUuid, int appId) {
        if (pcUuid == null) {
            return 0;
        }
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        return prefs.getLong(key(pcUuid, appId), 0);
    }
}
