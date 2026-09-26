package com.miko3.launcher;

/**
 * One holder at a time, kept alive by renewals and released on a missed TTL,
 * a Binder death or a clean release. Extracted from DriveLeaseService (its
 * renew, death and TTL bookkeeping, unchanged in behaviour) so the ears
 * session (meeting plan U3, KTD1) shares it. Plain Java: the Binder death
 * token is behind Token, the clock is passed in, and the harness drives it.
 *
 * Death handling keeps the drive lease's rule: a recipient is created per
 * acquisition and acts only while its holder is still the current one, so a
 * late death notification for a since-superseded holder is ignored.
 *
 * Every method locks this keeper; onRelease is called inside that lock, so an
 * implementation must not block (the drive lease posts its stop-motors to its
 * own thread; the ears session flips a flag its capture thread reads).
 */
final class LeaseKeeper {
    static final String RELEASE_TTL = "ttl_expired";
    static final String RELEASE_DIED = "binder_died";
    static final String RELEASE_CLEAN = "clean_release";

    /** A holder's death token (an IBinder in the launcher). */
    interface Token {
        /** Arranges for onDeath to run when the holder dies; throws when it is already dead. */
        void linkToDeath(Runnable onDeath) throws Exception;

        void unlinkToDeath(Runnable onDeath);
    }

    /** Told once per release, with the keeper's lock held. */
    interface Released {
        void released(String holder, String reason);
    }

    private final long ttlMs;
    private final Released onRelease;
    private String holder;
    private Token token;
    private Runnable recipient;
    private long lastRenewMs;

    LeaseKeeper(long ttlMs, Released onRelease) {
        this.ttlMs = ttlMs;
        this.onRelease = onRelease;
    }

    /**
     * Takes the lease for holder, or renews it when holder already has it.
     * False when another holder has it, or when the token is already dead.
     */
    synchronized boolean acquire(final String holder, Token token, long nowMs) {
        if (this.holder != null && !this.holder.equals(holder)) {
            return false;
        }
        if (this.holder == null) {
            Runnable r = new Runnable() {
                @Override
                public void run() {
                    died(holder, this);
                }
            };
            try {
                token.linkToDeath(r);
            } catch (Exception alreadyDead) {
                return false;
            }
            this.holder = holder;
            this.token = token;
            this.recipient = r;
        }
        lastRenewMs = nowMs;
        return true;
    }

    /** True when holder has the lease; its TTL restarts from nowMs. */
    synchronized boolean renew(String holder, long nowMs) {
        if (holder != null && holder.equals(this.holder)) {
            lastRenewMs = nowMs;
            return true;
        }
        return false;
    }

    /** A clean release by the holder; false (and nothing happens) for anyone else. */
    synchronized boolean release(String holder) {
        if (holder == null || !holder.equals(this.holder)) {
            return false;
        }
        releaseInternal(RELEASE_CLEAN);
        return true;
    }

    /** Releases a holder whose last renewal is older than the TTL; true when it did. */
    synchronized boolean check(long nowMs) {
        if (holder != null && nowMs - lastRenewMs > ttlMs) {
            releaseInternal(RELEASE_TTL);
            return true;
        }
        return false;
    }

    synchronized String holder() {
        return holder;
    }

    /** Milliseconds since the holder last renewed, or -1 with no holder. */
    synchronized long sinceRenewMs(long nowMs) {
        return holder == null ? -1 : nowMs - lastRenewMs;
    }

    private synchronized void died(String holder, Runnable recipient) {
        if (!holder.equals(this.holder) || recipient != this.recipient) {
            return; // stale notification for a since-superseded holder
        }
        releaseInternal(RELEASE_DIED);
    }

    /** Caller holds the lock. */
    private void releaseInternal(String reason) {
        String was = holder;
        if (token != null && recipient != null) {
            try {
                token.unlinkToDeath(recipient);
            } catch (RuntimeException ignored) {
                // Already unlinked (the death fired concurrently).
            }
        }
        holder = null;
        token = null;
        recipient = null;
        onRelease.released(was, reason);
    }
}
