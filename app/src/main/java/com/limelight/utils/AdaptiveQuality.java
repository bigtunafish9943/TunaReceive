package com.limelight.utils;

import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.http.NvHTTP;

import java.io.FileNotFoundException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picture quality that adjusts itself. Once a second the decoder reports how much of the
 * picture arrived; when parts go missing two seconds running (the Wi-Fi is overflowing), this
 * asks TunaStream to lower the bitrate by a third, down to two fifths of the starting rate.
 * After fifteen clean seconds it steps back up, a seventh at a time, to where it started.
 * A busy moment then softens the picture briefly instead of stuttering or dropping.
 *
 * PCs whose TunaStream can't do this answer "not found", and it stops asking.
 */
public final class AdaptiveQuality implements MediaCodecDecoderRenderer.StreamHealthListener {
    private static final float LOSS_THRESHOLD_PCT = 1.0f;
    private static final int BAD_SECONDS_TO_LOWER = 2;
    private static final int GOOD_SECONDS_TO_RAISE = 15;
    private static final long MIN_MS_BETWEEN_DOWN = 3_000;
    private static final long MIN_MS_BETWEEN_UP = 10_000;

    private final NvHTTP http;
    private final int startKbps;
    private final int floorKbps;
    private final int targetFps;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private volatile boolean disabled;
    private int currentKbps;
    private int badStreak;
    private int goodStreak;
    private long lastChangeMs;

    public AdaptiveQuality(NvHTTP http, int startKbps, int targetFps) {
        this.http = http;
        this.startKbps = startKbps;
        this.floorKbps = Math.max(3000, startKbps * 2 / 5);
        this.targetFps = Math.max(1, targetFps);
        this.currentKbps = startKbps;
    }

    @Override
    public void onStreamHealth(float lossPct, float receivedFps) {
        if (disabled || http == null || startKbps <= 0) {
            return;
        }
        boolean bad = lossPct > LOSS_THRESHOLD_PCT ||
                (lossPct > 0 && receivedFps < targetFps * 0.8f);
        if (bad) {
            badStreak++;
            goodStreak = 0;
        } else {
            goodStreak++;
            badStreak = 0;
        }

        long now = SystemClock.uptimeMillis();
        if (badStreak >= BAD_SECONDS_TO_LOWER && currentKbps > floorKbps && now - lastChangeMs >= MIN_MS_BETWEEN_DOWN) {
            request(Math.max(floorKbps, currentKbps * 2 / 3), now, "lost " + lossPct + "% of the picture");
            badStreak = 0;
        } else if (goodStreak >= GOOD_SECONDS_TO_RAISE && currentKbps < startKbps && now - lastChangeMs >= MIN_MS_BETWEEN_UP) {
            request(Math.min(startKbps, currentKbps + Math.max(1000, currentKbps / 7)), now, "clean for " + goodStreak + " s");
            goodStreak = 0;
        }
    }

    private void request(final int kbps, long now, String why) {
        currentKbps = kbps;
        lastChangeMs = now;
        LimeLog.info("Adaptive quality: asking for " + kbps + " kbps (" + why + ")");
        sender.execute(() -> {
            try {
                http.setTunaBitrate(kbps);
            } catch (FileNotFoundException e) {
                LimeLog.info("Adaptive quality: this PC's TunaStream can't change bitrate mid-stream");
                disabled = true;
            } catch (Exception e) {
                LimeLog.warning("Adaptive quality: request failed: " + e);
            }
        });
    }

    public void stop() {
        disabled = true;
        sender.shutdownNow();
    }
}
