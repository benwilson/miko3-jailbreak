package com.miko3.shared;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Host-JVM checks for RobotSpeechClient's bookkeeping (which lines are
 * outstanding, which are still unsent, when it binds and unbinds), driven by
 * scripts/tests/test_speech_service.py. The client compiles unchanged against
 * the android.* stubs in ../stubs; a fake Context records binds and hands the
 * harness the ServiceConnection, and a fake launcher service records what it
 * is asked to say and ends lines through their real callbacks.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class SpeechClientHarness {
    private static final long WAIT_MS = 2000;

    /** Shared event log, in order. */
    static final class Log {
        final List<String> events = Collections.synchronizedList(new ArrayList<String>());

        void add(String e) {
            events.add(e);
        }

        int count(String prefix) {
            int n = 0;
            for (String s : snapshot()) {
                if (s.equals(prefix)) {
                    n++;
                }
            }
            return n;
        }

        List<String> snapshot() {
            synchronized (events) {
                return new ArrayList<String>(events);
            }
        }

        @Override
        public String toString() {
            return snapshot().toString();
        }
    }

    static final class FakeContext extends Context {
        final Log log;
        boolean bindResult = true;
        ServiceConnection connection;
        int binds;
        int unbinds;

        FakeContext(Log log) {
            this.log = log;
        }

        @Override
        public synchronized boolean bindService(Intent service, ServiceConnection conn, int flags) {
            binds++;
            connection = conn;
            log.add("bind:" + service.getAction() + "@" + service.getPackage());
            return bindResult;
        }

        @Override
        public synchronized void unbindService(ServiceConnection conn) {
            unbinds++;
            log.add("unbind");
        }

        synchronized ServiceConnection connection() {
            return connection;
        }
    }

    /** The launcher's side: records speak/cancel and keeps each line's callback. */
    static final class FakeService extends RobotSpeech.Stub {
        final Log log;
        final List<RobotSpeech.Callback> callbacks = Collections.synchronizedList(new ArrayList<RobotSpeech.Callback>());
        final List<String> said = Collections.synchronizedList(new ArrayList<String>());
        /** Run inside speak() of the given text, on the client's worker thread. */
        volatile String hookOn;
        volatile Runnable hook;

        FakeService(Log log) {
            this.log = log;
        }

        @Override
        public void speak(String text, RobotSpeech.Callback callback) {
            said.add(text);
            callbacks.add(callback);
            log.add("service-speak:" + text);
            Runnable h = hook;
            if (h != null && text.equals(hookOn)) {
                h.run();
            }
        }

        @Override
        public void cancel() {
            log.add("service-cancel");
        }

        RobotSpeech.Callback callback(int i) {
            return callbacks.get(i);
        }
    }

    static final class Heard implements RobotSpeechClient.Listener {
        final String name;
        final Log log;

        Heard(String name, Log log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void onFinished() {
            log.add("finished:" + name);
        }

        @Override
        public void onCancelled() {
            log.add("cancelled:" + name);
        }

        @Override
        public void onFailed(String reason) {
            log.add("failed:" + name + ":" + reason);
        }
    }

    interface Scenario {
        void run(String name) throws Exception;
    }

    interface Cond {
        boolean ok();
    }

    private static void check(String name, boolean ok, String detail) {
        System.out.println(ok ? "PASS " + name : "FAIL " + name + ": " + detail);
    }

    private static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            System.out.println("FAIL " + name + ": threw " + t);
        }
    }

    /** Waits up to WAIT_MS for c; true if it came true. */
    static boolean await(Cond c) throws InterruptedException {
        long end = System.currentTimeMillis() + WAIT_MS;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) {
                return true;
            }
            Thread.sleep(5);
        }
        return c.ok();
    }

    /** Lets the client's worker run anything already handed to it. */
    static void settle() throws InterruptedException {
        Thread.sleep(100);
    }

    static final class Fixture {
        final Log log = new Log();
        final FakeContext context = new FakeContext(log);
        final FakeService service = new FakeService(log);
        final RobotSpeechClient client = new RobotSpeechClient(context);

        void connect() {
            context.connection().onServiceConnected(new ComponentName("com.miko3.launcher", "SpeechService"),
                    service);
        }

        void disconnect() {
            context.connection().onServiceDisconnected(new ComponentName("com.miko3.launcher", "SpeechService"));
        }

        boolean awaitSaid(final int n) throws InterruptedException {
            return await(new Cond() {
                public boolean ok() {
                    return service.said.size() >= n;
                }
            });
        }
    }

    public static void main(String[] args) {
        scenario("speak_before_connect_is_sent_on_connect", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.client.speak("Hello.", new Heard("a", f.log));
                settle();
                boolean heldBack = f.service.said.isEmpty() && f.context.binds == 1;
                f.connect();
                boolean sent = f.awaitSaid(1);
                boolean bindOk = f.log.count("bind:" + LauncherProtocol.ROBOT_SPEECH_ACTION + "@"
                        + LauncherProtocol.LAUNCHER_PACKAGE) == 1;
                check(n, heldBack && sent && bindOk && f.service.said.get(0).equals("Hello."), f.log.toString());
            }
        });
        scenario("bind_failure_fails_the_line_once", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.context.bindResult = false;
                f.client.speak("Hello.", new Heard("a", f.log));
                settle();
                check(n, f.log.count("failed:a:launcher speech service not found") == 1
                        && f.log.count("cancelled:a") == 0 && f.log.count("finished:a") == 0
                        && f.service.said.isEmpty(), f.log.toString());
            }
        });
        scenario("disconnect_fails_sent_lines_but_leaves_unsent_alone", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.client.speak("Sent.", new Heard("a", f.log));
                f.connect();
                f.awaitSaid(1);
                f.disconnect();
                boolean aFailed = f.log.count("failed:a:launcher speech service died") == 1;
                // Still bound; Android reconnects on its own. B waits for it.
                f.client.speak("Unsent.", new Heard("b", f.log));
                settle();
                f.disconnect();
                settle();
                boolean bUntouched = f.log.toString().indexOf(":b") < 0 && f.service.said.size() == 1;
                f.connect();
                boolean bSent = f.awaitSaid(2);
                f.service.callback(1).finished();
                check(n, aFailed && bUntouched && bSent && f.log.count("finished:b") == 1
                        && f.log.count("failed:a:launcher speech service died") == 1, f.log.toString());
            }
        });
        scenario("cancel_before_connect_cancels_unsent_lines_once", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.client.speak("One.", new Heard("a", f.log));
                f.client.speak("Two.", new Heard("b", f.log));
                f.client.cancel();
                f.client.cancel();
                f.connect();
                settle();
                check(n, f.log.count("cancelled:a") == 1 && f.log.count("cancelled:b") == 1
                        && f.service.said.isEmpty() && f.log.count("service-cancel") == 0
                        && f.context.unbinds == 1, f.log.toString());
            }
        });
        scenario("cancel_while_connecting_skips_lines_not_yet_sent", new Scenario() {
            public void run(String n) throws Exception {
                // cancel() lands after onServiceConnected handed the waiting lines
                // to the worker but before the second was sent: it must not be.
                final Fixture f = new Fixture();
                final CountDownLatch connected = new CountDownLatch(1);
                f.service.hookOn = "First.";
                f.service.hook = new Runnable() {
                    public void run() {
                        try {
                            connected.await(WAIT_MS, TimeUnit.MILLISECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        f.client.cancel();
                    }
                };
                f.client.speak("First.", new Heard("a", f.log));
                f.client.speak("Second.", new Heard("b", f.log));
                f.connect();
                connected.countDown();
                await(new Cond() {
                    public boolean ok() {
                        return f.log.count("service-cancel") == 1;
                    }
                });
                settle();
                // First was sent, so the launcher's cancel() owns it; Second never leaves.
                check(n, f.service.said.size() == 1 && f.log.count("cancelled:b") == 1
                        && f.log.count("cancelled:a") == 0 && f.log.count("failed:b:launcher speech service died") == 0,
                        f.log.toString());
            }
        });
        scenario("last_callback_unbinds_and_next_speak_rebinds", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.client.speak("One.", new Heard("a", f.log));
                f.client.speak("Two.", new Heard("b", f.log));
                f.connect();
                f.awaitSaid(2);
                f.service.callback(0).finished();
                boolean stillBound = f.context.unbinds == 0;
                f.service.callback(1).finished();
                boolean unbound = f.context.unbinds == 1;
                f.client.speak("Three.", new Heard("c", f.log));
                check(n, stillBound && unbound && f.context.binds == 2 && f.log.count("finished:a") == 1
                        && f.log.count("finished:b") == 1, f.log.toString() + " binds=" + f.context.binds);
            }
        });
        scenario("duplicate_callback_fires_the_listener_once", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.client.speak("One.", new Heard("a", f.log));
                f.connect();
                f.awaitSaid(1);
                RobotSpeech.Callback cb = f.service.callback(0);
                cb.finished();
                cb.finished();
                cb.cancelled();
                cb.failed("voice failed");
                check(n, f.log.count("finished:a") == 1 && f.log.count("cancelled:a") == 0
                        && f.log.count("failed:a:voice failed") == 0 && f.context.unbinds == 1, f.log.toString());
            }
        });
        scenario("failed_callback_reaches_on_failed_with_its_reason", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.client.speak("One.", new Heard("a", f.log));
                f.connect();
                f.awaitSaid(1);
                // Over the wire: the launcher holds a Proxy, not the client's object.
                final IBinder line = f.service.callback(0).asBinder();
                IBinder remote = new IBinder() {
                    public IInterface queryLocalInterface(String descriptor) {
                        return null;
                    }

                    public boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                        return line.transact(code, data, reply, flags);
                    }
                };
                RobotSpeech.Callback proxy = RobotSpeech.Callback.Stub.asInterface(remote);
                proxy.failed("voice failed");
                check(n, f.log.count("failed:a:voice failed") == 1 && f.log.count("finished:a") == 0
                        && f.log.count("cancelled:a") == 0 && f.context.unbinds == 1, f.log.toString());
            }
        });
    }
}
