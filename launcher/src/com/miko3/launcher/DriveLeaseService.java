package com.miko3.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.miko3.shared.DriveLease;
import com.miko3.shared.RobotControlClient;

/**
 * Exported bound Service implementing R3/R13-R15's control-arbitration
 * mechanism (KTD3/KTD3b): exactly one mode may hold the drive lease at a
 * time, a crashed holder is detected via Binder.linkToDeath(), a hung
 * holder is detected via a TTL heartbeat, and every release path issues a
 * stop-motors command as part of releasing the lock (R14) — never only a
 * software-lock release.
 *
 * Started alongside InfoHttpServer in LauncherApp.onCreate so it survives
 * independent of any Activity, and exists for the whole launcher process
 * lifetime — exactly the lifetime a mode's own crash or exit must not
 * take down, since a mode is what this service arbitrates between.
 */
public class DriveLeaseService extends Service {
    private static final String TAG = "DriveLeaseService";
    public static final String ACTION_BIND = "com.miko3.launcher.DRIVE_LEASE";

    /** ~3x R13's ~750ms drive-command watchdog window (KTD3). */
    private static final long TTL_MS = 2250;
    private static final long TTL_CHECK_INTERVAL_MS = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RobotControlClient robotClient;

    private String holderId;
    private IBinder holderDeathToken;
    private long lastRenewElapsedMs;

    private final IBinder.DeathRecipient deathRecipient = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            Log.w(TAG, "holder '" + holderId + "' died — releasing lease and stopping");
            releaseInternal("binder_died");
        }
    };

    private final Runnable ttlCheck = new Runnable() {
        @Override
        public void run() {
            if (holderId != null
                    && SystemClock.elapsedRealtime() - lastRenewElapsedMs > TTL_MS) {
                Log.w(TAG, "holder '" + holderId + "' TTL expired — releasing lease and stopping");
                releaseInternal("ttl_expired");
            }
            handler.postDelayed(this, TTL_CHECK_INTERVAL_MS);
        }
    };

    private final DriveLease.Stub binder = new DriveLease.Stub() {
        @Override
        public synchronized boolean acquire(IBinder deathToken, String clientId) {
            if (holderId != null && !holderId.equals(clientId)) {
                return false;
            }
            if (holderId == null) {
                holderId = clientId;
                holderDeathToken = deathToken;
                try {
                    deathToken.linkToDeath(deathRecipient, 0);
                } catch (RemoteException e) {
                    // Caller was already dead by the time we tried to link — treat as
                    // never having acquired it.
                    holderId = null;
                    holderDeathToken = null;
                    return false;
                }
                Log.i(TAG, "lease acquired by '" + clientId + "'");
            }
            lastRenewElapsedMs = SystemClock.elapsedRealtime();
            return true;
        }

        @Override
        public synchronized void renew(String clientId) {
            if (clientId != null && clientId.equals(holderId)) {
                lastRenewElapsedMs = SystemClock.elapsedRealtime();
            }
        }

        @Override
        public synchronized void release(String clientId) {
            if (clientId != null && clientId.equals(holderId)) {
                Log.i(TAG, "lease released cleanly by '" + clientId + "'");
                releaseInternal("clean_release");
            }
        }

        @Override
        public synchronized String getHolder() {
            return holderId;
        }
    };

    private synchronized void releaseInternal(String reason) {
        if (holderId == null) {
            return;
        }
        if (holderDeathToken != null) {
            try {
                holderDeathToken.unlinkToDeath(deathRecipient, 0);
            } catch (java.util.NoSuchElementException ignored) {
                // already unlinked (e.g. binderDied fired concurrently)
            }
        }
        holderId = null;
        holderDeathToken = null;
        issueStop(reason);
    }

    private void issueStop(String reason) {
        if (robotClient == null || !robotClient.isConnected()) {
            Log.e(TAG, "cannot issue stop-motors for release (" + reason
                    + ") — RobotControlClient not connected");
            return;
        }
        try {
            robotClient.stop();
            Log.i(TAG, "stop-motors issued (" + reason + ")");
        } catch (RemoteException e) {
            Log.e(TAG, "stop-motors command failed (" + reason + ")", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        robotClient = new RobotControlClient(this, new RobotControlClient.Listener() {
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
        handler.postDelayed(ttlCheck, TTL_CHECK_INTERVAL_MS);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ttlCheck);
        if (robotClient != null) {
            robotClient.disconnect();
        }
        super.onDestroy();
    }
}
