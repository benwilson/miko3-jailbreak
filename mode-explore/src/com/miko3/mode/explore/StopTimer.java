package com.miko3.mode.explore;

/**
 * The stop timer (KTD6): only the brain's own ticks keep the motors alive. If
 * nothing feeds it for the window, expired() reports true once, and the caller
 * stops the motors. Lease renewals and the driver's keepalive run on their own
 * threads, so they would carry on through a stuck brain; this is what notices.
 *
 * Idle until the first feed, and fires once per silence: a stop is sent when the
 * brain goes quiet, not repeated every check while it stays quiet.
 *
 * Plain Java; the times are whatever clock the caller uses.
 */
final class StopTimer {
    private final long windowMs;
    private long lastFeedMs;
    private boolean armed;

    StopTimer(long windowMs) {
        this.windowMs = windowMs;
    }

    synchronized void feed(long nowMs) {
        lastFeedMs = nowMs;
        armed = true;
    }

    /** Fire again on the next check, without a feed: the stop it triggered failed, and
     * a stuck brain will not feed the timer, so nothing else would retry it. */
    synchronized void rearm() {
        armed = true;
    }

    /** True exactly once when the window has passed since the last feed. */
    synchronized boolean expired(long nowMs) {
        if (armed && nowMs - lastFeedMs >= windowMs) {
            armed = false;
            return true;
        }
        return false;
    }
}
