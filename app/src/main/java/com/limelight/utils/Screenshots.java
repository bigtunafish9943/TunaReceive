package com.limelight.utils;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.widget.Toast;

import com.limelight.LimeLog;
import com.limelight.R;
import com.limelight.nvstream.http.NvHTTP;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Screenshots of the stream: the frame on screen is copied from the video surface, saved in the
 * device's Pictures/TunaReceive folder, and sent to the PC, which keeps it in Pictures\TunaStream.
 */
public final class Screenshots {
    private static long lastTakenMs;

    private Screenshots() {}

    public static void take(Activity activity, SurfaceView surface, NvHTTP http, String appName) {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lastTakenMs < 1000 || surface == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return;
        }
        lastTakenMs = now;
        android.graphics.Rect frame = surface.getHolder().getSurfaceFrame();
        int width = Math.max(1, frame.width());
        int height = Math.max(1, frame.height());
        final Bitmap bitmap;
        try {
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        } catch (OutOfMemoryError e) {
            LimeLog.warning("Screenshot: not enough memory for " + width + "x" + height);
            return;
        }
        HandlerThread thread = new HandlerThread("Screenshot");
        thread.start();
        Handler handler = new Handler(thread.getLooper());
        try {
            PixelCopy.request(surface, bitmap, result -> {
                if (result != PixelCopy.SUCCESS) {
                    LimeLog.warning("Screenshot: the frame couldn't be copied (" + result + ")");
                    activity.runOnUiThread(() -> Toast.makeText(activity, R.string.screenshot_failed, Toast.LENGTH_SHORT).show());
                    bitmap.recycle();
                    thread.quitSafely();
                    return;
                }
                // Encoding and saving happen on this thread, away from the game
                save(activity, bitmap, http, appName);
                thread.quitSafely();
            }, handler);
        } catch (IllegalArgumentException e) {
            LimeLog.warning("Screenshot: " + e);
            bitmap.recycle();
            thread.quitSafely();
        }
    }

    private static void save(Activity activity, Bitmap bitmap, NvHTTP http, String appName) {
        ByteArrayOutputStream png = new ByteArrayOutputStream(4 * 1024 * 1024);
        // JPEG at high quality: a 4K PNG takes seconds to encode on a TV box
        bitmap.compress(Bitmap.CompressFormat.JPEG, 95, png);
        bitmap.recycle();
        byte[] bytes = png.toByteArray();

        boolean savedHere = saveOnDevice(activity, bytes, appName);
        boolean savedOnPc = false;
        if (http != null) {
            try {
                savedOnPc = http.sendTunaScreenshot(bytes);
            } catch (Exception e) {
                LimeLog.warning("Screenshot: couldn't send it to the PC: " + e);
            }
        }
        final int message = savedOnPc ? R.string.screenshot_saved_both
                : savedHere ? R.string.screenshot_saved_here : R.string.screenshot_failed;
        activity.runOnUiThread(() -> Toast.makeText(activity, message, Toast.LENGTH_SHORT).show());
    }

    private static boolean saveOnDevice(Activity activity, byte[] bytes, String appName) {
        String name = (appName == null || appName.isEmpty() ? "Stream" : appName.replaceAll("[\\\\/:*?\"<>|]", " ").trim())
                + " " + new SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.ROOT).format(new Date()) + ".jpg";
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Older Android needs a storage permission for Pictures; the PC still gets its copy
            return false;
        }
        ContentResolver resolver = activity.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/TunaReceive");
        Uri uri = null;
        try {
            uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                return false;
            }
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    return false;
                }
                out.write(bytes);
            }
            return true;
        } catch (Exception e) {
            LimeLog.warning("Screenshot: couldn't save it on this device: " + e);
            if (uri != null) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            return false;
        }
    }
}
