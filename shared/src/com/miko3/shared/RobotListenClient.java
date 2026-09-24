package com.miko3.shared;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * How a mode hears one short spoken reply (explore-on-claude plan U3; R11,
 * R12, KTD8): ask the launcher to listen, and be told what it heard.
 *
 * <pre>
 *   RobotListenClient ears = new RobotListenClient(context);
 *   ears.listen(6000, listener);
 * </pre>
 *
 * listen() returns at once from any thread. The launcher first waits until
 * the robot has finished speaking (the microphone is ducked while he plays,
 * so ask the question with RobotSpeechClient, and listen once its
 * onFinished() has come), then records until the speaker stops or maxMs of
 * audio, whichever is first. Exactly one listener method is then called,
 * once, on a Binder or worker thread: onHeard(transcript) with what the
 * recognizer heard (upper case, no punctuation; NameExtractor pulls a name
 * out of it), onNoSpeech() when it heard no words, or onFailed(reason) when
 * it couldn't listen (another listen holds the microphone, the model isn't
 * loaded, the launcher refused this app or died, or no answer came within
 * maxMs plus TIMEOUT_MARGIN_MS).
 *
 * One listen at a time per client: a listen() while one is outstanding fails
 * at once. The client stays bound to the launcher until the answer, so the
 * launcher isn't at background priority while it records, then unbinds.
 */
public final class RobotListenClient {
    /** How one listen ended. Called once, on a Binder or worker thread. */
    public interface Listener {
        void onHeard(String transcript);

        void onNoSpeech();

        void onFailed(String reason);
    }

    /** Beyond maxMs, how long to wait for the launcher's answer: its wait for
     * the speech queue to go idle, loading, and decoding. */
    public static final long TIMEOUT_MARGIN_MS = 30000;

    private final Context app;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "robot-listen-client");
                    t.setDaemon(true);
                    return t;
                }
            });

    // Guarded by this.
    private Call current;
    private RobotListen service;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            Call send;
            synchronized (RobotListenClient.this) {
                service = RobotListen.Stub.asInterface(binder);
                send = current != null && !current.sent ? current : null;
            }
            if (send != null) {
                worker.execute(send);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The launcher's process died: no answer will come.
            Call lost;
            synchronized (RobotListenClient.this) {
                service = null;
                lost = current != null && current.sent ? current : null;
            }
            if (lost != null) {
                lost.fail("launcher listen service died");
            }
        }
    };

    public RobotListenClient(Context context) {
        this.app = context.getApplicationContext();
    }

    /** Listens for one reply of up to maxMs; see the class comment. */
    public void listen(long maxMs, Listener listener) {
        final Call call = new Call(maxMs, listener);
        RobotListen s;
        synchronized (this) {
            if (current != null) {
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        call.listener.onFailed("already listening");
                    }
                });
                return;
            }
            current = call;
            if (!bound) {
                Intent intent = new Intent(LauncherProtocol.ROBOT_LISTEN_ACTION);
                intent.setPackage(LauncherProtocol.LAUNCHER_PACKAGE);
                bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            }
            s = service;
        }
        worker.schedule(new Runnable() {
            @Override
            public void run() {
                call.fail("no answer from the launcher");
            }
        }, Math.max(0, maxMs) + TIMEOUT_MARGIN_MS, TimeUnit.MILLISECONDS);
        if (!bound) {
            call.fail("launcher listen service not found");
            return;
        }
        if (s != null) {
            worker.execute(call);
        }
        // else onServiceConnected sends it.
    }

    private void done(Call call) {
        synchronized (this) {
            if (current != call) {
                return;
            }
            current = null;
            if (bound) {
                bound = false;
                service = null;
                try {
                    app.unbindService(connection);
                } catch (IllegalArgumentException ignored) {
                    // Nothing was registered.
                }
            }
        }
    }

    /** One listen: sends itself when run, and is the launcher's callback for it. */
    private final class Call extends RobotListen.Callback.Stub implements Runnable {
        final long maxMs;
        final Listener listener;
        final AtomicBoolean ended = new AtomicBoolean();
        volatile boolean sent;

        Call(long maxMs, Listener listener) {
            this.maxMs = maxMs;
            this.listener = listener;
        }

        @Override
        public void run() {
            RobotListen s;
            synchronized (RobotListenClient.this) {
                s = service;
                sent = true;
            }
            if (s == null) {
                fail("launcher listen service died");
                return;
            }
            try {
                s.listen(maxMs, this);
            } catch (SecurityException e) {
                fail("launcher listen service refused this app");
            } catch (IllegalStateException | IllegalArgumentException e) {
                fail(e.getMessage());
            } catch (RemoteException | RuntimeException e) {
                fail("launcher listen service died");
            }
        }

        @Override
        public void heard(String transcript) {
            if (ended.compareAndSet(false, true)) {
                done(this);
                listener.onHeard(transcript);
            }
        }

        @Override
        public void noSpeech() {
            if (ended.compareAndSet(false, true)) {
                done(this);
                listener.onNoSpeech();
            }
        }

        @Override
        public void failed(String reason) {
            fail(reason);
        }

        void fail(String reason) {
            if (ended.compareAndSet(false, true)) {
                done(this);
                listener.onFailed(reason);
            }
        }
    }
}
