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
 * Wires the mode's drive HTTP routes to U21's DirectMotorDriver (a direct
 * /dev/ttyS2 writer, bypassing ServiceExam's AIDL surface and its confirmed
 * shared-lock/CPU contention entirely — see that class's own javadoc) through
 * U3's DriveLease coordinator, per R5/R13/R15/R16/R17 (U7). Acquires the
 * lease on start, runs a renew() loop well under the coordinator's TTL,
 * runs a local ~750ms drive-command watchdog independent of the
 * coordinator, and — R15's backstop — commands its own stop if the
 * coordinator becomes unreachable, since no coordinator remains to do it.
 *
 * Previously used the AIDL-based RobotControlClient (still used by
 * launcher/DriveLeaseService's own separate, rare stop-backstop path, not yet
 * converted — see DirectMotorDriver's keepalive requirement, which a
 * fire-once backstop can't satisfy without its own continuous poll thread).
 * That path required ServiceExam to be running at all, defeating the point
 * of bypassing it, so this mode's own drive path switched fully.
 */
final class DriveController {
    private static final String TAG = "DriveController";
    private static final long RENEW_INTERVAL_MS = 1000; // comfortably under KTD3's ~2.25s TTL
    private static final long WATCHDOG_MS = 750; // R13

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

    /** Guards leaseHeld together with the actual robotClient.drive()/stop() calls and
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

    DriveController(Context context, String clientId, ErrorListener errorListener) {
        this.context = context.getApplicationContext();
        this.clientId = clientId;
        this.errorListener = errorListener;
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    void start() {
        // Synchronous, unlike RobotControlClient.connect() — no Listener needed;
        // DirectMotorDriver's connect() only starts a root shell + the keepalive
        // thread (see its own javadoc's REQUIRED KEEPALIVE section), nothing that
        // needs to wait on ServiceExam or any other external service.
        motorDriver = new DirectMotorDriver();
        if (!motorDriver.connect()) {
            Log.e(TAG, "DirectMotorDriver connect() failed");
        }

        Intent intent = new Intent(LauncherProtocol.DRIVE_LEASE_ACTION);
        intent.setPackage(LauncherProtocol.LAUNCHER_PACKAGE);
        boolean bound = context.bindService(intent, leaseConnection, Context.BIND_AUTO_CREATE);
        if (!bound) {
            handleCoordinatorUnreachable("bindService to DriveLeaseService failed");
        }
    }

    private void tryAcquire() {
        try {
            boolean granted = lease.acquire(deathToken, clientId);
            if (granted) {
                leaseHeld = true;
                coordinatorUnreachable = false;
                Log.i(TAG, "lease acquired");
                handler.postDelayed(renewLoop, RENEW_INTERVAL_MS);
            } else {
                Log.w(TAG, "lease acquire() denied — already held");
                errorListener.onDriveError("drive lease already held by another mode");
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
            if (!acceptsClient(token)) {
                throw new StaleClientException();
            }
            if (!leaseHeld) {
                throw new RemoteException("drive lease not held");
            }
            handler.removeCallbacks(watchdog);
            // Stay at "10" (centiseconds -> ~100ms), the only value ever confirmed
            // live (docs/hardware/motors-wheels.md). U14 tried 15 (~150ms) as a live
            // A/B experiment, hypothesizing that a longer frame would mean fewer
            // motor ramp-restarts per second of held motion -- operator-tested and
            // REJECTED: it made things worse ("jerk jerk jerk then stopped, then
            // nothing"), consistent with drive frames queuing behind each other on
            // the firmware side rather than each new one interrupting whatever's
            // still in flight. Do not re-attempt lengthening this value; if the
            // motor-frame layer needs more headroom, the fix has to come from
            // shortening DriveSection.java's repeat interval instead, not this.
            //
            // The repeat interval itself (see DriveSection.java) already has to stay
            // faster than this frame's own duration, since each call is its own
            // short motion frame, not an extension of the last one -- a caller that
            // repeats a held direction slower than that sees the robot visibly stop
            // between frames even with every command arriving instantly (confirmed
            // live: this, not network latency, was most of the reported original
            // "straight, jank, jank, straight" -- the pre-WebSocket 300ms repeat
            // interval was already longer than this frame's ~100ms run time).
            try {
                motorDriver.drive(linear, angular, 10);
            } catch (IOException e) {
                throw new RemoteException(e.getMessage());
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
    }

    /** R16: explicit clean release, from the "Exit mode" control or an exit request. */
    void release() {
        handler.removeCallbacks(renewLoop);
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
