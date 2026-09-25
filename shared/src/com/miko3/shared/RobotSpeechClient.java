package com.miko3.shared;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * How a mode makes the robot speak (voice plan U5; R8, R11): hand the
 * launcher's speech service a line, and be told when it has finished.
 *
 * <pre>
 *   RobotSpeechClient speech = new RobotSpeechClient(context);
 *   speech.speak("Hi there, I don't think we've met!", listener);
 * </pre>
 *
 * speak() returns at once from any thread. The line queues behind whatever
 * the robot is already saying, from any mode (KTD5); exactly one listener
 * method is then called, once, on a Binder thread: onFinished() when its
 * last sound has actually played, onCancelled() when this app's cancel()
 * dropped or stopped it, or onFailed(reason) when it was never queued (the
 * launcher refused the line, or couldn't be reached), the launcher's voice
 * broke while making or playing it (reason "voice failed"), or the launcher
 * went away with it still queued ("speech unavailable") or died.
 * cancel() drops every line this app queued, and stops one that is playing at
 * the next sentence boundary; to interrupt yourself, cancel() then speak().
 *
 * The client stays bound to the launcher from speak() until the last
 * outstanding line's callback, so the launcher isn't at background priority
 * while it speaks, then unbinds by itself.
 *
 * Speak after heavy work pauses (KTD8). Speech is made on the robot's four
 * CPU cores; with Explore's camera and detector busy, the medium voice
 * measured 4.9 s to first sound instead of 1.1 s. The service can't know
 * when a mode's work is heavy, so that timing is the caller's job: a mode
 * that runs heavy work (Explore's detector) should call speak() once that
 * work has paused (after a look finishes, while the detector is idle) and
 * hold off starting more until onFinished() or onCancelled().
 */
public final class RobotSpeechClient {
    /** How one line ended. Called once, on a Binder or worker thread. */
    public interface Listener {
        void onFinished();

        void onCancelled();

        void onFailed(String reason);
    }

    private final Context app;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "robot-speech-client");
            t.setDaemon(true);
            return t;
        }
    });

    // Guarded by this.
    private final Set<Line> outstanding = new LinkedHashSet<Line>();
    private final List<Line> unsent = new ArrayList<Line>();
    private RobotSpeech service;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            List<Line> send;
            synchronized (RobotSpeechClient.this) {
                service = RobotSpeech.Stub.asInterface(binder);
                send = new ArrayList<Line>(unsent);
                unsent.clear();
            }
            for (Line line : send) {
                worker.execute(line);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // The launcher's process died: no callback will come for anything
            // it had queued. Android rebinds by itself when it restarts.
            List<Line> lost;
            synchronized (RobotSpeechClient.this) {
                service = null;
                lost = new ArrayList<Line>(outstanding);
                lost.removeAll(unsent);
            }
            for (Line line : lost) {
                line.fail("launcher speech service died");
            }
        }
    };

    public RobotSpeechClient(Context context) {
        this.app = context.getApplicationContext();
    }

    /** Queues text to be spoken; see the class comment for the listener. */
    public void speak(String text, Listener listener) {
        Line line = new Line(text, listener);
        RobotSpeech s;
        synchronized (this) {
            outstanding.add(line);
            if (!bound) {
                Intent intent = new Intent(LauncherProtocol.ROBOT_SPEECH_ACTION);
                intent.setPackage(LauncherProtocol.LAUNCHER_PACKAGE);
                bound = app.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            }
            s = service;
            if (bound && s == null) {
                unsent.add(line);
                return;
            }
        }
        if (s == null) {
            line.fail("launcher speech service not found");
            return;
        }
        worker.execute(line);
    }

    /** Drops every line this app queued and stops its playing line at the
     * next sentence boundary. Their listeners get onCancelled(). A line not
     * yet handed to the launcher (still waiting for the binding, or handed to
     * the worker but not sent) is marked cancelled under the lock, so it is
     * never sent even if the connection raced this call. */
    public void cancel() {
        List<Line> dropped = new ArrayList<Line>();
        final RobotSpeech s;
        synchronized (this) {
            for (Line line : outstanding) {
                if (!line.sent) {
                    line.cancelled = true;
                    dropped.add(line);
                }
            }
            unsent.clear();
            s = service;
        }
        for (Line line : dropped) {
            line.cancelled();
        }
        if (s != null) {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        s.cancel();
                    } catch (RemoteException | RuntimeException ignored) {
                        // The launcher died (onServiceDisconnected fails the lines)
                        // or turned this app away (speak() already failed them).
                    }
                }
            });
        }
    }

    private void lineDone(Line line) {
        synchronized (this) {
            outstanding.remove(line);
            unsent.remove(line);
            if (outstanding.isEmpty() && bound) {
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

    /** One line: sends itself when run, and is the launcher's callback for it. */
    private final class Line extends RobotSpeech.Callback.Stub implements Runnable {
        final String text;
        final Listener listener;
        final AtomicBoolean done = new AtomicBoolean();
        // Guarded by RobotSpeechClient.this: handed to the launcher, or
        // cancelled by this app before it was.
        boolean sent;
        boolean cancelled;

        Line(String text, Listener listener) {
            this.text = text;
            this.listener = listener;
        }

        @Override
        public void run() {
            RobotSpeech s;
            synchronized (RobotSpeechClient.this) {
                if (cancelled) {
                    // cancel() won the race and has already told the listener.
                    return;
                }
                sent = true;
                s = service;
            }
            if (s == null) {
                fail("launcher speech service died");
                return;
            }
            try {
                s.speak(text, this);
            } catch (SecurityException e) {
                fail("launcher speech service refused this app");
            } catch (IllegalArgumentException e) {
                fail(e.getMessage());
            } catch (RemoteException | RuntimeException e) {
                fail("launcher speech service died");
            }
        }

        @Override
        public void finished() {
            if (done.compareAndSet(false, true)) {
                lineDone(this);
                listener.onFinished();
            }
        }

        @Override
        public void cancelled() {
            if (done.compareAndSet(false, true)) {
                lineDone(this);
                listener.onCancelled();
            }
        }

        /** The launcher's voice broke on this line, or went away with it queued. */
        @Override
        public void failed(String reason) {
            fail(reason == null ? "launcher speech failed" : reason);
        }

        void fail(String reason) {
            if (done.compareAndSet(false, true)) {
                lineDone(this);
                listener.onFailed(reason);
            }
        }
    }
}
