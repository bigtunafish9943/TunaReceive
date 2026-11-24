package com.limelight.utils;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.webkit.MimeTypeMap;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.binding.PlatformBinding;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import okhttp3.ResponseBody;

/**
 * "Send to device": collects files TunaStream is sending to this device, saves them in
 * Downloads and says so. APKs can be installed straight away.
 *
 * Checked while TunaReceive is open on a paired PC's games, at most every few seconds.
 * A PC whose TunaStream doesn't offer this is remembered and not asked again this run.
 */
public final class FileReceiver {
    private static final long CHECK_EVERY_MS = 10_000;
    private static final Map<String, Long> lastCheck = new HashMap<>();
    private static final Map<String, Boolean> unsupported = new HashMap<>();
    // A file that keeps failing is left alone after a few tries, until TunaReceive restarts
    private static final Map<String, Integer> failures = new HashMap<>();
    private static final int MAX_TRIES = 3;
    private static boolean busy;

    private FileReceiver() {}

    private static final class Received {
        final String name;
        final Uri uri;
        final String mime;

        Received(String name, Uri uri, String mime) {
            this.name = name;
            this.uri = uri;
            this.mime = mime;
        }
    }

    /** Call often (on start and when the PC answers a poll); it only asks the PC every few seconds. */
    public static void check(final Activity activity, final ComputerDetails computer, final String uniqueId) {
        if (computer == null || computer.uuid == null || computer.serverCert == null || uniqueId == null
                || computer.state != ComputerDetails.State.ONLINE) {
            return;
        }
        synchronized (FileReceiver.class) {
            long now = android.os.SystemClock.elapsedRealtime();
            Long last = lastCheck.get(computer.uuid);
            if (busy || Boolean.TRUE.equals(unsupported.get(computer.uuid))
                    || (last != null && now - last < CHECK_EVERY_MS)) {
                return;
            }
            lastCheck.put(computer.uuid, now);
            busy = true;
        }

        new Thread(() -> {
            List<Received> received = new ArrayList<>();
            String failure = null;
            try {
                NvHTTP http = new NvHTTP(ServerHelper.getCurrentAddressFromComputer(computer), computer.httpsPort,
                        uniqueId, computer.serverCert, PlatformBinding.getCryptoProvider(activity));
                JSONArray files = new JSONObject(http.getTunaFiles()).optJSONArray("files");
                for (int i = 0; files != null && i < files.length(); i++) {
                    JSONObject file = files.getJSONObject(i);
                    String id = file.getString("id");
                    String name = file.optString("name", "file");
                    String key = computer.uuid + "/" + id;
                    int tries;
                    synchronized (FileReceiver.class) {
                        Integer t = failures.get(key);
                        tries = t == null ? 0 : t;
                    }
                    if (tries >= MAX_TRIES) {
                        continue;
                    }
                    try {
                        received.add(save(activity, http, id, name));
                        http.tunaFileReceived(id);
                    } catch (IOException e) {
                        LimeLog.warning("Couldn't receive " + name + ": " + e);
                        synchronized (FileReceiver.class) {
                            failures.put(key, tries + 1);
                        }
                        failure = activity.getString(tries + 1 >= MAX_TRIES ? R.string.files_gave_up : R.string.files_failed, name);
                    }
                }
            } catch (FileNotFoundException e) {
                // This PC's TunaStream doesn't send files: don't ask it again this run
                synchronized (FileReceiver.class) {
                    unsupported.put(computer.uuid, true);
                }
            } catch (Exception e) {
                LimeLog.info("File check skipped: " + e);
            } finally {
                synchronized (FileReceiver.class) {
                    busy = false;
                }
            }

            final String error = failure;
            if (received.isEmpty() && error == null) {
                return;
            }
            activity.runOnUiThread(() -> {
                if (activity.isFinishing()) {
                    return;
                }
                if (error != null) {
                    Toast.makeText(activity, error, Toast.LENGTH_LONG).show();
                }
                if (!received.isEmpty()) {
                    announce(activity, computer.name, received);
                }
            });
        }, "TunaReceive file check").start();
    }

    private static String mimeOf(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        if (ext.equals("apk")) {
            return "application/vnd.android.package-archive";
        }
        String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
        return mime != null ? mime : "application/octet-stream";
    }

    /** Saves to Downloads (Android 10+) or the app's own downloads folder (older Android). */
    private static Received save(Activity activity, NvHTTP http, String id, String name) throws IOException {
        String mime = mimeOf(name);
        try (ResponseBody body = http.openTunaFile(id)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentResolver resolver = activity.getContentResolver();
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, mime);
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (uri == null) {
                    throw new IOException("Downloads isn't available");
                }
                try (InputStream in = body.byteStream(); OutputStream out = resolver.openOutputStream(uri)) {
                    if (out == null) {
                        throw new IOException("Downloads isn't writable");
                    }
                    copy(in, out);
                } catch (IOException e) {
                    resolver.delete(uri, null, null);
                    throw e;
                }
                values.clear();
                values.put(MediaStore.Downloads.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
                return new Received(name, uri, mime);
            } else {
                File dir = activity.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) {
                    dir = new File(activity.getFilesDir(), "downloads");
                }
                if (!dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("Couldn't make a downloads folder");
                }
                File file = new File(dir, name);
                try (InputStream in = body.byteStream(); OutputStream out = new FileOutputStream(file)) {
                    copy(in, out);
                }
                Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".fileprovider", file);
                return new Received(name, uri, mime);
            }
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[128 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
    }

    private static void announce(Activity activity, String pcName, List<Received> received) {
        StringBuilder names = new StringBuilder();
        for (Received r : received) {
            names.append("\n• ").append(r.name);
        }
        AlertDialog.Builder dialog = new AlertDialog.Builder(activity)
                .setTitle(activity.getResources().getQuantityString(R.plurals.files_received_title, received.size(),
                        received.size(), pcName))
                .setMessage(activity.getString(R.string.files_received_body, names.toString()))
                .setNegativeButton(android.R.string.ok, null);

        if (received.size() == 1) {
            final Received only = received.get(0);
            boolean apk = only.mime.equals("application/vnd.android.package-archive");
            dialog.setPositiveButton(apk ? R.string.files_install : R.string.files_open, (d, w) -> {
                Intent open = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(only.uri, only.mime)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    activity.startActivity(open);
                } catch (ActivityNotFoundException e) {
                    Toast.makeText(activity, R.string.files_no_app, Toast.LENGTH_LONG).show();
                }
            });
        }
        dialog.show();
    }
}
