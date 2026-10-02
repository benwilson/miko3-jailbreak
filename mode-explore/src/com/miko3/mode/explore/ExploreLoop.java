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

        /** Make a curiosity stop due now instead of after 20-40 s of wandering (camera curiosity U8). */
        boolean curiousNow();

        /** Turn in place one way, then the other, instead of wandering, for the gyro
         * capture (ExploreSpin; explore nav plan U1). */
        boolean spinInPlace();
    }

    /**
     * Whether the speaker is muted or turned all the way down with the volume keys
     * (owner 2026-10-02): the brain's do-not-disturb. Polled every pass on the brain
     * thread, so it must be cheap; ModeApp's caches the audio service's answer.
     */
    interface Mute {
        boolean muted();
    }

    static final Mute NEVER_MUTED = new Mute() {
        @Override
        public boolean muted() {
            return false;
        }
    };

    static final Hooks NO_HOOKS = new Hooks() {
        @Override
        public boolean staleSensors() {
            return false;
        }

        @Override
        public boolean freezeBrain() {
            return false;
        }

        @Override
        public boolean curiousNow() {
            return false;
        }

        @Override
        public boolean spinInPlace() {
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
    private final ExploreSpin spin;
    private final StopTimer stopTimer;

    private volatile boolean running;
    private volatile Mute mute = NEVER_MUTED;
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
        this(tuning, clock, wheels, sensors, lease, eyes, sound, camera, CuriosityPort.NONE, hooks, trace,
                tickMs, stopTimerMs);
    }

    /** With Claude at curiosity stops (explore on Claude U6): port is ModeApp's ClaudeCuriosity. */
    ExploreLoop(ExploreTuning tuning, ExploreBrain.Clock clock, Wheels wheels, Sensors sensors, Lease lease,
                ExploreBrain.Eyes eyes, ExploreBrain.Sound sound, ExploreBrain.Camera camera, CuriosityPort port,
                Hooks hooks, ExploreBrain.Trace trace, long tickMs, long stopTimerMs) {
        this(tuning, clock, wheels, sensors, lease, eyes, sound, camera, port, Ears.NONE, hooks, trace, tickMs,
                stopTimerMs);
    }

    /** With the launcher's ears as step input (meeting plan U7, KTD1): cues, the angle trend and shoves. */
    ExploreLoop(ExploreTuning tuning, ExploreBrain.Clock clock, Wheels wheels, Sensors sensors, Lease lease,
                ExploreBrain.Eyes eyes, ExploreBrain.Sound sound, ExploreBrain.Camera camera, CuriosityPort port,
                Ears ears, Hooks hooks, ExploreBrain.Trace trace, long tickMs, long stopTimerMs) {
        this.clock = clock;
        this.wheels = wheels;
        this.sensors = sensors;
        this.lease = lease;
        this.hooks = hooks == null ? NO_HOOKS : hooks;
        this.trace = trace;
        this.tickMs = tickMs;
        DriveGate gate = new DriveGate(wheels, lease, trace);
        this.brain = new ExploreBrain(tuning, clock, gate, eyes, sound, camera, port, ears, new Random());
        this.spin = new ExploreSpin(gate, trace, tuning);
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

    /** Where the speaker's mute comes from (null: never muted); set before start(). */
    void setMute(Mute mute) {
        this.mute = mute == null ? NEVER_MUTED : mute;
    }

    /** Where the brain's cue counters and stage stamps go (meeting plan U7, KTD14): the state page. */
    void setGauges(ExploreBrain.Gauges gauges) {
        brain.setGauges(gauges);
    }

    private void runBrain() {
        brain.start();
        boolean leaseReported = false;
        boolean mutedReported = false;
        long lastReadingMs = Long.MIN_VALUE;
        while (running) {
            if (!hooks.freezeBrain() && hooks.spinInPlace()) {
                // The brain sits out the spin (no readings, no ticks), so nothing it
                // decides competes for the wheels; it catches up when the hook goes off.
                spin.onTick(clock.nowMs(), hooks.staleSensors() ? null : sensors.latest(), lease.held());
                stopTimer.feed(clock.nowMs());
            } else if (!hooks.freezeBrain()) {
                if (spin.active()) {
                    spin.end();
                }
                boolean held = lease.held();
                if (held != leaseReported) {
                    leaseReported = held;
                    brain.onLeaseChanged(held);
                }
                boolean muted = mute.muted();
                if (muted != mutedReported) {
                    mutedReported = muted;
                    brain.setMuted(muted);
                }
                SensorReading reading = hooks.staleSensors() ? null : sensors.latest();
                if (reading != null && reading.timestampMs > lastReadingMs) {
                    lastReadingMs = reading.timestampMs;
                    brain.onReading(reading);
                }
                if (hooks.curiousNow()) {
                    brain.requestCuriosity();
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
