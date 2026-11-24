package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.widget.Toast;

import androidx.preference.PreferenceManager;

import com.limelight.BuildConfig;
import com.limelight.LimeLog;
import com.limelight.R;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Keeps TunaReceive up to date from its GitHub releases, the way TunaStream's updater does.
 *
 * An update is installed only when all of these hold:
 *  - it comes from this project's own releases on GitHub, over HTTPS;
 *  - the file is the APK built for this device (or the universal one) with the exact expected name;
 *  - its SHA-256 matches the checksum GitHub publishes for that file (a release without one is refused);
 *  - Android itself accepts it, which it does only when it is signed with the same key as the
 *    installed app. That last check is what stops anyone else's APK from replacing TunaReceive.
 */
public final class TunaUpdater {
    private static final String RELEASES_API =
            "https://api.github.com/repos/bigtunafish9943/TunaReceive/releases/latest";
    private static final String PREF_DISMISSED_CODE = "tuna_update_dismissed_code";
    private static final String PREF_LAST_CHECK = "tuna_update_last_check";
    private static final long AUTO_CHECK_INTERVAL_MS = 6L * 60 * 60 * 1000;
    private static final String ACTION_INSTALL_RESULT = "com.tunastream.receive.UPDATE_INSTALL_RESULT";

    /** A release waiting for the user to allow installs from TunaReceive. */
    private static Release pendingAfterPermission;
    private static boolean busy;

    private TunaUpdater() {}

    public static final class Release {
        public final int code;
        public final String version;
        public final String apkName;
        public final String apkUrl;
        public final String sha256;
        public final long size;

        Release(int code, String version, String apkName, String apkUrl, String sha256, long size) {
            this.code = code;
            this.version = version;
            this.apkName = apkName;
            this.apkUrl = apkUrl;
            this.sha256 = sha256;
            this.size = size;
        }
    }

    // ------------------------------------------------------------------ checking

    /** Reads the latest release. Runs on a background thread. Returns null when none is usable. */
    static Release fetchLatest() throws IOException, JSONException {
        JSONObject json = new JSONObject(readText(RELEASES_API));
        String tag = json.optString("tag_name", "");
        int code = versionCodeFromTag(tag);
        if (code <= 0) {
            LimeLog.warning("Updater: release tag not understood: " + tag);
            return null;
        }
        String version = tag.startsWith("v") ? tag.substring(1) : tag;

        JSONArray assets = json.optJSONArray("assets");
        if (assets == null) {
            return null;
        }

        // The APK for this device's main processor type, or failing that the universal one
        String[] wanted = new String[]{
                "TunaReceive-" + version + "-" + Build.SUPPORTED_ABIS[0] + ".apk",
                "TunaReceive-" + version + "-universal.apk",
        };
        for (String name : wanted) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.getJSONObject(i);
                if (!name.equals(asset.optString("name"))) {
                    continue;
                }
                String digest = asset.optString("digest", "");
                String url = asset.optString("browser_download_url", "");
                if (!digest.startsWith("sha256:") || digest.length() != 7 + 64) {
                    LimeLog.warning("Updater: " + name + " has no published checksum, so it is not offered");
                    return null;
                }
                if (!url.startsWith("https://github.com/bigtunafish9943/TunaReceive/releases/download/")) {
                    LimeLog.warning("Updater: unexpected download address for " + name);
                    return null;
                }
                return new Release(code, version, name, url,
                        digest.substring(7).toLowerCase(Locale.ROOT), asset.optLong("size", 0));
            }
        }
        LimeLog.warning("Updater: release " + tag + " has no APK for " + Build.SUPPORTED_ABIS[0]);
        return null;
    }

    /** "v0.1.42" → 42. Releases are numbered by build, and the build number is also the version code. */
    static int versionCodeFromTag(String tag) {
        int dot = tag.lastIndexOf('.');
        if (dot < 0 || dot == tag.length() - 1) {
            return -1;
        }
        try {
            return Integer.parseInt(tag.substring(dot + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Checks for an update and offers it. When {@code userAsked} is false this is the quiet check
     * made on start: at most every few hours, and a version the user said "Later" to isn't offered again.
     */
    public static void check(final Activity activity, final boolean userAsked) {
        final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(activity);
        if (busy) {
            return;
        }
        if (!userAsked) {
            long last = prefs.getLong(PREF_LAST_CHECK, 0);
            if (System.currentTimeMillis() - last < AUTO_CHECK_INTERVAL_MS) {
                return;
            }
        } else {
            Toast.makeText(activity, R.string.update_checking, Toast.LENGTH_SHORT).show();
        }
        busy = true;

        new Thread(() -> {
            Release release = null;
            String error = null;
            try {
                release = fetchLatest();
            } catch (Exception e) {
                LimeLog.warning("Updater: check failed: " + e);
                error = e.getMessage();
            }
            final Release found = release;
            final String failure = error;
            new Handler(Looper.getMainLooper()).post(() -> {
                busy = false;
                if (activity.isFinishing()) {
                    return;
                }
                if (failure == null) {
                    prefs.edit().putLong(PREF_LAST_CHECK, System.currentTimeMillis()).apply();
                }
                if (found == null || found.code <= BuildConfig.VERSION_CODE) {
                    if (userAsked) {
                        Toast.makeText(activity, failure != null
                                ? activity.getString(R.string.update_check_failed)
                                : activity.getString(R.string.update_up_to_date, BuildConfig.VERSION_NAME),
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                if (!userAsked && prefs.getInt(PREF_DISMISSED_CODE, 0) == found.code) {
                    return;
                }
                offer(activity, found);
            });
        }, "TunaReceive update check").start();
    }

    private static void offer(final Activity activity, final Release release) {
        UiHelper.styleDialog(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_available_title, release.version))
                .setMessage(activity.getString(R.string.update_available_body, BuildConfig.VERSION_NAME))
                .setPositiveButton(R.string.update_now, (d, w) -> startUpdate(activity, release))
                .setNegativeButton(R.string.update_later, (d, w) ->
                        PreferenceManager.getDefaultSharedPreferences(activity).edit()
                                .putInt(PREF_DISMISSED_CODE, release.code).apply())
                .show());
    }

    /** Call from onResume: carries on an update that was waiting for the install permission. */
    public static void resumeIfWaiting(Activity activity) {
        Release release = pendingAfterPermission;
        if (release != null && canInstall(activity)) {
            pendingAfterPermission = null;
            startUpdate(activity, release);
        }
    }

    // ------------------------------------------------------------------ installing

    private static boolean canInstall(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || context.getPackageManager().canRequestPackageInstalls();
    }

    private static void startUpdate(final Activity activity, final Release release) {
        if (!canInstall(activity)) {
            // Android asks once whether TunaReceive may install apps; send the user to that switch
            pendingAfterPermission = release;
            UiHelper.styleDialog(new AlertDialog.Builder(activity)
                    .setTitle(R.string.update_permission_title)
                    .setMessage(R.string.update_permission_body)
                    .setPositiveButton(R.string.update_permission_open, (d, w) -> {
                        try {
                            activity.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                    Uri.parse("package:" + activity.getPackageName())));
                        } catch (ActivityNotFoundException e) {
                            try {
                                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                            } catch (ActivityNotFoundException ignored) {
                                Toast.makeText(activity, R.string.update_permission_manual, Toast.LENGTH_LONG).show();
                            }
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> pendingAfterPermission = null)
                    .show());
            return;
        }

        final boolean[] cancelled = {false};
        final AlertDialog progress = UiHelper.styleDialog(new AlertDialog.Builder(activity)
                .setTitle(activity.getString(R.string.update_downloading_title, release.version))
                .setMessage(activity.getString(R.string.update_downloading, 0))
                .setCancelable(false)
                .setNegativeButton(android.R.string.cancel, (d, w) -> cancelled[0] = true)
                .show());
        busy = true;

        final Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String failure = null;
            File apk = null;
            try {
                apk = download(activity, release, percent -> ui.post(() ->
                        progress.setMessage(activity.getString(R.string.update_downloading, percent))), cancelled);
            } catch (Exception e) {
                LimeLog.warning("Updater: download failed: " + e);
                failure = e.getMessage();
            }
            final File done = apk;
            final String error = failure;
            ui.post(() -> {
                busy = false;
                if (progress.isShowing()) {
                    progress.dismiss();
                }
                if (cancelled[0]) {
                    return;
                }
                if (done == null) {
                    UiHelper.styleDialog(new AlertDialog.Builder(activity)
                            .setTitle(R.string.update_failed_title)
                            .setMessage(error != null ? error : activity.getString(R.string.update_check_failed))
                            .setPositiveButton(android.R.string.ok, null)
                            .show());
                    return;
                }
                try {
                    install(activity, done);
                } catch (IOException e) {
                    LimeLog.warning("Updater: install failed: " + e);
                    Toast.makeText(activity, activity.getString(R.string.update_install_failed, e.getMessage()),
                            Toast.LENGTH_LONG).show();
                }
            });
        }, "TunaReceive update download").start();
    }

    interface ProgressListener {
        void onProgress(int percent);
    }

    /** Downloads the APK and checks it against GitHub's published SHA-256. Returns null if cancelled. */
    static File download(Context context, Release release, ProgressListener listener, boolean[] cancelled)
            throws IOException {
        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Couldn't make a folder for the download");
        }
        // Only ever keep the update being downloaded now
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        File out = new File(dir, release.apkName);

        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }

        HttpURLConnection conn = open(release.apkUrl);
        try (InputStream in = conn.getInputStream(); OutputStream os = new FileOutputStream(out)) {
            long total = release.size > 0 ? release.size : conn.getContentLength();
            byte[] buf = new byte[64 * 1024];
            long got = 0;
            int lastPercent = -1;
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancelled[0]) {
                    break;
                }
                os.write(buf, 0, n);
                sha.update(buf, 0, n);
                got += n;
                if (total > 0) {
                    int percent = (int) Math.min(100, got * 100 / total);
                    if (percent != lastPercent) {
                        lastPercent = percent;
                        listener.onProgress(percent);
                    }
                }
            }
        } finally {
            conn.disconnect();
        }
        if (cancelled[0]) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            return null;
        }

        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) {
            hex.append(String.format(Locale.ROOT, "%02x", b));
        }
        if (!hex.toString().equals(release.sha256)) {
            //noinspection ResultOfMethodCallIgnored
            out.delete();
            throw new IOException(context.getString(R.string.update_checksum_mismatch));
        }
        return out;
    }

    /** Hands the checked APK to Android, which asks the user to confirm and verifies the signature. */
    static void install(Context context, File apk) throws IOException {
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(context.getPackageName());
        params.setSize(apk.length());

        int sessionId = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(sessionId)) {
            try (InputStream in = new FileInputStream(apk);
                 OutputStream out = session.openWrite("TunaReceive.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
                session.fsync(out);
            }

            Intent result = new Intent(context, InstallResultReceiver.class).setAction(ACTION_INSTALL_RESULT);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // The system adds the result to this intent, so it has to be mutable
                flags |= PendingIntent.FLAG_MUTABLE;
            }
            PendingIntent pending = PendingIntent.getBroadcast(context, sessionId, result, flags);
            session.commit(pending.getIntentSender());
        }
    }

    /** Receives Android's answer to an install: show its confirmation screen, or say what went wrong. */
    public static class InstallResultReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        context.startActivity(confirm);
                    } catch (ActivityNotFoundException e) {
                        LimeLog.warning("Updater: no install confirmation screen: " + e);
                    }
                }
            } else if (status != PackageInstaller.STATUS_SUCCESS) {
                String message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                LimeLog.warning("Updater: install refused (" + status + "): " + message);
                int text = status == PackageInstaller.STATUS_FAILURE_CONFLICT
                        || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                        ? R.string.update_signature_mismatch : R.string.update_install_cancelled;
                Toast.makeText(context, text, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ------------------------------------------------------------------ network

    private static HttpURLConnection open(String address) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(address).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("User-Agent", "TunaReceive/" + BuildConfig.VERSION_NAME);
        if (address.startsWith("https://api.github.com/")) {
            conn.setRequestProperty("Accept", "application/vnd.github+json");
        }
        int code = conn.getResponseCode();
        if (code != HttpURLConnection.HTTP_OK) {
            conn.disconnect();
            throw new IOException("GitHub answered " + code);
        }
        return conn;
    }

    private static String readText(String address) throws IOException {
        HttpURLConnection conn = open(address);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bytes.write(buf, 0, n);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            conn.disconnect();
        }
    }
}
