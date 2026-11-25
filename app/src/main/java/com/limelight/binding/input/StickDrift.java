package com.limelight.binding.input;

/**
 * Anti-drift for one thumbstick. Worn sticks don't return to the exact centre, so a game sees a
 * small push that makes the character or camera creep. When the stick sits still near the centre
 * for a moment, this takes that resting position as the stick's new centre and removes it.
 *
 * Unlike a bigger deadzone, small movements still count: the correction fades out as the stick
 * moves away from its centre, so full tilt still reaches the edge.
 */
final class StickDrift {
    /** Drift larger than this is a worn-out stick, or a thumb resting on it: left alone. */
    private static final float MAX_DRIFT = 0.2f;
    /** Moving less than this counts as sitting still. */
    private static final float STILL = 0.02f;
    /** How long the stick has to sit still before its resting position is learned. */
    static final long SETTLE_MS = 1500;

    private float centreX, centreY;
    private float lastX, lastY;
    private long stillSince;

    /** A new reading; returns nothing, read the corrected values with x() and y(). */
    void feed(float x, float y, long now) {
        if (Math.abs(x - lastX) > STILL || Math.abs(y - lastY) > STILL || stillSince == 0) {
            stillSince = now;
        }
        lastX = x;
        lastY = y;
    }

    /**
     * Called after the stick may have settled. True when a new centre was learned, so the
     * corrected position should be sent again.
     */
    boolean settle(long now) {
        if (stillSince == 0 || now - stillSince < SETTLE_MS) {
            return false;
        }
        if (Math.hypot(lastX, lastY) > MAX_DRIFT) {
            return false;
        }
        if (Math.abs(lastX - centreX) < 0.005f && Math.abs(lastY - centreY) < 0.005f) {
            return false;
        }
        centreX = lastX;
        centreY = lastY;
        return true;
    }

    float x() {
        return lastX - centreX * fade();
    }

    float y() {
        return lastY - centreY * fade();
    }

    /** 1 at the learned centre, falling to 0 at full tilt, so the edges stay reachable. */
    private float fade() {
        float drift = (float) Math.hypot(centreX, centreY);
        if (drift == 0) {
            return 0;
        }
        float away = (float) Math.hypot(lastX, lastY);
        float f = 1f - (away - drift) / (1f - drift);
        return Math.max(0f, Math.min(1f, f));
    }
}
