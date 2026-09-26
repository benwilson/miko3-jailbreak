package com.miko3.shared;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * A mode's handle on the launcher's continuous ears session (meeting plan U3;
 * KTD1, KTD6). Binds ListenService by its ears action, opens the session with
 * the caller's charger latch, renews it every RobotEars.RENEW_PERIOD_MS on
 * its own thread with the latest latch (setCharger), and streams every heard
 * utterance to the Listener on that thread. close() closes the session,
 * unbinds and stops the thread; it is idempotent, and nothing reaches the
 * Listener after it.
 *
 * A renew the launcher answers false (the session was released: missed
 * renews, a death, another opener), a launcher without the ears Binder (an
 * older install; the proxy throws), or a launcher that dies is reported once
 * through onLost(reason); the client then stops renewing and the mode
 * decides whether to open again.
 */
public final class RobotEarsClient {
    public interface Listener {
        /** One heard utterance; see RobotEars.Callback.heard. */
        void onHeard(String text, int side, float angle, int tier, long at, boolean partial);

        /** The session is gone; reason is fixed text. Called at most once per open(). */
        void onLost(String reason);
    }

    private final Context app;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "robot-ears-client");
                    t.setDaemon(true);
                    return t;
                }
            });

    // Guarded by this.
    private RobotEars service;
    private Session current;
    private boolean bound;
    private boolean closed;
    private volatile boolean charger;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Session s;
            synchronized (RobotEarsClient.this) {
                if (closed) {
                    return;
                }
                service = RobotEars.Stub.asInterface(binder);
                s = current;
            }
            if (s != null) {
                post(s.opener);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Session s;
            synchronized (RobotEarsClient.this) {
                if (closed) {
                    return;
                }
                service = null;
                s = current;
            }
            if (s != null) {
                s.lost("launcher ears service died");
            }
        }
    };

    public RobotEarsClient(Context context) {
        this.app = context.getApplicationContext();
    }

    /** The caller's charger latch, sent with the next renew (KTD6). */
    public void setCharger(boolean latched) {
        charger = latched;
    }

    /**
     * Opens the session (binding first when needed) and starts renewing.
     * One open at a time: a second open before close() fails at once.
     */
    public void open(boolean chargerLatched, Listener listener) {
        charger = chargerLatched;
        final Session s = new Session(listener);
        boolean bindNow;
        boolean connected;
        synchronized (this) {
            if (closed) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onLost("ears client closed");
                    }
                });
                return;
            }
            if (current != null) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onLost("ears already open");
                    }
                });
                return;
            }
            current = s;
            connected = service != null;
            bindNow = !bound;
            if (bindNow) {
                bound = true;
            }
        }
        if (bindNow) {
            Intent intent = new Intent(LauncherProtocol.ROBOT_EARS_ACTION);
            intent.setPackage(LauncherProtocol.LAUNCHER_PACKAGE);
            if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                synchronized (this) {
                    bound = false;
                }
                s.lost("launcher ears service not found");
            }
        } else if (connected) {
            post(s.opener);
        }
    }

    /** A conversation listen for up to maxMs (KTD1); the reply arrives through onHeard. */
    public void listen(final long maxMs) {
        post(new Runnable() {
            @Override
            public void run() {
                RobotEars s = serviceOrNull();
                if (s == null) {
                    return;
                }
                try {
                    s.listen(maxMs);
                } catch (RemoteException | RuntimeException e) {
                    lostCurrent("listen failed: " + e.getClass().getSimpleName());
                }
            }
        });
    }

    /** Opens the launcher's deaf window for durationMs before a local clip plays. */
    public void clipWindow(final long durationMs) {
        post(new Runnable() {
            @Override
            public void run() {
                RobotEars s = serviceOrNull();
                if (s == null) {
                    return;
                }
                try {
                    s.clipWindow(durationMs);
                } catch (RemoteException | RuntimeException e) {
                    lostCurrent("clip window failed: " + e.getClass().getSimpleName());
                }
            }
        });
    }

    /** A shove or a collision stop at atElapsedMs (SystemClock.elapsedRealtime), for the classifier. */
    public void shoved(final long atElapsedMs) {
        post(new Runnable() {
            @Override
            public void run() {
                RobotEars s = serviceOrNull();
                if (s == null) {
                    return;
                }
                try {
                    s.shoved(atElapsedMs);
                } catch (RemoteException | RuntimeException e) {
                    lostCurrent("shove failed: " + e.getClass().getSimpleName());
                }
            }
        });
    }

    /** Closes the session, unbinds and stops the worker. Idempotent. */
    public void close() {
        final RobotEars s;
        Session was;
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            s = service;
            was = current;
            current = null;
            service = null;
        }
        if (was != null) {
            was.stopRenewing();
            was.ended = true;
        }
        if (s != null) {
            try {
                s.close();
            } catch (RemoteException | RuntimeException ignored) {
                // The launcher will release it on the missed renews anyway.
            }
        }
        synchronized (this) {
            if (bound) {
                bound = false;
                try {
                    app.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                    // Nothing was registered.
                }
            }
        }
        worker.shutdownNow();
    }

    private synchronized RobotEars serviceOrNull() {
        return closed ? null : service;
    }

    private void lostCurrent(String reason) {
        Session s;
        synchronized (this) {
            s = current;
        }
        if (s != null) {
            s.lost(reason);
        }
    }

    /** Runs r on the worker; after close() it is dropped. */
    private void post(Runnable r) {
        try {
            worker.execute(r);
        } catch (RejectedExecutionException closedMeanwhile) {
            // close() ran first; its session was already ended.
        }
    }

    /** One open session: the launcher's callback, the opener and the renew loop. */
    private final class Session extends RobotEars.Callback.Stub {
        final Listener listener;
        volatile boolean ended;
        private ScheduledFuture<?> renewing;

        final Runnable opener = new Runnable() {
            @Override
            public void run() {
                RobotEars s = serviceOrNull();
                if (s == null || ended) {
                    return;
                }
                try {
                    s.open(Session.this, charger);
                } catch (RemoteException e) {
                    lost("launcher ears unavailable: " + e.getMessage());
                    return;
                } catch (RuntimeException e) {
                    // IllegalStateException (held by another app) or SecurityException.
                    lost("ears refused: " + e.getMessage());
                    return;
                }
                synchronized (RobotEarsClient.this) {
                    if (!ended && renewing == null) {
                        renewing = worker.scheduleAtFixedRate(renew, RobotEars.RENEW_PERIOD_MS,
                                RobotEars.RENEW_PERIOD_MS, TimeUnit.MILLISECONDS);
                    }
                }
            }
        };

        final Runnable renew = new Runnable() {
            @Override
            public void run() {
                RobotEars s = serviceOrNull();
                if (s == null || ended) {
                    return;
                }
                boolean ok;
                try {
                    ok = s.renew(charger);
                } catch (RemoteException | RuntimeException e) {
                    lost("renew failed: " + e.getClass().getSimpleName());
                    return;
                }
                if (!ok) {
                    lost("ears session released by the launcher");
                }
            }
        };

        Session(Listener listener) {
            this.listener = listener;
        }

        void stopRenewing() {
            ScheduledFuture<?> f;
            synchronized (RobotEarsClient.this) {
                f = renewing;
                renewing = null;
            }
            if (f != null) {
                f.cancel(false);
            }
        }

        /** Ends this session once and tells the listener, on the worker. */
        void lost(final String reason) {
            synchronized (RobotEarsClient.this) {
                if (ended) {
                    return;
                }
                ended = true;
                if (current == this) {
                    current = null;
                }
            }
            stopRenewing();
            post(new Runnable() {
                @Override
                public void run() {
                    listener.onLost(reason);
                }
            });
        }

        @Override
        public void heard(final String text, final int side, final float angle, final int tier, final long at,
                          final boolean partial) {
            if (ended) {
                return;
            }
            post(new Runnable() {
                @Override
                public void run() {
                    if (!ended) {
                        listener.onHeard(text, side, angle, tier, at, partial);
                    }
                }
            });
        }
    }
}
