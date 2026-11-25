package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.limelight.ProfilesActivity;
import com.limelight.R;
import com.limelight.profiles.ProfilesManager;
import com.limelight.profiles.SettingsProfile;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-game settings: a game can remember one of the settings profiles, and that profile is
 * used whenever the game is streamed (say 4K with HDR for a slow game, 1080p at 120 FPS for a
 * shooter). Other games keep using the usual settings. Nothing changes outside the stream.
 */
public final class GameProfiles {
    private GameProfiles() {}

    private static String key(String pcUuid, int appId) {
        return "tuna_game_profile_" + pcUuid + "_" + appId;
    }

    /** The profile this game uses, or null for the usual settings. */
    public static SettingsProfile of(Context c, String pcUuid, int appId) {
        if (pcUuid == null) {
            return null;
        }
        String id = PreferenceManager.getDefaultSharedPreferences(c).getString(key(pcUuid, appId), null);
        if (id == null) {
            return null;
        }
        ProfilesManager pm = ProfilesManager.getInstance();
        for (SettingsProfile p : pm.getProfiles()) {
            if (p.getUuid().toString().equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** The settings this game's profile sets, which the check before a game leaves alone. */
    public static Set<String> keysSetBy(Context c, String pcUuid, int appId) {
        SettingsProfile p = of(c, pcUuid, appId);
        if (p == null || p.getOptions() == null) {
            return Collections.emptySet();
        }
        return new HashSet<>(p.getOptions().keySet());
    }

    /** Use the game's profile for this stream. Returns its name, or null when there's none. */
    public static String beginStream(Context c, String pcUuid, int appId) {
        SettingsProfile p = of(c, pcUuid, appId);
        ProfilesManager.getInstance().setStreamProfile(p == null ? null : p.getUuid());
        return p == null ? null : p.getName();
    }

    public static void endStream() {
        ProfilesManager.getInstance().setStreamProfile(null);
    }

    /** The games list's "Settings for this game". */
    public static void choose(final Activity a, final String pcUuid, final int appId, final String appName) {
        ProfilesManager pm = ProfilesManager.getInstance();
        final List<SettingsProfile> profiles = new ArrayList<>(pm.getProfiles());
        if (profiles.isEmpty()) {
            UiHelper.styleDialog(new AlertDialog.Builder(a)
                    .setTitle(a.getString(R.string.game_profile_title, appName))
                    .setMessage(R.string.game_profile_none_yet)
                    .setPositiveButton(R.string.game_profile_make, (d, w) -> a.startActivity(new Intent(a, ProfilesActivity.class)))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show());
            return;
        }
        Collections.sort(profiles, (x, y) -> x.getName().compareToIgnoreCase(y.getName()));
        SettingsProfile current = of(a, pcUuid, appId);
        final String[] names = new String[profiles.size() + 1];
        names[0] = a.getString(R.string.game_profile_usual);
        int checked = 0;
        for (int i = 0; i < profiles.size(); i++) {
            names[i + 1] = profiles.get(i).getName();
            if (current != null && current.getUuid().equals(profiles.get(i).getUuid())) {
                checked = i + 1;
            }
        }
        UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.game_profile_title, appName))
                .setSingleChoiceItems(names, checked, (d, which) -> {
                    SharedPreferences.Editor e = PreferenceManager.getDefaultSharedPreferences(a).edit();
                    if (which == 0) {
                        e.remove(key(pcUuid, appId));
                    } else {
                        UUID id = profiles.get(which - 1).getUuid();
                        e.putString(key(pcUuid, appId), id.toString());
                    }
                    e.apply();
                    Toast.makeText(a, which == 0 ? a.getString(R.string.game_profile_saved_usual, appName)
                            : a.getString(R.string.game_profile_saved, appName, names[which]), Toast.LENGTH_SHORT).show();
                    d.dismiss();
                })
                .setNeutralButton(R.string.game_profile_edit, (d, w) -> a.startActivity(new Intent(a, ProfilesActivity.class)))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }
}
