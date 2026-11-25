package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;
import android.view.LayoutInflater;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.preference.PreferenceManager;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Backup and restore of TunaReceive's settings, kept on a PC through TunaStream (the PC's menu).
 * Pairings (this device's identity and the list of paired PCs) are only included when ticked.
 * A restore puts everything back, then TunaReceive closes so it starts with the restored settings.
 */
public final class ReceiverBackup {
    private static final String FORMAT = "tunareceive-backup";
    private static final String[] IDENTITY_FILES = {"client.crt", "client.key", "uniqueid"};
    private static final String COMPUTER_DB = "computers4.db";

    private ReceiverBackup() {}

    // ---------- Making and restoring ----------

    static JSONObject make(Context c, boolean withPairings) throws Exception {
        JSONObject prefs = new JSONObject();
        for (Map.Entry<String, ?> e : PreferenceManager.getDefaultSharedPreferences(c).getAll().entrySet()) {
            Object v = e.getValue();
            JSONObject item = new JSONObject();
            if (v instanceof Boolean) {
                item.put("t", "b");
            } else if (v instanceof Integer) {
                item.put("t", "i");
            } else if (v instanceof Long) {
                item.put("t", "l");
            } else if (v instanceof Float) {
                item.put("t", "f");
            } else if (v instanceof String) {
                item.put("t", "s");
            } else if (v instanceof Set) {
                item.put("t", "ss");
                v = new JSONArray((Set<?>) v);
            } else {
                continue;
            }
            item.put("v", v);
            prefs.put(e.getKey(), item);
        }
        JSONObject files = new JSONObject();
        if (withPairings) {
            for (String name : IDENTITY_FILES) {
                putFile(files, "files/" + name, new File(c.getFilesDir(), name));
            }
            putFile(files, "db/" + COMPUTER_DB, c.getDatabasePath(COMPUTER_DB));
        }
        JSONObject backup = new JSONObject();
        backup.put("format", FORMAT);
        backup.put("version", 1);
        backup.put("made", System.currentTimeMillis() / 1000);
        backup.put("device", android.os.Build.MODEL);
        backup.put("withPairings", withPairings);
        backup.put("prefs", prefs);
        backup.put("files", files);
        return backup;
    }

    private static void putFile(JSONObject files, String name, File file) throws Exception {
        if (!file.isFile() || file.length() > 8 * 1024 * 1024) {
            return;
        }
        byte[] bytes = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int read = 0;
            while (read < bytes.length) {
                int n = in.read(bytes, read, bytes.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
        }
        files.put(name, Base64.encodeToString(bytes, Base64.NO_WRAP));
    }

    static void restore(Context c, JSONObject backup) throws Exception {
        if (!FORMAT.equals(backup.optString("format"))) {
            throw new IllegalArgumentException("not a TunaReceive backup");
        }
        // Decode everything first, so a damaged backup changes nothing
        JSONObject files = backup.optJSONObject("files");
        List<File> targets = new ArrayList<>();
        List<byte[]> contents = new ArrayList<>();
        if (files != null) {
            for (String name : IDENTITY_FILES) {
                if (files.has("files/" + name)) {
                    targets.add(new File(c.getFilesDir(), name));
                    contents.add(Base64.decode(files.getString("files/" + name), Base64.DEFAULT));
                }
            }
            if (files.has("db/" + COMPUTER_DB)) {
                targets.add(c.getDatabasePath(COMPUTER_DB));
                contents.add(Base64.decode(files.getString("db/" + COMPUTER_DB), Base64.DEFAULT));
            }
        }
        JSONObject prefs = backup.optJSONObject("prefs");

        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(c).edit();
        if (prefs != null) {
            editor.clear();
            for (java.util.Iterator<String> it = prefs.keys(); it.hasNext(); ) {
                String key = it.next();
                JSONObject item = prefs.getJSONObject(key);
                switch (item.optString("t")) {
                    case "b": editor.putBoolean(key, item.getBoolean("v")); break;
                    case "i": editor.putInt(key, item.getInt("v")); break;
                    case "l": editor.putLong(key, item.getLong("v")); break;
                    case "f": editor.putFloat(key, (float) item.getDouble("v")); break;
                    case "s": editor.putString(key, item.getString("v")); break;
                    case "ss": {
                        JSONArray a = item.getJSONArray("v");
                        java.util.HashSet<String> set = new java.util.HashSet<>();
                        for (int i = 0; i < a.length(); i++) {
                            set.add(a.getString(i));
                        }
                        editor.putStringSet(key, set);
                        break;
                    }
                    default: break;
                }
            }
        }
        for (int i = 0; i < targets.size(); i++) {
            File target = targets.get(i);
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            if (target.getName().equals(COMPUTER_DB)) {
                // The database's journal files belong to the old one
                new File(target.getPath() + "-wal").delete();
                new File(target.getPath() + "-shm").delete();
                new File(target.getPath() + "-journal").delete();
            }
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(contents.get(i));
            }
        }
        editor.commit();
    }

    // ---------- From the PC's menu ----------

    private static NvHTTP http(Activity a, ComputerDetails computer, String uniqueId) throws Exception {
        return new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(a));
    }

    /** Asks whether to include pairings, then saves the backup on this PC. */
    public static void backUp(final Activity a, final ComputerDetails computer, final String uniqueId) {
        final CheckBox pairings = new CheckBox(a);
        pairings.setText(R.string.backup_include_pairings);
        final TextView note = new TextView(a);
        note.setText(R.string.backup_pairings_note);
        note.setPadding(0, 16, 0, 0);
        LinearLayout layout = new LinearLayout(a);
        layout.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (20 * a.getResources().getDisplayMetrics().density);
        layout.setPadding(pad, pad / 2, pad, 0);
        layout.addView(pairings);
        layout.addView(note);
        UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(R.string.backup_title)
                .setMessage(a.getString(R.string.backup_desc, computer.name))
                .setView(layout)
                .setPositiveButton(R.string.backup_save, (d, w) -> saveOnPc(a, computer, uniqueId, pairings.isChecked()))
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }

    private static void saveOnPc(Activity a, ComputerDetails computer, String uniqueId, boolean withPairings) {
        new Thread(() -> {
            int message;
            try {
                String answer = http(a, computer, uniqueId).sendTunaBackup(make(a, withPairings).toString());
                message = new JSONObject(answer).optBoolean("saved") ? R.string.backup_saved : R.string.backup_failed;
            } catch (FileNotFoundException e) {
                message = R.string.backup_needs_tunastream;
            } catch (Exception e) {
                LimeLog.warning("Backup failed: " + e);
                message = R.string.backup_failed;
            }
            final int shown = message;
            a.runOnUiThread(() -> UiHelper.styleDialog(new AlertDialog.Builder(a)
                    .setTitle(R.string.backup_title)
                    .setMessage(shown)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()));
        }, "TunaReceive backup").start();
    }

    /** Lists the backups kept on this PC and restores the one picked. */
    public static void restoreFromPc(final Activity a, final ComputerDetails computer, final String uniqueId) {
        new Thread(() -> {
            JSONArray list = null;
            int error = 0;
            try {
                list = new JSONObject(http(a, computer, uniqueId).getTunaBackups()).optJSONArray("backups");
            } catch (FileNotFoundException e) {
                error = R.string.backup_needs_tunastream;
            } catch (Exception e) {
                LimeLog.warning("Listing backups failed: " + e);
                error = R.string.backup_failed;
            }
            final JSONArray backups = list;
            final int failed = error;
            a.runOnUiThread(() -> {
                if (failed != 0 || backups == null || backups.length() == 0) {
                    UiHelper.styleDialog(new AlertDialog.Builder(a)
                            .setTitle(R.string.restore_title)
                            .setMessage(failed != 0 ? failed : R.string.restore_none)
                            .setPositiveButton(android.R.string.ok, null)
                            .show());
                    return;
                }
                String[] labels = new String[backups.length()];
                DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT);
                for (int i = 0; i < backups.length(); i++) {
                    JSONObject b = backups.optJSONObject(i);
                    labels[i] = b.optString("device") + ", " + format.format(new Date(b.optLong("made") * 1000));
                }
                UiHelper.styleDialog(new AlertDialog.Builder(a)
                        .setTitle(R.string.restore_title)
                        .setItems(labels, (d, which) -> confirmRestore(a, computer, uniqueId, backups.optJSONObject(which).optString("name")))
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
            });
        }, "TunaReceive restore list").start();
    }

    private static void confirmRestore(Activity a, ComputerDetails computer, String uniqueId, String name) {
        UiHelper.styleDialog(new AlertDialog.Builder(a)
                .setTitle(R.string.restore_title)
                .setMessage(R.string.restore_confirm)
                .setPositiveButton(R.string.restore_button, (d, w) -> new Thread(() -> {
                    try {
                        JSONObject backup = new JSONObject(http(a, computer, uniqueId).getTunaBackup(name));
                        restore(a, backup);
                        a.runOnUiThread(() -> UiHelper.styleDialog(new AlertDialog.Builder(a)
                                .setTitle(R.string.restore_title)
                                .setMessage(R.string.restore_done)
                                .setCancelable(false)
                                .setPositiveButton(android.R.string.ok, (d2, w2) -> {
                                    a.finishAffinity();
                                    // Start fresh, so every screen and the PC list read the restored settings
                                    android.os.Process.killProcess(android.os.Process.myPid());
                                })
                                .show()));
                    } catch (Exception e) {
                        LimeLog.warning("Restore failed: " + e);
                        a.runOnUiThread(() -> UiHelper.styleDialog(new AlertDialog.Builder(a)
                                .setTitle(R.string.restore_title)
                                .setMessage(R.string.restore_failed)
                                .setPositiveButton(android.R.string.ok, null)
                                .show()));
                    }
                }, "TunaReceive restore").start())
                .setNegativeButton(android.R.string.cancel, null)
                .show());
    }
}
