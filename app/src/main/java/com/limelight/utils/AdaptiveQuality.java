package com.limelight.utils;

import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.http.NvHTTP;

import java.io.FileNotFoundException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Picture quality that adjusts itself. Once a second the decoder reports how the picture is
 * arriving, and this asks TunaStream for a bitrate that fits.
 *
 * It watches two things. Frames that arrive slowly are the early sign: the PC sends each frame
 * in a short burst, so a frame that takes most of a frame interval to come in means the
 * connection is carrying nearly all it can, before anything has been lost. Two seconds of that
 * lowers the bitrate by a seventh, which usually can't be seen. Frames that go missing are the
 * late sign, when the Wi-Fi has already overflowed: two seconds of that lowers it by a third.
 * The bitrate never goes below two fifths of where it started.
 *
 * After clean seconds it steps back up, a seventh at a time. If stepping up brings the trouble
 * straight back, it waits twice as long before trying again, so a connection that can't carry
 * the full picture settles just under its limit instead of bouncing off it.
 *
 * PCs whose TunaStream can't do this answer "not found", and it stops asking.
 */
public final class AdaptiveQuality implements MediaCodecDecoderRenderer.StreamHealthListener {
    private static final float LOSS_THRESHOLD_PCT = 1.0f;
    private static final float SLOW_FRAMES_THRESHOLD_PCT = 30f;
    private static final int BAD_SECONDS_TO_LOWER = 2;
    private static final int SLOW_SECONDS_TO_LOWER = 2;
    private static final int GOOD_SECONDS_TO_RAISE = 15;
    private static final int MAX_GOOD_SECONDS_TO_RAISE = 240;
    private static final long MIN_MS_BETWEEN_DOWN = 3_000;
    private static final long MIN_MS_BETWEEN_UP = 10_000;
    private static final long RAISE_WAS_TOO_MUCH_MS = 60_000;
    private static final long SETTLED_MS = 5 * 60_000;

    private final NvHTTP http;
    private final int startKbps;
    private final int floorKbps;
    private final int targetFps;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private volatile boolean disabled;
    private int currentKbps;
    private int badStreak;
    private int slowStreak;
    private int goodStreak;
    private int goodSecondsNeeded = GOOD_SECONDS_TO_RAISE;
    private long lastChangeMs;
    private long lastRaiseMs;

    public AdaptiveQuality(NvHTTP http, int startKbps, int targetFps) {
        this.http = http;
        this.startKbps = startKbps;
        this.floorKbps = Math.max(3000, startKbps * 2 / 5);
        this.targetFps = Math.max(1, targetFps);
        this.currentKbps = startKbps;
    }

    @Override
    public void onStreamHealth(float lossPct, float receivedFps, float slowFramePct) {
        if (disabled || http == null || startKbps <= 0) {
            return;
        }
        boolean losing = lossPct > LOSS_THRESHOLD_PCT ||
                (lossPct > 0 && receivedFps < targetFps * 0.8f);
        boolean straining = !losing && slowFramePct >= SLOW_FRAMES_THRESHOLD_PCT;
        if (losing) {
            badStreak++;
            slowStreak = 0;
            goodStreak = 0;
        } else if (straining) {
            slowStreak++;
            badStreak = 0;
            goodStreak = 0;
        } else {
            goodStreak++;
            badStreak = 0;
            slowStreak = 0;
        }

        long now = SystemClock.uptimeMillis();
        boolean canLower = currentKbps > floorKbps && now - lastChangeMs >= MIN_MS_BETWEEN_DOWN;
        if (badStreak >= BAD_SECONDS_TO_LOWER && canLower) {
            lowered(now);
            request(Math.max(floorKbps, currentKbps * 2 / 3), now, "lost " + lossPct + "% of the picture");
            badStreak = 0;
        } else if (slowStreak >= SLOW_SECONDS_TO_LOWER && canLower) {
            lowered(now);
            request(Math.max(floorKbps, currentKbps * 6 / 7), now, Math.round(slowFramePct) + "% of frames arrived slowly");
            slowStreak = 0;
        } else if (goodStreak >= goodSecondsNeeded && currentKbps < startKbps && now - lastChangeMs >= MIN_MS_BETWEEN_UP) {
            lastRaiseMs = now;
            request(Math.min(startKbps, currentKbps + Math.max(1000, currentKbps / 7)), now, "clean for " + goodStreak + " s");
            goodStreak = 0;
        } else if (goodSecondsNeeded > GOOD_SECONDS_TO_RAISE && now - lastChangeMs >= SETTLED_MS) {
            // Steady for a good while: the connection may have improved, so be quick to try again
            goodSecondsNeeded = GOOD_SECONDS_TO_RAISE;
        }
    }

    /** Having to lower soon after raising means the raise was too much: wait longer next time. */
    private void lowered(long now) {
        if (lastRaiseMs != 0 && now - lastRaiseMs < RAISE_WAS_TOO_MUCH_MS) {
            goodSecondsNeeded = Math.min(MAX_GOOD_SECONDS_TO_RAISE, goodSecondsNeeded * 2);
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
