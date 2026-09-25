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
 * Lease ownership rules (review findings on PR #4):
 *  - The driver (and so the UART keepalive and the readings) is connected only
 *    while the lease is held; a denied or lost lease stops the wheels and
 *    disconnects, so a mode waiting for the lease never shares the UART.
 *  - The lease is trusted only for LEASE_TRUST_MS after the last successful
 *    renewal (LeaseTrust), shorter than the launcher's TTL, and every motion
 *    write checks it, so a stalled renewal stops the robot before the launcher
 *    can give the wheels to another mode.
 *  - Nothing re-acquires the lease after release().
 *
 * The native readUART() waits at most 5 s for a reply (select() timeout in
 * libmiko_drivers.so), so if the MCU goes silent a stop can be delayed by up to
 * that long while another frame holds the driver, but never indefinitely.
 *
 * Debug hooks for staging the acceptance checks (U8), each off unless its
 * log tag is set to DEBUG over adb (setprop log.tag.<tag> DEBUG):
 *   MikoExploreStale    hide every reading (AE3/AE4)
 *   MikoExploreFreeze   stop ticking the brain (proves the stop timer)
 *   MikoExploreNoRenew  stop renewing, so the launcher's lease TTL expires (AE5)
 *   MikoExploreCurious  a curiosity stop is due at every pause (camera curiosity U8)
 *   MikoExploreSpin     turn in place one way then the other, for the gyro capture
 *                       (ExploreSpin; explore nav plan U1)
 *   MikoExploreLookThenGo  look-then-go navigation (explore nav plan U4, KTD7): the
 *                       camera opens only at each leg decision; read once as
 *                       Explore starts (lookThenGo()), so set it before starting
 */
final class ExploreDrive implements ExploreLoop.Wheels, ExploreLoop.Sensors, ExploreLoop.Lease, ExploreLoop.Hooks {
    private static final String TAG = "ExploreDrive";
    private static final String CLIENT_ID = LauncherProtocol.MODE_EXPLORE;
    private static final long RENEW_INTERVAL_MS = 1000; // comfortably under the lease's ~2.25s TTL
    private static final long LEASE_RETRY_BASE_MS = 2000;
    private static final long LEASE_RETRY_MAX_MS = 30000;
    /** Under the launcher's 2250 ms TTL, with room for its 500 ms check interval. */
    private static final long LEASE_TRUST_MS = 1750;
    /** A CPL=2 refusal this close before a reading counts as part of it. */
    private static final long REFUSAL_WINDOW_MS = 250;
    /** Only the sign is used by DirectMotorDriver; positive turns left (see DriveSection). */
    private static final int TURN_LEFT = 20;
    private static final int TURN_RIGHT = -20;

    private final Context context;
    private final HandlerThread handlerThread = new HandlerThread("explore-lease");
    private final Handler handler;
    private final IBinder deathToken = new Binder();

    /** A fresh driver per lease: reusing one after disconnect() could leave its old
     * keepalive thread (mid-backoff) running beside the new one. */
    private volatile DirectMotorDriver driver;
    private volatile boolean driverConnected;
    private final LeaseTrust trust = new LeaseTrust(LEASE_TRUST_MS);
    /** Set first thing in release(); every lease callback checks it. */
    private volatile boolean released;
    /** Brain-thread only: the snapshot last turned into a reading, and that reading. The
     * loop polls every tick but the driver publishes a new snapshot only every ~100 ms. */
    private SensorSnapshot lastSnapshot;
    private SensorReading lastReading;
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
                    trust.renewed(SystemClock.elapsedRealtime());
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
            if (released) {
                return;
            }
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

    /** Asks for the lease; the driver connects only once it is granted. */
    void start() {
        bindLease();
    }

    /** Lease thread: connect a fresh driver (starting its keepalive, and so the readings). */
    private void connectDriver() {
        DirectMotorDriver d = new DirectMotorDriver();
        if (!d.connect()) {
            Log.e(TAG, "DirectMotorDriver connect() failed -- no readings, so the robot stays still");
            return;
        }
        // Rules out a ToF left switched off (a leftover TOFDS), which would read as a
        // stuck value (KTD3; docs/hardware/tof-sensor.md).
        try {
            d.enableTof();
        } catch (IOException e) {
            Log.w(TAG, "TOFEN failed", e);
        }
        driver = d;
        driverConnected = true;
    }

    /** Stop the wheels best-effort, then disconnect, so nothing of ours is on the UART. */
    private void stopAndDisconnect() {
        DirectMotorDriver d = driver;
        driverConnected = false;
        if (d == null) {
            return;
        }
        try {
            d.stop();
        } catch (IOException e) {
            Log.w(TAG, "stop before disconnect failed", e);
        }
        d.disconnect();
        driver = null;
    }

    /** Exit: release the lease, then disconnect. The loop has already stopped the wheels. */
    void release() {
        released = true;
        trust.lost();
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
        DirectMotorDriver d = driver;
        driverConnected = false;
        driver = null;
        if (d != null) {
            d.disconnect();
        }
        handlerThread.quitSafely();
    }

    private void bindLease() {
        if (released) {
            return;
        }
        Intent intent = new Intent(LauncherProtocol.DRIVE_LEASE_ACTION);
        intent.setPackage(LauncherProtocol.LAUNCHER_PACKAGE);
        if (!context.bindService(intent, leaseConnection, Context.BIND_AUTO_CREATE)) {
            loseLease("bindService to DriveLeaseService failed");
        }
    }

    private void tryAcquire() {
        DriveLease l = lease;
        if (l == null || released) {
            return;
        }
        try {
            if (l.acquire(deathToken, CLIENT_ID)) {
                if (released) {
                    // Exit raced the acquire: hand it straight back.
                    l.release(CLIENT_ID);
                    return;
                }
                connectDriver();
                trust.renewed(SystemClock.elapsedRealtime());
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
        trust.lost();
        leaseHeld = false;
        handler.removeCallbacks(renewLoop);
        stopAndDisconnect();
        if (!released) {
            scheduleLeaseRetry();
        }
    }

    private void scheduleLeaseRetry() {
        handler.removeCallbacks(leaseRetry);
        leaseRetryAttempt++;
        long delay = Math.min(LEASE_RETRY_BASE_MS << Math.min(leaseRetryAttempt - 1, 4), LEASE_RETRY_MAX_MS);
        Log.i(TAG, "retrying the drive lease in " + delay + "ms (attempt " + leaseRetryAttempt + ")");
        handler.postDelayed(leaseRetry, delay);
    }

    /** The MikoExploreLookThenGo hook: look-then-go navigation instead of continuous (KTD7). */
    static boolean lookThenGo() {
        return hook("MikoExploreLookThenGo");
    }

    private static boolean hook(String tag) {
        return Log.isLoggable(tag, Log.DEBUG);
    }

    // ---- ExploreLoop.Lease ----

    @Override
    public boolean held() {
        return leaseHeld && trust.trusted(SystemClock.elapsedRealtime());
    }

    // ---- ExploreLoop.Sensors ----

    @Override
    public SensorReading latest() {
        DirectMotorDriver d = driver;
        if (!driverConnected || d == null) {
            return null;
        }
        SensorSnapshot s = d.latestSensors();
        if (s == null) {
            return null;
        }
        if (s == lastSnapshot) {
            return lastReading;
        }
        if (lastSnapshot == null) {
            Log.i(TAG, "first sensor reading: " + s);
        }
        long refusal = d.lastRefusalMs();
        Integer cpl = refusal != 0 && refusal >= s.timestampMs - REFUSAL_WINDOW_MS ? Integer.valueOf(2) : null;
        // fault stays false: SensorSnapshot.fault only means tof read 16383, which the
        // classifier judges itself (it can be an edge when the IR flag agrees). A dead
        // keepalive shows up as the readings going stale.
        lastSnapshot = s;
        // The wheel counts are signed (reverse counts down past 0): presence is its own flag.
        lastReading = new SensorReading(s.timestampMs, s.tof, s.ir1, s.ir2, cpl, false,
                s.hasWheels, s.wheelLeft, s.wheelRight, s.hasGyro, s.gyroX, s.gyroY, s.gyroZ);
        return lastReading;
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

    @Override
    public boolean curiousNow() {
        return hook("MikoExploreCurious");
    }

    @Override
    public boolean spinInPlace() {
        return hook("MikoExploreSpin");
    }

    // ---- ExploreLoop.Wheels ----

    @Override
    public void forwardTick() throws IOException {
        requireLease().driveContinuous(2, 0);
    }

    @Override
    public void turn(ExploreBrain.Direction direction) throws IOException {
        requireLease().driveTurnSustained(direction == ExploreBrain.Direction.LEFT ? TURN_LEFT : TURN_RIGHT);
    }

    @Override
    public void backTick() throws IOException {
        requireLease().driveContinuous(-2, 0);
    }

    @Override
    /** Stop goes out whether or not the lease is still trusted. */
    public void stop() throws IOException {
        requireDriver().stop();
    }

    private DirectMotorDriver requireDriver() throws IOException {
        DirectMotorDriver d = driver;
        if (!driverConnected || d == null || !d.isConnected()) {
            throw new IOException("motor driver not connected");
        }
        return d;
    }

    /** The driver, but only while the lease is trusted: checked at the write itself, so a
     * lease that lapsed after the brain's last check still blocks the motion. */
    private DirectMotorDriver requireLease() throws IOException {
        if (!held()) {
            throw new IOException("drive lease not held or not recently renewed");
        }
        return requireDriver();
    }

    /** The brain's clock: the same elapsedRealtime the driver stamps readings with. */
    static final ExploreBrain.Clock CLOCK = new ExploreBrain.Clock() {
        @Override
        public long nowMs() {
            return SystemClock.elapsedRealtime();
        }
    };
}
