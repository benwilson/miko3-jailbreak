package com.miko3.mode.remotecontrol;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import com.miko3.shared.DriveLease;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.RobotControlClient;

/**
 * Wires the mode's drive HTTP routes to U2's RobotControlClient through
 * U3's DriveLease coordinator, per R5/R13/R15/R16/R17 (U7). Acquires the
 * lease on start, runs a renew() loop well under the coordinator's TTL,
 * runs a local ~750ms drive-command watchdog independent of the
 * coordinator, and — R15's backstop — commands its own stop if the
 * coordinator becomes unreachable, since no coordinator remains to do it.
 */
final class DriveController {
    private static final String TAG = "DriveController";
    private static final long RENEW_INTERVAL_MS = 1000; // comfortably under KTD3's ~2.25s TTL
    private static final long WATCHDOG_MS = 750; // R13

    interface ErrorListener {
        void onDriveError(String reason);
    }

    /** Thrown by drive()/keepalive() when the request's client token is no longer the
     * active one (R17) — checked atomically with the actual dispatch, inside the same
     * lock, so a request that already passed a separate pre-check can't still land after
     * a newer page load has taken over in between (confirmed reachable: ModeApp's route
     * handlers run on RoutingHttpServer's one-thread-per-connection model). */
    static final class StaleClientException extends RuntimeException {
    }

    private final Context context;
    private final String clientId;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IBinder deathToken = new Binder();
    private final ErrorListener errorListener;

    private RobotControlClient robotClient;
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

    /** R17: only the most recently connected browser client's commands are honored. */
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
    }

    void start() {
        robotClient = new RobotControlClient(context, new RobotControlClient.Listener() {
            @Override
            public void onConnected() {
                Log.i(TAG, "RobotControlClient connected");
            }

            @Override
            public void onDisconnected() {
                Log.w(TAG, "RobotControlClient disconnected");
            }

            @Override
            public void onSendFailed(RemoteException e) {
                Log.e(TAG, "RobotControlClient send failed", e);
            }
        });
        robotClient.connect();

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

    /** R17: called when a request arrives; returns true if it's from the active client
     * (claiming a not-yet-seen token as the new active one, per "newer connection wins"). */
    boolean acceptsClient(String token) {
        if (token == null || token.isEmpty()) {
            return false;
        }
        if (currentClientToken == null) {
            currentClientToken = token;
            return true;
        }
        return currentClientToken.equals(token);
    }

    /** A fresh page load claims control outright, superseding any previous client. */
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
            // The "10" (centiseconds -> ~100ms) is deliberately left at the only
            // value ever confirmed live (docs/hardware/motors-wheels.md) rather than
            // lengthened to paper over gaps between repeated calls -- each call is
            // its own short motion frame, not an extension of the last one, so a
            // caller that repeats a held direction (see DriveSection.java's JS) MUST
            // do so faster than this frame's own duration, or the robot visibly stops
            // between frames even with every command arriving instantly (confirmed
            // live: this, not network latency, was most of the reported "straight,
            // jank, jank, straight" -- the old 300ms/WS-era 250ms repeat interval was
            // already longer than this frame's ~100ms run time).
            robotClient.drive(linear, angular, 10);
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
        if (robotClient != null && robotClient.isConnected()) {
            try {
                robotClient.stop();
            } catch (RemoteException e) {
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
        if (robotClient != null) {
            robotClient.disconnect();
        }
    }
}
