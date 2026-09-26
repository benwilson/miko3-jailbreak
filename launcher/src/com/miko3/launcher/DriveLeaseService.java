package com.miko3.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.miko3.shared.DirectMotorDriver;
import com.miko3.shared.DriveLease;
import com.miko3.shared.LauncherProtocol;

import java.io.IOException;

/**
 * Exported bound Service implementing R3/R13-R15's control-arbitration
 * mechanism (KTD3/KTD3b): exactly one mode may hold the drive lease at a
 * time, a crashed holder is detected via Binder.linkToDeath(), a hung
 * holder is detected via a TTL heartbeat, and every release path issues a
 * stop-motors command as part of releasing the lock (R14) — never only a
 * software-lock release.
 *
 * The holder, renew, death and TTL bookkeeping lives in LeaseKeeper (meeting
 * plan U3 extracted it so the ears session shares it); this service adds the
 * Binder, the TTL clock and the stop-motors on every release. The keeper's
 * one lock replaces the service monitor the Binder methods and the TTL check
 * used to share, so a main-thread TTL read can never race a Binder-thread
 * acquire()/renew()/release() (confirmed live as a real race risk on this
 * hardware's weaker ARM memory ordering). The keeper also keeps the per-
 * acquisition death recipient: a late death notification for a since-
 * superseded holder is ignored rather than clearing the new holder's session.
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

    // issueStop() below does blocking UART IO (DirectMotorDriver.connect()/stop()/
    // disconnect()) and is reached from the keeper's release callback, inside the
    // keeper's lock, from the main Looper's TTL check as well as from Binder
    // threads. A dedicated thread keeps that IO off both the main thread and
    // whichever Binder thread called acquire()/release().
    private final HandlerThread stopThread = new HandlerThread("drive-lease-stop");
    private Handler stopHandler;

    private final LeaseKeeper keeper = new LeaseKeeper(TTL_MS, new LeaseKeeper.Released() {
        @Override
        public void released(String holder, String reason) {
            if (LeaseKeeper.RELEASE_TTL.equals(reason)) {
                Log.w(TAG, "holder '" + holder + "' TTL expired — releasing lease and stopping");
            } else if (LeaseKeeper.RELEASE_DIED.equals(reason)) {
                Log.w(TAG, "holder '" + holder + "' died — releasing lease and stopping");
            } else {
                Log.i(TAG, "lease released cleanly by '" + holder + "'");
            }
            issueStop(reason);
        }
    });

    private final Runnable ttlCheck = new Runnable() {
        @Override
        public void run() {
            keeper.check(SystemClock.elapsedRealtime());
            handler.postDelayed(this, TTL_CHECK_INTERVAL_MS);
        }
    };

    private final DriveLease.Stub binder = new DriveLease.Stub() {
        @Override
        public boolean acquire(final IBinder deathToken, String clientId) {
            if (clientId == null) {
                return false;
            }
            boolean fresh = keeper.holder() == null;
            boolean got = keeper.acquire(clientId, new LeaseKeeper.Token() {
                private IBinder.DeathRecipient recipient;

                @Override
                public void linkToDeath(final Runnable onDeath) throws RemoteException {
                    IBinder.DeathRecipient r = new IBinder.DeathRecipient() {
                        @Override
                        public void binderDied() {
                            onDeath.run();
                        }
                    };
                    deathToken.linkToDeath(r, 0);
                    recipient = r;
                }

                @Override
                public void unlinkToDeath(Runnable onDeath) {
                    if (recipient == null) {
                        return;
                    }
                    try {
                        deathToken.unlinkToDeath(recipient, 0);
                    } catch (java.util.NoSuchElementException ignored) {
                        // already unlinked (e.g. binderDied fired concurrently)
                    }
                    recipient = null;
                }
            }, SystemClock.elapsedRealtime());
            if (got && fresh) {
                Log.i(TAG, "lease acquired by '" + clientId + "'");
            }
            return got;
        }

        @Override
        public boolean renew(String clientId) {
            return keeper.renew(clientId, SystemClock.elapsedRealtime());
        }

        @Override
        public void release(String clientId) {
            keeper.release(clientId);
        }

        @Override
        public String getHolder() {
            return keeper.holder();
        }
    };

    /**
     * Own DirectMotorDriver connection, independent of any mode's — this coordinator
     * used to reach the motors via RobotControlClient's AIDL bind to ServiceExam, which
     * cannot work now that ServiceExam is disabled (see DirectMotorDriver's own class
     * javadoc for why driving no longer goes through it at all). Unlike
     * RobotControlClient's async bind, DirectMotorDriver.connect() is synchronous, so
     * there's no "connect pending" state to track or retry against — a stop either goes
     * out now or is logged and dropped, matching this class's existing fire-and-forget
     * contract (same as DriveController's own use of it).
     *
     * KNOWN, ACCEPTED RISK: this opens a second, independent writer to /dev/ttyS2 that
     * can briefly overlap with a still-connected mode's own DirectMotorDriver — e.g. a
     * clean release, where DriveController.release() calls lease.release() (landing
     * here) before its own motorDriver.disconnect(). DirectMotorDriver's class javadoc
     * already documents that this device node doesn't arbitrate between concurrent
     * writers and two frames landing at once can corrupt each other. Accepted here for
     * the same reason it was accepted for ServiceExam-vs-DirectMotorDriver overlap
     * during a drive session: a best-effort stop attempt that might occasionally lose a
     * race is strictly better than this backstop being permanently unable to reach the
     * motors at all, which was the actual state once ServiceExam got disabled.
     */
    private void issueStop(final String reason) {
        stopHandler.post(new Runnable() {
            @Override
            public void run() {
                DirectMotorDriver driver = new DirectMotorDriver();
                if (!driver.connect()) {
                    Log.e(TAG, "cannot issue stop-motors for release (" + reason
                            + ") — DirectMotorDriver connect() failed");
                    return;
                }
                try {
                    driver.stop();
                    Log.i(TAG, "stop-motors issued (" + reason + ")");
                } catch (IOException e) {
                    Log.e(TAG, "stop-motors command failed (" + reason + ")", e);
                } finally {
                    driver.disconnect();
                }
            }
        });
    }

    @Override
    public void onCreate() {
        super.onCreate();
        stopThread.start();
        stopHandler = new Handler(stopThread.getLooper());
        handler.postDelayed(ttlCheck, TTL_CHECK_INTERVAL_MS);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(ttlCheck);
        stopThread.quitSafely();
        super.onDestroy();
    }
}
