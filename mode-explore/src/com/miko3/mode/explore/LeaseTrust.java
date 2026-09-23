package com.miko3.mode.explore;

/**
 * How long the robot keeps trusting its drive lease after the last successful
 * renewal. The launcher reclaims a lease whose holder has not renewed within its
 * TTL (DriveLeaseService, 2250 ms) and may then grant it to another mode. A
 * cached "held" flag would stay true through a stalled renewal thread, letting
 * this mode drive after the lease is gone. Trusting it only for a window shorter
 * than the TTL means the robot always stops before the launcher can hand the
 * wheels to anyone else.
 *
 * Plain Java; times are whatever clock the caller uses (elapsedRealtime on device).
 */
final class LeaseTrust {
    private final long windowMs;
    private boolean held;
    private long lastRenewMs;

    LeaseTrust(long windowMs) {
        this.windowMs = windowMs;
    }

    /** The lease was acquired or renewed successfully at nowMs. */
    synchronized void renewed(long nowMs) {
        held = true;
        lastRenewMs = nowMs;
    }

    /** The lease is gone (denied, revoked, released, or the coordinator vanished). */
    synchronized void lost() {
        held = false;
    }

    /** Held, and renewed recently enough that the launcher cannot have reclaimed it. */
    synchronized boolean trusted(long nowMs) {
        return held && nowMs - lastRenewMs < windowMs;
    }
}
