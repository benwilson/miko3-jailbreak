package com.miko3.mode.remotecontrol;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.miko3.shared.DirectMotorDriver;
import com.miko3.shared.DriveLease;
import com.miko3.shared.LauncherProtocol;

import java.io.IOException;

/**
 * Wires the mode's drive HTTP routes to DirectMotorDriver (a direct
 * /dev/ttyS2 writer, bypassing ServiceExam's AIDL surface entirely) through
 * U3's DriveLease coordinator, per R5/R13/R15/R16/R17 (U7). Acquires the
 * lease on start, runs a renew() loop well under the coordinator's TTL,
 * runs a local drive-command watchdog independent of the coordinator, and —
 * R15's backstop — commands its own stop if the coordinator becomes
 * unreachable, since no coordinator remains to do it.
 *
 * U19l REVERSAL (2026-09-15, same day as U19h below): back to DirectMotorDriver,
 * ServiceExam no longer involved in the drive path at all. U19h had switched
 * to RobotControlClient (ServiceExam's own GameEvent(135) AIDL) reasoning that
 * exhaustive testing couldn't reproduce sustained motion outside ServiceExam's
 * own process "no matter how faithfully the wire bytes were replicated" — that
 * reasoning was real but incomplete: every one of those bypass tests used the
 * WRONG frame shape (type=25/loop=1, the vendor's AUTONOMOUS idle-wandering
 * recipe), never the actual TeleConnect held-drive recipes
 * (frontContinous/leftContinous_new/rightContinous_new — see
 * DirectMotorDriver.buildContinuousFrame()/buildTurnFrame()'s own javadoc,
 * found by decompiling this robot's actual companion phone app) that were
 * only discovered afterward and, by then, only ever wired through
 * RobotControlClient. Confirmed live (2026-09-15, later the same day) that
 * the CORRECT frame shapes, sent via this class's own raw /dev/ttyS2 writes
 * with ServiceExam fully disabled the entire time
 * (scripts/bypass-drive-test.py --shape single --frame-type 1 --t1 10
 * --loop 0), drive continuously and reliably for a full 15s hold — closing
 * the gap U19h's testing left open. ServiceExam is not required for reliable
 * driving after all; the CPU/lock-contention cost of keeping it enabled and
 * running (see [[miko3-camera-hal-cpu-bug]]-adjacent concerns) is not a
 * tradeoff this app needs to accept anymore.
 */
final class DriveController {
    private static final String TAG = "DriveController";
    private static final long RENEW_INTERVAL_MS = 1000; // comfortably under KTD3's ~2.25s TTL
    // 1200: comfortably above both DriveSection.java's repeat intervals (250ms for
    // forward, 500ms for everything else) without being so tight that ordinary timer
    // jitter false-triggers a stop mid-hold.
    private static final long WATCHDOG_MS = 1200;

    interface ErrorListener {
        void onDriveError(String reason);
    }

    /** Thrown by drive()/keepalive() when the request carries no usable client token
     * (missing or empty "ct" param) — see acceptsClient()'s comment: as of U15,
     * control is shared rather than exclusive, so this is no longer thrown for a
     * merely-not-the-most-recent token, only for a genuinely absent one. Kept as its
     * own exception (rather than dropped) since ModeApp's routes already handle it
     * as "reject with 409" and there's still a real, if narrower, case to reject. */
    static final class StaleClientException extends RuntimeException {
    }

    private final Context context;
    private final String clientId;
    // Confirmed live: on the main Looper, the lease renew loop (scheduled every
    // RENEW_INTERVAL_MS, needing to beat the coordinator's ~2.25s TTL) could miss
    // that window under real system load (camera capture, WebView, everything else
    // competing for main-thread time) -- the coordinator then revokes the lease
    // outright, surfacing as driving working briefly then stopping completely
    // ("drive lease not held"), not just getting rougher. A dedicated thread means
    // this timing no longer depends on how busy the UI thread happens to be.
    private final android.os.HandlerThread handlerThread = new android.os.HandlerThread("drive-controller");
    private final Handler handler;
    private final IBinder deathToken = new Binder();
    private final ErrorListener errorListener;

    private DirectMotorDriver motorDriver;
    private DriveLease lease;
    private volatile boolean leaseHeld;
    private volatile boolean coordinatorUnreachable;
    // Automatic lease recovery (U19t, 2026-09-15): previously, ANY lease failure --
    // a transient renew() RemoteException under heavy system load, the launcher's
    // DriveLeaseService binder connection dropping, or losing an acquire() race
    // against a not-yet-expired stale holder -- left this DriveController
    // permanently unable to drive for the rest of its lifetime, with no retry at
    // all; only a full app restart (confirmed live) re-acquired the lease.
    // Mirrors ModeApp's own camera retry-with-backoff (see
    // [[miko3-camera-hal-cpu-bug]]) for the same underlying reason: the operator
    // has no adb/root access in normal use to notice and manually restart the app.
    private static final long LEASE_RETRY_BASE_MS = 2000;
    private static final long LEASE_RETRY_MAX_MS = 30000;
    private int leaseRetryAttempt;

    /** The direction actually sent to the motor last, so repeat calls with the same
     * (linear, angular) for TURNING (self-sustaining, loop=1) are treated as a
     * watchdog-only keepalive instead of resending the motor command — resending an
     * unchanged turn would interrupt and restart it instead of extending it. Pure
     * forward/back is the deliberate exception: see drive()'s own comment. */
    private int activeLinear;
    private int activeAngular;
    private boolean hasSentDirection;

    /** Guards leaseHeld together with the actual motorDriver.drive()/stop() calls and
     * the watchdog schedule, so a concurrent HTTP thread's drive() and another thread's
     * release() (e.g. the UI's "Exit mode" running on the main thread) can't interleave
     * into a state where a drive command is sent after the lease was already released —
     * confirmed reachable given RoutingHttpServer's one-thread-per-connection model,
     * where /drive and /exit can be handled by two different threads at once. */
    private final Object driveLock = new Object();

    /** The most recently seen client token — as of U15 this is informational only
     * (see acceptsClient()'s comment), not a gate on whose commands are honored. */
    private volatile String currentClientToken;

    private final ServiceConnection leaseConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            lease = DriveLease.Stub.asInterface(binder);
            tryAcquire();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            lease = null;
            handleCoordinatorUnreachable("lease service disconnected");
        }
    };

    private final Runnable renewLoop = new Runnable() {
        @Override
        public void run() {
            if (leaseHeld && lease != null) {
                try {
                    boolean stillHeld = lease.renew(clientId);
                    coordinatorUnreachable = false;
                    if (!stillHeld) {
                        // The coordinator revoked this lease (e.g. TTL-expired because a
                        // prior renew was delayed past ~2.25s) but this call itself didn't
                        // throw — treating a non-throwing renew() as proof of ownership
                        // would let this mode keep driving after a second mode has already
                        // acquired the now-free lease, breaking "exactly one mode drives."
                        Log.w(TAG, "renew() reports lease no longer held — stopping");
                        synchronized (driveLock) {
                            leaseHeld = false;
                            handler.removeCallbacks(watchdog);
                        }
                        sendStopBestEffort();
                        errorListener.onDriveError("drive lease revoked — stopped");
                        // A THIRD lease-loss path, distinct from handleCoordinatorUnreachable()
                        // (a thrown RemoteException) and tryAcquire()'s "denied" branch: the
                        // coordinator itself is still reachable and answered normally, it just
                        // says this lease is gone (most likely TTL expiry from a renew() that
                        // arrived too late, e.g. under the same system load this whole project
                        // keeps running into). Confirmed live (2026-09-15) this path previously
                        // had NO retry at all -- left driving permanently dead here too, same
                        // as the other two paths before this fix.
                        scheduleLeaseRetry();
                        return;
                    }
                } catch (RemoteException e) {
                    handleCoordinatorUnreachable("renew() failed: " + e.getMessage());
                }
            }
            if (leaseHeld) {
                handler.postDelayed(this, RENEW_INTERVAL_MS);
            }
        }
    };

    private final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            Log.w(TAG, "drive watchdog fired — no command/keepalive within " + WATCHDOG_MS + "ms, stopping");
            sendStopBestEffort();
        }
    };

    private final Runnable leaseRetryRunnable = new Runnable() {
        @Override
        public void run() {
            reconnectLeaseService();
        }
    };

    DriveController(Context context, String clientId, ErrorListener errorListener) {
        this.context = context.getApplicationContext();
        this.clientId = clientId;
        this.errorListener = errorListener;
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    void start() {
        // Synchronous — DirectMotorDriver's connect() only opens /dev/ttyS2 and
        // starts its own POWER keepalive thread, nothing that needs to wait on
        // ServiceExam or any other external service (see U19l's own class javadoc
        // for why ServiceExam is no longer involved in the drive path at all).
        motorDriver = new DirectMotorDriver();
        if (!motorDriver.connect()) {
            Log.e(TAG, "DirectMotorDriver connect() failed");
        }

        bindLeaseService();
    }

    private void bindLeaseService() {
        Intent intent = new Intent(LauncherProtocol.DRIVE_LEASE_ACTION);
        intent.setPackage(LauncherProtocol.LAUNCHER_PACKAGE);
        boolean bound = context.bindService(intent, leaseConnection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            handleCoordinatorUnreachable("bindService to DriveLeaseService failed");
        }
    }

    /** Fully unbinds and rebinds — the same sequence a full app restart already goes
     * through (confirmed live to reliably re-acquire a lease this stuck), rather
     * than trying to reuse whatever state leaseConnection/lease are currently in,
     * which is exactly the state a previous failure already showed can't recover
     * on its own. */
    private void reconnectLeaseService() {
        try {
            context.unbindService(leaseConnection);
        } catch (IllegalArgumentException ignored) {
            // not currently bound -- fine, bindLeaseService() below starts fresh either way
        }
        lease = null;
        bindLeaseService();
    }

    private void scheduleLeaseRetry() {
        handler.removeCallbacks(leaseRetryRunnable);
        leaseRetryAttempt++;
        long delay = Math.min(LEASE_RETRY_BASE_MS << Math.min(leaseRetryAttempt - 1, 4), LEASE_RETRY_MAX_MS);
        Log.i(TAG, "retrying drive lease acquisition in " + delay + "ms (attempt " + leaseRetryAttempt + ")");
        handler.postDelayed(leaseRetryRunnable, delay);
    }

    private void tryAcquire() {
        try {
            boolean granted = lease.acquire(deathToken, clientId);
            if (granted) {
                leaseHeld = true;
                coordinatorUnreachable = false;
                leaseRetryAttempt = 0;
                Log.i(TAG, "lease acquired");
                handler.postDelayed(renewLoop, RENEW_INTERVAL_MS);
            } else {
                // Confirmed live: previously left this DriveController permanently unable
                // to drive if it lost this race even once -- e.g. against a just-exited
                // prior instance's not-yet-expired lease (see the coordinator's own TTL).
                // That kind of holder frees up on its own shortly, so retrying here (not
                // just logging and giving up) recovers from exactly that case.
                Log.w(TAG, "lease acquire() denied — already held");
                errorListener.onDriveError("drive lease already held by another mode");
                scheduleLeaseRetry();
            }
        } catch (RemoteException e) {
            handleCoordinatorUnreachable("acquire() failed: " + e.getMessage());
        }
    }

    /** U15: R17's original "newest connection wins" exclusivity is gone, by explicit
     * request — every one of this session's own reloads and test connections kept
     * silently kicking out the operator's own real browser tab ("control taken by
     * another connection"), and in practice this robot only ever has one person
     * actually driving it at a time anyway, so the exclusivity was pure friction with
     * no real benefit. Any request carrying a non-empty token (still required so a
     * stray/malformed request can't drive) is accepted — control is shared, not
     * claimed. currentClientToken is kept only so an already-connected client's own
     * requests are recognizably "known", not to gate anyone out. */
    boolean acceptsClient(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        currentClientToken = token;
        return true;
    }

    /** Records the token as seen; kept for callers of the old claim-on-page-load
     * pattern, but no longer has any gating effect — see acceptsClient()'s comment. */
    void claimClient(String token) {
        currentClientToken = token;
    }

    boolean isLeaseHeld() {
        return leaseHeld;
    }

    boolean isCoordinatorUnreachable() {
        return coordinatorUnreachable;
    }

    /** @throws StaleClientException if token is no longer the active client (R17) */
    void drive(String token, int linear, int angular) throws RemoteException {
        synchronized (driveLock) {
            Log.i(TAG, "drive() linear=" + linear + " angular=" + angular
                    + " t=" + android.os.SystemClock.elapsedRealtime());
            if (!acceptsClient(token)) {
                throw new StaleClientException();
            }
            if (!leaseHeld) {
                throw new RemoteException("drive lease not held");
            }
            handler.removeCallbacks(watchdog);
            boolean isStop = linear == 0 && angular == 0;
            // Pure forward/back (angular==0, linear!=0) is special-cased to ALWAYS
            // resend, matching TeleConnect's own frontContinous shape (loop=0, does
            // NOT self-sustain — see DirectMotorDriver.buildContinuousFrame()'s own
            // javadoc). This is the opposite of turning below, whose loop=1 shape
            // self-sustains once sent and would be interrupted by resending it.
            boolean pureLinear = angular == 0 && linear != 0;
            if (pureLinear) {
                try {
                    // Fixed magnitude 2, matching frontContinous exactly — sign only,
                    // not the browser's own ±20 button value (see buildContinuousFrame()'s
                    // javadoc: this magnitude is a vendor constant, not scaled).
                    motorDriver.driveContinuous(linear > 0 ? 2 : -2, 0);
                } catch (IOException e) {
                    throw new RemoteException(e.getMessage());
                }
                Log.i(TAG, "drive() pure forward/back -> driveContinuous (always resent, matches frontContinous)");
                activeLinear = linear;
                activeAngular = angular;
                hasSentDirection = true;
                handler.postDelayed(watchdog, WATCHDOG_MS);
                return;
            }
            boolean directionChanged = !hasSentDirection || linear != activeLinear || angular != activeAngular;
            // For turning and stop: only actually send a motor command when the
            // direction changes (or this is the first command of a hold) — turning's
            // loop=1 shape self-sustains, so resending an unchanged direction on every
            // held-key tick would interrupt and restart it instead of extending it. A
            // command that repeats the already-active direction just renews the
            // watchdog below.
            if (directionChanged) {
                Log.i(TAG, "drive() direction CHANGED -> sending to motor: linear=" + linear + " angular=" + angular);
                try {
                    if (isStop) {
                        motorDriver.stop();
                    } else {
                        // angular != 0 here (pureLinear handled above, isStop handled here) —
                        // fixed vendor kick/sustain magnitudes, sign only (see
                        // buildTurnFrame()'s own javadoc).
                        motorDriver.driveTurnSustained(angular);
                    }
                } catch (IOException e) {
                    throw new RemoteException(e.getMessage());
                }
                activeLinear = linear;
                activeAngular = angular;
                hasSentDirection = true;
            } else {
                Log.i(TAG, "drive() direction unchanged -> keepalive only (no motor resend)");
            }
            handler.postDelayed(watchdog, WATCHDOG_MS);
        }
    }

    /** A keepalive with no motion — resets the watchdog without sending a new drive frame.
     * @throws StaleClientException if token is no longer the active client (R17) */
    void keepalive(String token) {
        synchronized (driveLock) {
            if (!acceptsClient(token)) {
                throw new StaleClientException();
            }
            handler.removeCallbacks(watchdog);
            handler.postDelayed(watchdog, WATCHDOG_MS);
        }
    }

    private void sendStopBestEffort() {
        // Reset the dedup state under driveLock so a resumed hold after this
        // independent stop (watchdog fire, lease loss, coordinator unreachable) is
        // recognized as a real direction change and actually resent, rather than
        // being skipped because it happens to match whatever direction was active
        // right before this stop.
        synchronized (driveLock) {
            activeLinear = 0;
            activeAngular = 0;
            hasSentDirection = true;
        }
        if (motorDriver != null && motorDriver.isConnected()) {
            try {
                motorDriver.stop();
            } catch (IOException e) {
                Log.e(TAG, "stop() failed during watchdog/backstop", e);
            }
        }
    }

    /** R15: the coordinator is unreachable — stop independently, since no coordinator
     * remains to issue the release-triggered stop itself. */
    private void handleCoordinatorUnreachable(String reason) {
        Log.e(TAG, "coordinator unreachable (" + reason + ") — commanding stop independently");
        coordinatorUnreachable = true;
        handler.removeCallbacks(renewLoop);
        synchronized (driveLock) {
            leaseHeld = false;
            handler.removeCallbacks(watchdog);
        }
        sendStopBestEffort();
        errorListener.onDriveError("lost contact with drive coordinator — stopped");
        scheduleLeaseRetry();
    }

    /** R16: explicit clean release, from the "Exit mode" control or an exit request. */
    void release() {
        handler.removeCallbacks(renewLoop);
        handler.removeCallbacks(leaseRetryRunnable);
        synchronized (driveLock) {
            handler.removeCallbacks(watchdog);
            if (leaseHeld && lease != null) {
                try {
                    lease.release(clientId);
                } catch (RemoteException e) {
                    Log.w(TAG, "release() failed (coordinator likely already unreachable)", e);
                }
            }
            leaseHeld = false;
        }
        try {
            context.unbindService(leaseConnection);
        } catch (IllegalArgumentException ignored) {
            // never bound
        }
        if (motorDriver != null) {
            motorDriver.disconnect();
        }
        handlerThread.quitSafely();
    }
}
