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

import com.miko3.shared.DriveLease;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.RobotControlClient;

/**
 * Wires the mode's drive HTTP routes to RobotControlClient (ServiceExam's own
 * GameEvent(135) AIDL surface) through U3's DriveLease coordinator, per
 * R5/R13/R15/R16/R17 (U7). Acquires the lease on start, runs a renew() loop
 * well under the coordinator's TTL, runs a local drive-command watchdog
 * independent of the coordinator, and — R15's backstop — commands its own
 * stop if the coordinator becomes unreachable, since no coordinator remains
 * to do it.
 *
 * U19h REVERSAL (2026-09-15): U21 had switched this to DirectMotorDriver (a
 * direct /dev/ttyS2 writer bypassing ServiceExam entirely) specifically to
 * avoid ServiceExam's confirmed shared-lock/CPU contention. That tradeoff no
 * longer holds: exhaustive live testing this session (raw serial writes, the
 * real libmiko_drivers.so loaded directly into a different process matching
 * its exact synchronized write+read+ERROR_UART protocol, DirectMotorDriver's
 * own repeated writes) could NOT reproduce sustained, non-stalling drive
 * motion outside ServiceExam's own process no matter how faithfully the wire
 * bytes were replicated — something about staying inside ServiceExam's own
 * session is load-bearing, not just the frame content. Confirmed instead
 * (GLPOS/encoder telemetry, not just a CPL=1 ack) that ServiceExam's own
 * GameEvent(135) AIDL call, sent ONCE per direction with the vendor's own
 * loop=1 "Explore" sustained-drive shape (RobotControlClient.driveSustained(),
 * magnitude 2 — see that method's own javadoc), drives smoothly and
 * continuously for as long as held. This DOES require ServiceExam to be
 * enabled and running (see ensureServiceExamEnabled()) — accepted as a real
 * tradeoff now that DirectMotorDriver's bypass has been shown incapable of
 * sustained motion at all, not just rougher.
 */
final class DriveController {
    private static final String TAG = "DriveController";
    private static final long RENEW_INTERVAL_MS = 1000; // comfortably under KTD3's ~2.25s TTL
    // 1200, not 750: matches DriveSection.java's own repeat interval, now 500ms
    // (was 80ms) -- see driveSustained()'s javadoc for why the resend cadence had
    // to change. 1200 keeps roughly the same multiple-of-the-repeat-interval
    // margin the old 750/80 pairing had, without being so tight that ordinary
    // timer jitter on a 500ms cadence false-triggers a stop mid-hold.
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

    private RobotControlClient robotClient;
    private volatile boolean robotClientConnected;
    private DriveLease lease;
    private volatile boolean leaseHeld;
    private volatile boolean coordinatorUnreachable;

    /** The direction actually sent to the motor last, so repeat calls with the same
     * (linear, angular) — DriveSection.java's own 500ms held-key resend — are treated
     * as a watchdog-only keepalive instead of resending the motor command. Resending
     * driveSustained() on every call, rather than only on a real direction change, is
     * exactly what reintroduced the lurch/freeze stall this session spent a long time
     * root-causing (see RobotControlClient.driveSustained()'s own javadoc) — its
     * loop=1 shape self-sustains once sent; sending it again while the same direction
     * is already active interrupts and restarts the motion instead of extending it. */
    private int activeLinear;
    private int activeAngular;
    private boolean hasSentDirection;

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
        ensureServiceExamEnabled();
        robotClient = new RobotControlClient(context, new RobotControlClient.Listener() {
            @Override
            public void onConnected() {
                robotClientConnected = true;
                Log.i(TAG, "RobotControlClient connected");
            }

            @Override
            public void onDisconnected() {
                robotClientConnected = false;
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
            // U19i (2026-09-15): pure FORWARD (linear>0, angular==0) is special-cased to
            // ALWAYS resend, matching com.teleconnect.TeleConnect's own frontContinous —
            // confirmed from source that forward uses type=1/loop=0 (does NOT self-sustain,
            // must be resent) at a fixed magnitude of 2, unlike turning's type=24/loop=1
            // (self-sustains, sent once) that leftContinous_new/rightContinous_new use.
            // There is no "backContinous" anywhere in the vendor source at all — TeleConnect
            // doesn't support held backward driving — so backward keeps using the same
            // send-once driveSustained() path as turning, which live testing confirmed
            // works well for both. Forward alone was being sent through the turning-style
            // shape this whole time, which is what kept degrading after repeated use; a
            // command actually matching the vendor's own forward recipe, resent every tick
            // like the real app does, is the only remaining untried, fully vendor-accurate
            // combination for forward specifically.
            boolean pureForward = linear > 0 && angular == 0;
            if (pureForward) {
                if (!robotClientConnected) {
                    throw new RemoteException("RobotControlClient not connected");
                }
                Log.i(TAG, "drive() pure forward -> driveContinuous (always resent, matches frontContinous)");
                robotClient.driveContinuous(2, 0);
                activeLinear = linear;
                activeAngular = angular;
                hasSentDirection = true;
                handler.postDelayed(watchdog, WATCHDOG_MS);
                return;
            }
            boolean directionChanged = !hasSentDirection || linear != activeLinear || angular != activeAngular;
            // U19h: for every OTHER direction (turning, backward, stop) only actually send a
            // motor command when the direction changes (or this is the first command of a
            // hold) -- see this class's own javadoc and activeLinear/activeAngular's field
            // comment for why resending an unchanged direction on every held-key tick, rather
            // than treating repeats as a watchdog-only keepalive, reintroduces the
            // lurch/freeze stall for THESE shapes (their loop=1 self-sustains; forward's
            // loop=0 above is the deliberate exception). A command that repeats the
            // already-active direction just renews the watchdog below.
            if (directionChanged) {
                if (!robotClientConnected) {
                    throw new RemoteException("RobotControlClient not connected");
                }
                Log.i(TAG, "drive() direction CHANGED -> sending to motor: linear=" + linear + " angular=" + angular);
                if (isStop) {
                    robotClient.stop();
                } else {
                    robotClient.driveSustained(linear, angular);
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
        if (robotClient != null && robotClientConnected) {
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
        handlerThread.quitSafely();
    }

    /** RobotControlClient's GameEvent(135) AIDL path (see this class's own javadoc
     * for why the drive path switched back to it) requires ServiceExam actually
     * running, but this jailbreak's other components (launcher, camera-CPU-bug
     * mitigation) deliberately keep it disabled otherwise — enable it here, on
     * entering drive mode specifically, via su (matching the same pattern
     * ServiceExam's own SensorModule.initGPIO() uses for its own chmod calls),
     * since a regular app can't toggle another package's enabled state itself. */
    private void ensureServiceExamEnabled() {
        // Full path, bare stdin close (no "exit") -- matches bootagent/RootOps.java's own
        // proven-working su invocation pattern on this device; a bare "su" on PATH was NOT
        // independently confirmed here and is exactly the kind of thing that silently no-ops.
        Process suProcess = null;
        try {
            suProcess = Runtime.getRuntime().exec("/system/bin/su");
            java.io.OutputStream out = suProcess.getOutputStream();
            out.write("pm enable com.example.root.serviceexam\n".getBytes("UTF-8"));
            out.flush();
            out.close();
            boolean finished = suProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
            if (!finished) {
                Log.e(TAG, "enabling ServiceExam via su timed out — drive commands may no-op");
                return;
            }
            int rc = suProcess.exitValue();
            Log.i(TAG, "pm enable com.example.root.serviceexam via su exit=" + rc);
            if (rc != 0) {
                Log.e(TAG, "pm enable ServiceExam via su exited non-zero: " + rc + " — drive commands may no-op");
            }
        } catch (Exception e) {
            Log.e(TAG, "failed to enable ServiceExam via su — drive commands may no-op", e);
        } finally {
            if (suProcess != null) {
                suProcess.destroy();
            }
        }
    }
}
