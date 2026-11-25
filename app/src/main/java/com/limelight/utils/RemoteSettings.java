package com.limelight.utils;

import android.app.Activity;
import android.content.SharedPreferences;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;
import com.limelight.preferences.PreferenceConfiguration;
import com.limelight.profiles.ProfilesManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * TunaReceive's settings, changeable from TunaStream's page on the PC (Devices > TV settings).
 *
 * While the games are on screen, TunaReceive sends the PC its everyday settings (their current
 * values and choices) and gets back any changes made on the PC since, which it saves at once.
 * They are used from the next stream. Only the settings listed here can be changed, and only to
 * one of their own choices.
 */
public final class RemoteSettings {
    private RemoteSettings() {}

    private static final String APPLIED_REVISION = "tuna_remote_settings_revision_";
    private static final long SYNC_EVERY_MS = 15000;
    private static long lastSync = -SYNC_EVERY_MS;
    private static boolean busy;

    private enum Kind { BOOL, LIST, KBPS }

    private static final class Item {
        final String section, key;
        final int title;
        final Kind kind;
        final Object fallback;
        final int names, values;  // string arrays of a list

        Item(String section, String key, int title, Kind kind, Object fallback, int names, int values) {
            this.section = section;
            this.key = key;
            this.title = title;
            this.kind = kind;
            this.fallback = fallback;
            this.names = names;
            this.values = values;
        }
    }

    private static Item bool(String section, String key, int title, boolean fallback) {
        return new Item(section, key, title, Kind.BOOL, fallback, 0, 0);
    }

    private static Item list(String section, String key, int title, String fallback, int names, int values) {
        return new Item(section, key, title, Kind.LIST, fallback, names, values);
    }

    private static final Item[] ITEMS = {
            list("picture", "list_resolution", R.string.title_resolution_list, "1280x720", R.array.resolution_names, R.array.resolution_values),
            list("picture", "list_fps", R.string.title_fps_list, PreferenceConfiguration.DEFAULT_FPS, R.array.fps_names, R.array.fps_values),
            new Item("picture", "seekbar_bitrate_kbps", R.string.title_seekbar_bitrate, Kind.KBPS, 0, 0, 0),
            bool("picture", "tuna_adaptive_quality", R.string.tuna_adaptive_quality, true),
            bool("picture", "tuna_adaptive_size", R.string.tuna_adaptive_size, true),
            bool("picture", "checkbox_enable_hdr", R.string.title_enable_hdr, false),
            bool("picture", "tuna_10bit", R.string.tuna_10bit, true),
            list("picture", "tuna_sharpen", R.string.sharpen_title, "off", R.array.sharpen_names, R.array.sharpen_values),
            bool("picture", "checkbox_use_virtual_display", R.string.title_checkbox_use_virtual_display, true),
            list("sound", "list_audio_config", R.string.title_audio_config_list, "2", R.array.audio_config_names, R.array.audio_config_values),
            bool("sound", "checkbox_host_audio", R.string.title_checkbox_host_audio, false),
            bool("controller", "checkbox_enable_rumble", R.string.title_enable_rumble, true),
            bool("controller", "checkbox_flip_face_buttons", R.string.title_checkbox_flip_face_buttons, false),
            list("controller", Shortcuts.MENU, R.string.shortcut_menu_title, null, 0, 0),
            list("controller", Shortcuts.SCREENSHOT, R.string.shortcut_screenshot_title, null, 0, 0),
            list("controller", Shortcuts.REPLAY, R.string.shortcut_replay_title, null, 0, 0),
            list("controller", Shortcuts.STATS, R.string.shortcut_stats_title, null, 0, 0),
            list("controller", Shortcuts.QUIT, R.string.shortcut_quit_title, null, 0, 0),
            bool("tv", "tuna_checkup_before_game", R.string.checkup_setting, true),
            bool("tv", "tuna_open_only_pc", R.string.tuna_open_only_pc, true),
            bool("tv", "tuna_tv_game_mode", R.string.tuna_tv_game_mode, true),
            bool("tv", "tuna_auto_reconnect", R.string.tuna_auto_reconnect, true),
            bool("tv", "checkbox_enable_perf_overlay", R.string.title_enable_perf_overlay, false),
            bool("tv", "tuna_match_pc_theme", R.string.tuna_match_pc_theme, true),
            list("tv", "tuna_theme", R.string.tuna_theme, "deepsea", R.array.tuna_theme_names, R.array.tuna_theme_ids),
            list("tv", "list_languages", R.string.title_language_list, PreferenceConfiguration.DEFAULT_LANGUAGE, R.array.language_names, R.array.language_values),
    };

    private static boolean isShortcut(Item item) {
        return item.key.startsWith("tuna_shortcut_");
    }

    /** The choices of a list setting: [value, label] pairs. */
    private static List<String[]> choices(Activity a, Item item) {
        List<String[]> out = new ArrayList<>();
        if (isShortcut(item)) {
            if (Shortcuts.MENU.equals(item.key)) {
                out.add(new String[]{Shortcuts.HOME_HOLD, Shortcuts.label(a, Shortcuts.HOME_HOLD)});
            }
            for (String combo : Shortcuts.CHOICES) {
                out.add(new String[]{combo, Shortcuts.label(a, combo)});
            }
            // A combination pressed in on the TV (any buttons) is a choice too
            String current = Shortcuts.get(a, item.key);
            boolean listed = false;
            for (String[] c : out) {
                listed |= c[0].equals(current);
            }
            if (!listed) {
                out.add(new String[]{current, Shortcuts.label(a, current)});
            }
            return out;
        }
        String[] names = a.getResources().getStringArray(item.names);
        String[] values = a.getResources().getStringArray(item.values);
        for (int i = 0; i < Math.min(names.length, values.length); i++) {
            out.add(new String[]{values[i], names[i]});
        }
        return out;
    }

    /** What the PC is told: each setting with its current value and its choices. */
    private static JSONObject describe(Activity a, long appliedRevision) throws Exception {
        // Settings in use: a profile picked on the TV can override some of them
        SharedPreferences prefs = ProfilesManager.getInstance().getOverlayingSharedPreferences(a);
        JSONArray settings = new JSONArray();
        for (Item item : ITEMS) {
            JSONObject s = new JSONObject();
            s.put("key", item.key);
            s.put("section", item.section);
            s.put("title", a.getString(item.title));
            int summary = summaryOf(item.key);
            if (summary != 0) {
                s.put("summary", a.getString(summary));
            }
            switch (item.kind) {
                case BOOL:
                    s.put("type", "bool");
                    s.put("value", prefs.getBoolean(item.key, (Boolean) item.fallback));
                    break;
                case KBPS: {
                    s.put("type", "kbps");
                    int kbps = prefs.getInt(item.key, 0);
                    s.put("value", kbps > 0 ? kbps : PreferenceConfiguration.getDefaultBitrate(a));
                    s.put("min", 500);
                    s.put("max", 300000);
                    s.put("step", 500);
                    break;
                }
                default: {
                    s.put("type", "list");
                    String fallback = isShortcut(item) ? Shortcuts.defaultFor(item.key) : (String) item.fallback;
                    s.put("value", prefs.getString(item.key, fallback));
                    JSONArray options = new JSONArray();
                    for (String[] c : choices(a, item)) {
                        options.put(new JSONObject().put("value", c[0]).put("label", c[1]));
                    }
                    s.put("choices", options);
                    break;
                }
            }
            settings.put(s);
        }
        JSONObject out = new JSONObject();
        out.put("applied", appliedRevision);
        out.put("settings", settings);
        out.put("profile", ProfilesManager.getInstance().getActiveName());
        out.put("version", a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName);
        return out;
    }

    /** The setting's description in TunaReceive's own settings, or 0. */
    private static int summaryOf(String key) {
        switch (key) {
            case "list_resolution": return R.string.summary_resolution_list;
            case "list_fps": return R.string.summary_fps_list;
            case "seekbar_bitrate_kbps": return R.string.summary_seekbar_bitrate;
            case "tuna_adaptive_quality": return R.string.tuna_adaptive_quality_summary;
            case "tuna_adaptive_size": return R.string.tuna_adaptive_size_summary;
            case "checkbox_enable_hdr": return R.string.summary_enable_hdr;
            case "tuna_10bit": return R.string.tuna_10bit_summary;
            case "tuna_sharpen": return R.string.sharpen_summary;
            case "checkbox_use_virtual_display": return R.string.summary_checkbox_use_virtual_display;
            case "list_audio_config": return R.string.summary_audio_config_list;
            case "checkbox_host_audio": return R.string.summary_checkbox_host_audio;
            case "checkbox_enable_rumble": return R.string.summary_enable_rumble;
            case "checkbox_flip_face_buttons": return R.string.summary_checkbox_flip_face_buttons;
            case "tuna_checkup_before_game": return R.string.checkup_setting_summary;
            case "tuna_open_only_pc": return R.string.tuna_open_only_pc_summary;
            case "tuna_tv_game_mode": return R.string.tuna_tv_game_mode_summary;
            case "tuna_auto_reconnect": return R.string.tuna_auto_reconnect_summary;
            case "checkbox_enable_perf_overlay": return R.string.summary_enable_perf_overlay;
            case "tuna_match_pc_theme": return R.string.tuna_match_pc_theme_summary;
            case "list_languages": return R.string.summary_language_list;
            default: return 0;
        }
    }

    private static Item find(String key) {
        for (Item item : ITEMS) {
            if (item.key.equals(key)) {
                return item;
            }
        }
        return null;
    }

    /**
     * Save the changes made on the PC. A value that isn't one of the setting's choices is skipped.
     * @return The titles of the settings that changed.
     */
    private static List<String> apply(Activity a, JSONObject changes) {
        List<String> changed = new ArrayList<>();
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(a);
        SharedPreferences.Editor edit = prefs.edit();
        for (Iterator<String> keys = changes.keys(); keys.hasNext(); ) {
            String key = keys.next();
            Item item = find(key);
            if (item == null) {
                continue;
            }
            try {
                switch (item.kind) {
                    case BOOL:
                        edit.putBoolean(key, changes.getBoolean(key));
                        break;
                    case KBPS: {
                        int kbps = changes.getInt(key);
                        if (kbps < 500 || kbps > 300000) {
                            continue;
                        }
                        edit.putInt(key, Math.round(kbps / 500f) * 500);
                        break;
                    }
                    default: {
                        String value = changes.getString(key);
                        boolean known = false;
                        for (String[] c : choices(a, item)) {
                            known |= c[0].equals(value);
                        }
                        if (!known) {
                            continue;
                        }
                        // Two controller shortcuts never share buttons
                        if (isShortcut(item) && !Shortcuts.OFF.equals(value)) {
                            String other = Shortcuts.usedBy(a, value, key);
                            if (other != null && !changes.has(other)) {
                                edit.putString(other, Shortcuts.OFF);
                            }
                        }
                        edit.putString(key, value);
                        break;
                    }
                }
                changed.add(a.getString(item.title));
            } catch (Exception e) {
                LimeLog.warning("Settings from the PC: skipped " + key + ": " + e);
            }
        }
        edit.apply();
        return changed;
    }

    /**
     * Send the settings to the PC and save any changes made there. Runs in the background; says on
     * screen which settings were changed.
     */
    public static void sync(final Activity a, final ComputerDetails computer, final String uniqueId) {
        if (computer == null || uniqueId == null || computer.serverCert == null) {
            return;
        }
        synchronized (RemoteSettings.class) {
            long now = android.os.SystemClock.elapsedRealtime();
            if (busy || now - lastSync < SYNC_EVERY_MS) {
                return;
            }
            busy = true;
            lastSync = now;
        }
        new Thread(() -> {
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(a));
                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(a);
                String revisionKey = APPLIED_REVISION + computer.uuid;
                long applied = prefs.getLong(revisionKey, 0);
                JSONObject answer = new JSONObject(http.sendTunaSettings(describe(a, applied).toString()));
                long revision = answer.optLong("revision", 0);
                JSONObject changes = answer.optJSONObject("changes");
                if (revision <= applied || changes == null || changes.length() == 0) {
                    return;
                }
                final List<String> changed = apply(a, changes);
                prefs.edit().putLong(revisionKey, revision).apply();
                // Tell the PC they are in, with the new values
                http.sendTunaSettings(describe(a, revision).toString());
                if (!changed.isEmpty()) {
                    final String list = android.text.TextUtils.join(", ", changed);
                    a.runOnUiThread(() -> Toast.makeText(a.getApplicationContext(), a.getString(R.string.remote_settings_applied, list),
                            Toast.LENGTH_LONG).show());
                }
            } catch (FileNotFoundException e) {
                // An older TunaStream, or not TunaStream at all
            } catch (Exception e) {
                LimeLog.warning("Settings from the PC: " + e);
            } finally {
                synchronized (RemoteSettings.class) {
                    busy = false;
                }
            }
        }).start();
    }
}
