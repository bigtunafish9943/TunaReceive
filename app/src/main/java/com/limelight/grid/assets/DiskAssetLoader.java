package com.limelight.grid.assets;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.os.Build;

import com.limelight.LimeLog;
import com.limelight.utils.CacheHelper;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class DiskAssetLoader {
    // 5 MB
    private static final long MAX_ASSET_SIZE = 5 * 1024 * 1024;

    // Standard box art is 300x400
    private static final int STANDARD_ASSET_WIDTH = 300;
    private static final int STANDARD_ASSET_HEIGHT = 400;

    private final boolean isLowRamDevice;
    private final File cacheDir;

    // Pictures are fetched again after a few days, and once after TunaReceive starts asking for
    // new ones, so covers TunaStream finds later (such as Steam's) replace the plain box
    private static final long REFRESH_AFTER_MS = 3L * 24 * 60 * 60 * 1000;
    static final String PREF_REFRESH_SINCE = "tuna_boxart_refresh_since";
    private static final String PREF_THEMED_PICTURES = "tuna_boxart_themed";

    /** Fetch every picture again (the PC's theme changed, and its own pictures with it). */
    public static void refreshAll(Context context) {
        android.preference.PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putLong(PREF_REFRESH_SINCE, System.currentTimeMillis()).apply();
    }
    private final long refreshSince;

    public DiskAssetLoader(Context context) {
        this.cacheDir = context.getCacheDir();
        android.content.SharedPreferences prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context);
        long since = prefs.getLong(PREF_REFRESH_SINCE, 0);
        // Once: TunaStream's own pictures (Desktop, Steam, ...) now wear the PC's theme
        if (since == 0 || !prefs.getBoolean(PREF_THEMED_PICTURES, false)) {
            since = System.currentTimeMillis();
            prefs.edit().putLong(PREF_REFRESH_SINCE, since).putBoolean(PREF_THEMED_PICTURES, true).apply();
        }
        this.refreshSince = since;
        this.isLowRamDevice =
                ((ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE)).isLowRamDevice();
    }

    /** Whether the picture is on disk and recent enough not to be fetched again. */
    public boolean checkCacheExists(CachedAppAssetLoader.LoaderTuple tuple) {
        File file = getFile(tuple.computer.uuid, tuple.app.getAppId());
        if (!file.exists()) {
            return false;
        }
        long modified = file.lastModified();
        return modified >= refreshSince && System.currentTimeMillis() - modified < REFRESH_AFTER_MS;
    }

    // https://developer.android.com/topic/performance/graphics/load-bitmap.html
    public static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // Raw height and width of image
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {

            final int halfHeight = height / 2;
            final int halfWidth = width / 2;

            // Calculates the largest inSampleSize value that is a power of 2 and keeps both
            // height and width larger than the requested height and width.
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }

        return inSampleSize;
    }

    public ScaledBitmap loadBitmapFromCache(CachedAppAssetLoader.LoaderTuple tuple, int sampleSize) {
        File file = getFile(tuple.computer.uuid, tuple.app.getAppId());

        // Don't bother with anything if it doesn't exist
        if (!file.exists()) {
            return null;
        }

        // Make sure the cached asset doesn't exceed the maximum size
        if (file.length() > MAX_ASSET_SIZE) {
            LimeLog.warning("Removing cached tuple exceeding size threshold: "+tuple);
            file.delete();
            return null;
        }

        Bitmap bmp;

        // For OSes prior to P, we have to use the ugly BitmapFactory API
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            // Lookup bounds of the downloaded image
            BitmapFactory.Options decodeOnlyOptions = new BitmapFactory.Options();
            decodeOnlyOptions.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), decodeOnlyOptions);
            if (decodeOnlyOptions.outWidth <= 0 || decodeOnlyOptions.outHeight <= 0) {
                // Dimensions set to -1 on error. Return value always null.
                return null;
            }

            LimeLog.info("Tuple "+tuple+" has cached art of size: "+decodeOnlyOptions.outWidth+"x"+decodeOnlyOptions.outHeight);

            // Load the image scaled to the appropriate size
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = calculateInSampleSize(decodeOnlyOptions,
                    STANDARD_ASSET_WIDTH / sampleSize,
                    STANDARD_ASSET_HEIGHT / sampleSize);
            if (isLowRamDevice) {
                options.inPreferredConfig = Bitmap.Config.RGB_565;
                options.inDither = true;
            }
            else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                options.inPreferredConfig = Bitmap.Config.HARDWARE;
            }

            bmp = BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            if (bmp != null) {
                LimeLog.info("Tuple "+tuple+" decoded from disk cache with sample size: "+options.inSampleSize);
                return new ScaledBitmap(decodeOnlyOptions.outWidth, decodeOnlyOptions.outHeight, bmp);
            }
        }
        else {
            // On P, we can get a bitmap back in one step with ImageDecoder
            final ScaledBitmap scaledBitmap = new ScaledBitmap();
            try {
                scaledBitmap.bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder imageDecoder, ImageDecoder.ImageInfo imageInfo, ImageDecoder.Source source) {
                        scaledBitmap.originalWidth = imageInfo.getSize().getWidth();
                        scaledBitmap.originalHeight = imageInfo.getSize().getHeight();

                        float aspectRatio = (float) scaledBitmap.originalWidth / scaledBitmap.originalHeight;
                        float standardAspectRatio = (float) STANDARD_ASSET_WIDTH / STANDARD_ASSET_HEIGHT;
                        int targetWidth = STANDARD_ASSET_WIDTH;
                        int targetHeight = STANDARD_ASSET_HEIGHT;

                        if (aspectRatio < standardAspectRatio) {
                            targetHeight = (int) (standardAspectRatio / aspectRatio * targetHeight);
                        } else {
                            targetWidth = (int) (aspectRatio / standardAspectRatio * targetWidth);
                        }
                        imageDecoder.setTargetSize(targetWidth, targetHeight);
                        if (isLowRamDevice) {
                            imageDecoder.setMemorySizePolicy(ImageDecoder.MEMORY_POLICY_LOW_RAM);
                        }
                    }
                });
                return scaledBitmap;
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        return null;
    }

    public File getFile(String computerUuid, int appId) {
        return CacheHelper.openPath(false, cacheDir, "boxart", computerUuid, appId + ".png");
    }

    public void deleteAssetsForComputer(String computerUuid) {
        File dir = CacheHelper.openPath(false, cacheDir, "boxart", computerUuid);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
    }

    public void populateCacheWithStream(CachedAppAssetLoader.LoaderTuple tuple, InputStream input) {
        // Written beside the old picture and swapped in once complete, so a failed refresh
        // keeps the picture there was
        final String partName = tuple.app.getAppId() + ".png.part";
        boolean success = false;
        try (final OutputStream out = CacheHelper.openCacheFileForOutput(
                cacheDir, "boxart", tuple.computer.uuid, partName)
        ) {
            CacheHelper.writeInputStreamToOutputStream(input, out, MAX_ASSET_SIZE);
            success = true;
        } catch (IOException e) {
            e.printStackTrace();
        }
        File part = CacheHelper.openPath(false, cacheDir, "boxart", tuple.computer.uuid, partName);
        File file = getFile(tuple.computer.uuid, tuple.app.getAppId());
        if (success && part.length() > 0) {
            file.delete();
            success = part.renameTo(file);
        }
        if (!success) {
            LimeLog.warning("Unable to populate cache with tuple: "+tuple);
            part.delete();
        }
    }
}
