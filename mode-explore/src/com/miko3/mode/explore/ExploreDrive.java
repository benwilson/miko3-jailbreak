package com.miko3.mode.explore;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.miko3.shared.DirectMotorDriver;
import com.miko3.shared.DriveLease;
import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.SensorSnapshot;

import java.io.IOException;

/**
 * The device side of the wander (U5): the motor driver, the drive lease, and the
 * sensor readings the driver parses from its keepalive replies (KTD1), exposed
 * to ExploreLoop as Wheels, Lease and Sensors.
 *
 * The lease handling is adapted from mode-remote-control's DriveController
 * (bind, acquire, renew every second, retry with backoff; KTD7) rather than
 * shared with it, so the remote-control mode's hard-won lease behavior is not
 * put at risk; deduplicating the two is deferred follow-up work. Unlike
 * DriveController there is no per-command watchdog here: ExploreLoop's stop
 * timer covers a stuck brain instead.
 *
 * Debug hooks for staging the acceptance checks (U8), each off unless its
 * log tag is set to DEBUG over adb (setprop log.tag.<tag> DEBUG):
 *   MikoExploreStale    hide every reading (AE3/AE4)
 *   MikoExploreFreeze   stop ticking the brain (proves the stop timer)
 *   MikoExploreNoRenew  stop renewing, so the launcher's lease TTL expires (AE5)
 */
final class ExploreDrive implements ExploreLoop.Wheels, ExploreLoop.Sensors, ExploreLoop.Lease, ExploreLoop.Hooks {
    private static final String TAG = "ExploreDrive";
    private static final String CLIENT_ID = LauncherProtocol.MODE_EXPLORE;
    private static final long RENEW_INTERVAL_MS = 1000; // comfortably under the lease's ~2.25s TTL
    private static final long LEASE_RETRY_BASE_MS = 2000;
    private static final long LEASE_RETRY_MAX_MS = 30000;
    /** A CPL=2 refusal this close before a reading counts as part of it. */
    private static final long REFUSAL_WINDOW_MS = 250;
    /** Only the sign is used by DirectMotorDriver; positive turns left (see DriveSection). */
    private static final int TURN_LEFT = 20;
    private static final int TURN_RIGHT = -20;

    private final Context context;
    private final HandlerThread handlerThread = new HandlerThread("explore-lease");
    private final Handler handler;
    private final IBinder deathToken = new Binder();

    private final DirectMotorDriver driver = new DirectMotorDriver();
    private volatile boolean driverConnected;
    /** Brain-thread only: whether the first reading has been logged yet. */
    private boolean sawReading;
    private volatile DriveLease lease;
    private volatile boolean leaseHeld;
    private int leaseRetryAttempt;

    private final ServiceConnection leaseConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            lease = DriveLease.Stub.asInterface(binder);
            tryAcquire();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            lease = null;
            loseLease("lease service disconnected");
        }
    };

    private final Runnable renewLoop = new Runnable() {
        @Override
        public void run() {
            DriveLease l = lease;
            if (!leaseHeld || l == null) {
                return;
            }
            if (!hook("MikoExploreNoRenew")) {
                try {
                    if (!l.renew(CLIENT_ID)) {
                        loseLease("renew() reports the lease is no longer held");
                        return;
                    }
                } catch (RemoteException e) {
                    loseLease("renew() failed: " + e.getMessage());
                    return;
                }
            }
            handler.postDelayed(this, RENEW_INTERVAL_MS);
        }
    };

    private final Runnable leaseRetry = new Runnable() {
        @Override
        public void run() {
            try {
                context.unbindService(leaseConnection);
            } catch (IllegalArgumentException ignored) {
                // Not bound; nothing to undo.
            }
            lease = null;
            bindLease();
        }
    };

    ExploreDrive(Context context) {
        this.context = context.getApplicationContext();
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    /** Connects the driver (starting its keepalive, and so the readings) and asks for the lease. */
    void start() {
        driverConnected = driver.connect();
        if (!driverConnected) {
            Log.e(TAG, "DirectMotorDriver connect() failed -- no readings, so the robot stays still");
        } else {
            // Rules out a ToF left switched off (a leftover TOFDS), which would read as a
            // stuck value (KTD3; docs/hardware/tof-sensor.md).
            try {
                driver.enableTof();
            } catch (IOException e) {
                Log.w(TAG, "TOFEN failed", e);
            }
        }
        bindLease();
    }

    /** Exit: release the lease, then disconnect. The loop has already stopped the wheels. */
    void release() {
        handler.removeCallbacks(renewLoop);
        handler.removeCallbacks(leaseRetry);
        DriveLease l = lease;
        if (leaseHeld && l != null) {
            try {
                l.release(CLIENT_ID);
            } catch (RemoteException e) {
                Log.w(TAG, "release() failed (coordinator likely already gone)", e);
            }
        }
        leaseHeld = false;
        try {
            context.unbindService(leaseConnection);
        } catch (IllegalArgumentException ignored) {
            // Never bound.
        }
        driver.disconnect();
        driverConnected = false;
        handlerThread.quitSafely();
    }

    private void bindLease() {
        Intent intent = new Intent(LauncherProtocol.DRIVE_LEASE_ACTION);
        intent.setPackage(LauncherProtocol.LAUNCHER_PACKAGE);
        if (!context.bindService(intent, leaseConnection, Context.BIND_AUTO_CREATE)) {
            loseLease("bindService to DriveLeaseService failed");
        }
    }

    private void tryAcquire() {
        DriveLease l = lease;
        if (l == null) {
            return;
        }
        try {
            if (l.acquire(deathToken, CLIENT_ID)) {
                leaseHeld = true;
                leaseRetryAttempt = 0;
                Log.i(TAG, "lease acquired");
                handler.postDelayed(renewLoop, RENEW_INTERVAL_MS);
            } else {
                Log.w(TAG, "lease denied -- another mode holds it");
                scheduleLeaseRetry();
            }
        } catch (RemoteException e) {
            loseLease("acquire() failed: " + e.getMessage());
        }
    }

    /** The loop sees held() go false on its next pass and tells the brain, which stops. */
    private void loseLease(String reason) {
        Log.w(TAG, "drive lease lost (" + reason + ")");
        leaseHeld = false;
        handler.removeCallbacks(renewLoop);
        scheduleLeaseRetry();
    }

    private void scheduleLeaseRetry() {
        handler.removeCallbacks(leaseRetry);
        leaseRetryAttempt++;
        long delay = Math.min(LEASE_RETRY_BASE_MS << Math.min(leaseRetryAttempt - 1, 4), LEASE_RETRY_MAX_MS);
        Log.i(TAG, "retrying the drive lease in " + delay + "ms (attempt " + leaseRetryAttempt + ")");
        handler.postDelayed(leaseRetry, delay);
    }

    private static boolean hook(String tag) {
        return Log.isLoggable(tag, Log.DEBUG);
    }

    // ---- ExploreLoop.Lease ----

    @Override
    public boolean held() {
        return leaseHeld;
    }

    // ---- ExploreLoop.Sensors ----

    @Override
    public SensorReading latest() {
        if (!driverConnected) {
            return null;
        }
        SensorSnapshot s = driver.latestSensors();
        if (s == null) {
            return null;
        }
        if (!sawReading) {
            sawReading = true;
            Log.i(TAG, "first sensor reading: " + s);
        }
        long refusal = driver.lastRefusalMs();
        Integer cpl = refusal != 0 && refusal >= s.timestampMs - REFUSAL_WINDOW_MS ? Integer.valueOf(2) : null;
        // fault stays false: SensorSnapshot.fault only means tof read 16383, which the
        // classifier judges itself (it can be an edge when the IR flag agrees). A dead
        // keepalive shows up as the readings going stale.
        return new SensorReading(s.timestampMs, s.tof, s.ir1, s.ir2, cpl, false);
    }

    // ---- ExploreLoop.Hooks ----

    @Override
    public boolean staleSensors() {
        return hook("MikoExploreStale");
    }

    @Override
    public boolean freezeBrain() {
        return hook("MikoExploreFreeze");
    }

    // ---- ExploreLoop.Wheels ----

    @Override
    public void forwardTick() throws IOException {
        requireDriver();
        driver.driveContinuous(2, 0);
    }

    @Override
    public void turn(ExploreBrain.Direction direction) throws IOException {
        requireDriver();
        driver.driveTurnSustained(direction == ExploreBrain.Direction.LEFT ? TURN_LEFT : TURN_RIGHT);
    }

    @Override
    public void backTick() throws IOException {
        requireDriver();
        driver.driveContinuous(-2, 0);
    }

    @Override
    public void stop() throws IOException {
        requireDriver();
        driver.stop();
    }

    private void requireDriver() throws IOException {
        if (!driverConnected || !driver.isConnected()) {
            throw new IOException("motor driver not connected");
        }
    }

    /** The brain's clock: the same elapsedRealtime the driver stamps readings with. */
    static final ExploreBrain.Clock CLOCK = new ExploreBrain.Clock() {
        @Override
        public long nowMs() {
            return SystemClock.elapsedRealtime();
        }
    };
}
