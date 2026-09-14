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
import com.miko3.shared.LauncherProtocol;
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
    public static final String ACTION_BIND = LauncherProtocol.DRIVE_LEASE_ACTION;

    /** ~3x R13's ~750ms drive-command watchdog window (KTD3). */
    private static final long TTL_MS = 2250;
    private static final long TTL_CHECK_INTERVAL_MS = 500;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RobotControlClient robotClient;

    private String holderId;
    private IBinder holderDeathToken;
    private IBinder.DeathRecipient holderDeathRecipient;
    private long lastRenewElapsedMs;
    private volatile boolean stopOwed;

    private final Runnable ttlCheck = new Runnable() {
        @Override
        public void run() {
            // Locks on DriveLeaseService.this — the same monitor every method below
            // uses — so this main-thread read of holderId/lastRenewElapsedMs can't
            // race a Binder-thread acquire()/renew()/release() call. Binder methods
            // used to be `synchronized` (locking the anonymous Stub instance instead
            // of the enclosing service) while this read held no lock at all: two
            // different monitors for one critical section, confirmed live as a real
            // race risk on this hardware's weaker ARM memory ordering, not just a
            // theoretical JMM violation.
            synchronized (DriveLeaseService.this) {
                if (holderId != null
                        && SystemClock.elapsedRealtime() - lastRenewElapsedMs > TTL_MS) {
                    Log.w(TAG, "holder '" + holderId + "' TTL expired — releasing lease and stopping");
                    releaseInternal("ttl_expired");
                }
            }
            handler.postDelayed(this, TTL_CHECK_INTERVAL_MS);
        }
    };

    private final DriveLease.Stub binder = new DriveLease.Stub() {
        @Override
        public boolean acquire(IBinder deathToken, final String clientId) {
            synchronized (DriveLeaseService.this) {
                if (holderId != null && !holderId.equals(clientId)) {
                    return false;
                }
                if (holderId == null) {
                    // A DeathRecipient created fresh per acquisition, capturing this
                    // specific clientId, rather than one shared instance reused across
                    // every holder: binderDied() carries no argument identifying which
                    // IBinder died, so a single shared recipient can't tell "the holder
                    // that just died" from "a different, already-superseded holder" if
                    // its death notification was merely delayed. Confirmed live-reviewable
                    // race: holder A crashes, its death notification is still queued when
                    // holder B legitimately acquires, and the stale callback would clear
                    // B's session and stop B's robot mid-drive. Checking clientId against
                    // the *current* holderId before acting closes that window.
                    final IBinder.DeathRecipient recipient = new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            synchronized (DriveLeaseService.this) {
                                if (!clientId.equals(holderId)) {
                                    return; // stale notification for a since-superseded holder
                                }
                                Log.w(TAG, "holder '" + clientId + "' died — releasing lease and stopping");
                                releaseInternal("binder_died");
                            }
                        }
                    };
                    holderId = clientId;
                    holderDeathToken = deathToken;
                    holderDeathRecipient = recipient;
                    try {
                        deathToken.linkToDeath(recipient, 0);
                    } catch (RemoteException e) {
                        // Caller was already dead by the time we tried to link — treat as
                        // never having acquired it.
                        holderId = null;
                        holderDeathToken = null;
                        holderDeathRecipient = null;
                        return false;
                    }
                    Log.i(TAG, "lease acquired by '" + clientId + "'");
                }
                lastRenewElapsedMs = SystemClock.elapsedRealtime();
                return true;
            }
        }

        @Override
        public boolean renew(String clientId) {
            synchronized (DriveLeaseService.this) {
                if (clientId != null && clientId.equals(holderId)) {
                    lastRenewElapsedMs = SystemClock.elapsedRealtime();
                    return true;
                }
                return false;
            }
        }

        @Override
        public void release(String clientId) {
            synchronized (DriveLeaseService.this) {
                if (clientId != null && clientId.equals(holderId)) {
                    Log.i(TAG, "lease released cleanly by '" + clientId + "'");
                    releaseInternal("clean_release");
                }
            }
        }

        @Override
        public String getHolder() {
            synchronized (DriveLeaseService.this) {
                return holderId;
            }
        }
    };

    /** Caller must hold the DriveLeaseService.this monitor (see call sites above and
     * the DeathRecipient below — the one exception, documented at its call site). */
    private void releaseInternal(String reason) {
        if (holderId == null) {
            return;
        }
        if (holderDeathToken != null && holderDeathRecipient != null) {
            try {
                holderDeathToken.unlinkToDeath(holderDeathRecipient, 0);
            } catch (java.util.NoSuchElementException ignored) {
                // already unlinked (e.g. binderDied fired concurrently)
            }
        }
        holderId = null;
        holderDeathToken = null;
        holderDeathRecipient = null;
        issueStop(reason);
    }

    private void issueStop(String reason) {
        if (robotClient == null || !robotClient.isConnected()) {
            // RobotControlClient's own bind to ServiceExam is asynchronous (KTD1/U2) and
            // can still be pending here, e.g. a mode acquires and crashes before the
            // coordinator's own connect() has completed. Previously this just logged and
            // returned, permanently skipping the stop-motors obligation for this release.
            // Track it and retry once the client actually connects instead.
            stopOwed = true;
            Log.e(TAG, "cannot issue stop-motors for release (" + reason
                    + ") — RobotControlClient not connected; will retry on connect");
            return;
        }
        try {
            robotClient.stop();
            stopOwed = false;
            Log.i(TAG, "stop-motors issued (" + reason + ")");
        } catch (RemoteException e) {
            stopOwed = true;
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
                if (stopOwed) {
                    issueStop("retry_after_connect");
                }
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
