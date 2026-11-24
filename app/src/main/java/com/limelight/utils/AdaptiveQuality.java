package com.limelight.utils;

import android.os.SystemClock;

import com.limelight.LimeLog;
import com.limelight.binding.video.MediaCodecDecoderRenderer;
import com.limelight.nvstream.http.NvHTTP;

import org.json.JSONObject;

import java.io.FileNotFoundException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

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
 * Once the bitrate has dropped so far that the picture would turn blocky at its size, it asks
 * for a smaller picture as well (three quarters, two thirds or half the size), which the TV
 * stretches back to full screen: a 4K stream at a third of its bitrate looks cleaner as 1440p.
 * The size goes back up as the bitrate recovers. With that available, the bitrate may go as
 * low as a quarter of where it started.
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

    // Picture sizes, in percent of the size the stream started at, and the share of pixels each has
    private static final int[] SCALES = {100, 75, 67, 50};
    private static final double[] PIXEL_SHARE = {1.0, 9.0 / 16, 4.0 / 9, 1.0 / 4};
    // Bits per pixel below which HEVC turns visibly blocky in motion, and above which the next size up looks good
    private static final double BPP_TOO_LOW = 0.06;
    private static final double BPP_ENOUGH = 0.08;
    private static final long MIN_MS_BETWEEN_SIZES = 20_000;

    /** Tells whether the decoder can take a different picture size mid-stream. */
    public interface SizeChangeCheck {
        boolean canChangePictureSize();
    }

    private final NvHTTP http;
    private final int startKbps;
    private volatile int floorKbps;
    private final int targetFps;
    private final double fullPixelsPerSecond;
    private final SizeChangeCheck sizeCheck;
    private final ExecutorService sender = Executors.newSingleThreadExecutor();

    private volatile boolean disabled;
    private int currentKbps;
    private int badStreak;
    private int slowStreak;
    private int goodStreak;
    private int goodSecondsNeeded = GOOD_SECONDS_TO_RAISE;
    private long lastChangeMs;
    private long lastRaiseMs;

    // Picture size: index into SCALES, when it last changed, and whether this PC can do it
    private volatile int scaleIndex;
    private long lastSizeChangeMs;
    private volatile boolean hostChangesSize = true;

    /**
     * @param sizeCheck null to leave the picture size alone and only adjust the bitrate
     */
    public AdaptiveQuality(NvHTTP http, int startKbps, int targetFps, int width, int height, SizeChangeCheck sizeCheck) {
        this.http = http;
        this.startKbps = startKbps;
        this.floorKbps = Math.max(3000, startKbps * 2 / 5);
        this.targetFps = Math.max(1, targetFps);
        this.currentKbps = startKbps;
        this.fullPixelsPerSecond = (double) Math.max(1, width) * Math.max(1, height) * this.targetFps;
        this.sizeCheck = sizeCheck;
    }

    private boolean sizeChangesPossible() {
        return sizeCheck != null && hostChangesSize && sizeCheck.canChangePictureSize();
    }

    private double bitsPerPixel(int kbps, int index) {
        return kbps * 1000.0 / (fullPixelsPerSecond * PIXEL_SHARE[index]);
    }

    /** The picture size that suits a bitrate: smaller while it is too thin, larger once there is plenty. */
    private int sizeFor(int kbps) {
        int index = scaleIndex;
        while (index < SCALES.length - 1 && bitsPerPixel(kbps, index) < BPP_TOO_LOW) {
            index++;
        }
        while (index > 0 && bitsPerPixel(kbps, index - 1) >= BPP_ENOUGH) {
            index--;
        }
        return index;
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
        } else if (sizeChangesPossible() && sizeFor(currentKbps) != scaleIndex
                && now - lastSizeChangeMs >= MIN_MS_BETWEEN_SIZES && now - lastChangeMs >= MIN_MS_BETWEEN_DOWN) {
            // The bitrate settled where a different picture size suits it, but the size had changed too recently
            request(currentKbps, now, "picture size catching up");
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

        // Send the picture size with every request, so the first answer tells whether this PC can change it
        int wanted = -1;
        if (sizeChangesPossible()) {
            wanted = scaleIndex;
            if (now - lastSizeChangeMs >= MIN_MS_BETWEEN_SIZES) {
                wanted = sizeFor(kbps);
                if (wanted != scaleIndex) {
                    lastSizeChangeMs = now;
                }
            }
        }
        send(kbps, wanted, why);
    }

    private void send(final int kbps, final int wantedIndex, String why) {
        LimeLog.info("Adaptive quality: asking for " + kbps + " kbps"
                + (wantedIndex >= 0 ? " at " + SCALES[wantedIndex] + "% size" : "") + " (" + why + ")");
        try {
            sender.execute(() -> {
                try {
                    String answer = http.setTunaBitrate(kbps, wantedIndex >= 0 ? SCALES[wantedIndex] : 0);
                    if (wantedIndex < 0) {
                        return;
                    }
                    JSONObject json = new JSONObject(answer);
                    if (!json.has("scale")) {
                        LimeLog.info("Adaptive quality: this PC's TunaStream can't change the picture size mid-stream");
                        hostChangesSize = false;
                        scaleIndex = 0;
                        return;
                    }
                    int used = json.optInt("scale", 100);
                    for (int i = 0; i < SCALES.length; i++) {
                        if (SCALES[i] == used) {
                            scaleIndex = i;
                        }
                    }
                    // With a smaller picture to fall back on, the bitrate can go lower before it looks bad
                    floorKbps = Math.max(3000, startKbps / 4);
                } catch (FileNotFoundException e) {
                    LimeLog.info("Adaptive quality: this PC's TunaStream can't change bitrate mid-stream");
                    disabled = true;
                } catch (Exception e) {
                    LimeLog.warning("Adaptive quality: request failed: " + e);
                }
            });
        } catch (RejectedExecutionException e) {
            // The stream is closing
        }
    }

    /**
     * Shows two thirds of the picture size for ten seconds, then the size it was at: a way to see
     * that this TV and PC handle a size change before the Wi-Fi ever calls for one.
     * @return false when it can't be tried here
     */
    public boolean testPictureSize() {
        if (disabled || !sizeChangesPossible()) {
            return false;
        }
        final int before = scaleIndex;
        final int kbps = currentKbps;
        lastSizeChangeMs = SystemClock.uptimeMillis() + 10_000;
        send(kbps, before == 2 ? 0 : 2, "test");
        try {
            sender.execute(() -> {
                try {
                    Thread.sleep(10_000);
                } catch (InterruptedException e) {
                    return;
                }
                try {
                    http.setTunaBitrate(kbps, SCALES[before]);
                    scaleIndex = before;
                } catch (Exception e) {
                    LimeLog.warning("Adaptive quality: couldn't put the picture size back: " + e);
                }
            });
        } catch (RejectedExecutionException e) {
            return false;
        }
        return true;
    }

    public void stop() {
        disabled = true;
        sender.shutdownNow();
    }
}
