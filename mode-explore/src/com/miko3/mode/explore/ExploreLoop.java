package com.miko3.mode.explore;

import java.io.IOException;
import java.util.Random;

/**
 * Runs the wander: one thread drives the brain, which ExploreBrain requires (all
 * its entry points on a single thread), and a second, independent thread holds
 * the stop timer (KTD6), so a stuck brain thread still ends in a stop.
 *
 * Each brain pass: report a lease change, hand over the newest reading if it is
 * new, tick, then feed the stop timer. Readings are polled from the driver's
 * published snapshot rather than pushed, so the driver's keepalive thread never
 * calls into the brain.
 *
 * Plain Java: ExploreDrive supplies the device side (wheels, sensors, lease) and
 * the harness fakes it (scripts/tests/test_explore_drive.py).
 */
final class ExploreLoop {
    /** The motors, as the driver exposes them. turn() self-sustains until stop(). */
    interface Wheels {
        void forwardTick() throws IOException;

        void turn(ExploreBrain.Direction direction) throws IOException;

        void backTick() throws IOException;

        void stop() throws IOException;
    }

    /** The newest reading, or null before the first; the loop skips repeats by timestamp. */
    interface Sensors {
        SensorReading latest();
    }

    interface Lease {
        boolean held();
    }

    /** Debug hooks for staging the acceptance checks on the robot (U8). */
    interface Hooks {
        /** Hide every reading, as if the sensor went quiet (AE3/AE4). */
        boolean staleSensors();

        /** Stop ticking the brain, as if its thread hung, to prove the stop timer. */
        boolean freezeBrain();
    }

    static final Hooks NO_HOOKS = new Hooks() {
        @Override
        public boolean staleSensors() {
            return false;
        }

        @Override
        public boolean freezeBrain() {
            return false;
        }
    };

    private final ExploreBrain.Clock clock;
    private final Wheels wheels;
    private final Sensors sensors;
    private final Lease lease;
    private final Hooks hooks;
    private final ExploreBrain.Trace trace;
    private final long tickMs;
    private final ExploreBrain brain;
    private final StopTimer stopTimer;

    private volatile boolean running;
    private Thread brainThread;
    private Thread stopTimerThread;

    ExploreLoop(ExploreTuning tuning, ExploreBrain.Clock clock, Wheels wheels, Sensors sensors, Lease lease,
                ExploreBrain.Eyes eyes, ExploreBrain.Sound sound, Hooks hooks, ExploreBrain.Trace trace,
                long tickMs, long stopTimerMs) {
        this(tuning, clock, wheels, sensors, lease, eyes, sound, ExploreBrain.NO_CAMERA, hooks, trace,
                tickMs, stopTimerMs);
    }

    ExploreLoop(ExploreTuning tuning, ExploreBrain.Clock clock, Wheels wheels, Sensors sensors, Lease lease,
                ExploreBrain.Eyes eyes, ExploreBrain.Sound sound, ExploreBrain.Camera camera, Hooks hooks,
                ExploreBrain.Trace trace, long tickMs, long stopTimerMs) {
        this.clock = clock;
        this.wheels = wheels;
        this.sensors = sensors;
        this.lease = lease;
        this.hooks = hooks == null ? NO_HOOKS : hooks;
        this.trace = trace;
        this.tickMs = tickMs;
        this.brain = new ExploreBrain(tuning, clock, new DriveGate(wheels, lease, trace), eyes, sound, camera,
                new Random());
        if (trace != null) {
            brain.setTrace(trace);
        }
        this.stopTimer = new StopTimer(stopTimerMs);
    }

    synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        brainThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runBrain();
            }
        }, "explore-brain");
        stopTimerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runStopTimer();
            }
        }, "explore-stop-timer");
        brainThread.start();
        stopTimerThread.start();
    }

    /**
     * Exit (R5): end the brain thread and wait for it, then shut the brain down on
     * this thread (which sends stop()), then end the stop timer. When this returns
     * the wheels are stopped and nothing here sends another command, so the
     * caller can release the lease and disconnect.
     */
    void stop() {
        Thread b;
        Thread s;
        synchronized (this) {
            if (!running) {
                return;
            }
            running = false;
            b = brainThread;
            s = stopTimerThread;
        }
        join(b);
        brain.shutdown();
        join(s);
    }

    boolean isRunning() {
        return running;
    }

    private void runBrain() {
        brain.start();
        boolean leaseReported = false;
        long lastReadingMs = Long.MIN_VALUE;
        while (running) {
            if (!hooks.freezeBrain()) {
                boolean held = lease.held();
                if (held != leaseReported) {
                    leaseReported = held;
                    brain.onLeaseChanged(held);
                }
                SensorReading reading = hooks.staleSensors() ? null : sensors.latest();
                if (reading != null && reading.timestampMs > lastReadingMs) {
                    lastReadingMs = reading.timestampMs;
                    brain.onReading(reading);
                }
                brain.onTick();
                stopTimer.feed(clock.nowMs());
            }
            sleep(tickMs);
        }
    }

    private void runStopTimer() {
        long checkMs = Math.max(20, tickMs * 2);
        while (running) {
            if (stopTimer.expired(clock.nowMs())) {
                if (trace != null) {
                    trace.note("stop timer fired: the brain stopped ticking -- stopping the wheels");
                }
                try {
                    wheels.stop();
                } catch (IOException e) {
                    if (trace != null) {
                        trace.note("stop-timer stop failed, retrying: " + e.getMessage());
                    }
                    stopTimer.rearm();
                }
            }
            sleep(checkMs);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void join(Thread t) {
        if (t == null) {
            return;
        }
        try {
            t.join(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
