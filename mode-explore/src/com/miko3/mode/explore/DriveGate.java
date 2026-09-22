package com.miko3.mode.explore;

import java.io.IOException;

/**
 * The brain's Motor, gated on the drive lease: motion commands go out only while
 * the lease is held, and are dropped otherwise (the loop tells the brain the
 * lease is gone on its next pass). stop() always goes out. A failed write stops
 * the wheels best-effort rather than leaving the last command running.
 *
 * Plain Java: the wheels and lease are interfaces ExploreDrive implements on the
 * device and the harness fakes on the host.
 */
final class DriveGate implements ExploreBrain.Motor {
    private final ExploreLoop.Wheels wheels;
    private final ExploreLoop.Lease lease;
    private final ExploreBrain.Trace trace;

    DriveGate(ExploreLoop.Wheels wheels, ExploreLoop.Lease lease, ExploreBrain.Trace trace) {
        this.wheels = wheels;
        this.lease = lease;
        this.trace = trace;
    }

    @Override
    public void hopTick() {
        if (lease.held()) {
            try {
                wheels.forwardTick();
            } catch (IOException e) {
                failed("forward", e);
            }
        }
    }

    @Override
    public void turn(ExploreBrain.Direction direction) {
        if (lease.held()) {
            try {
                wheels.turn(direction);
            } catch (IOException e) {
                failed("turn", e);
            }
        }
    }

    @Override
    public void backTick() {
        if (lease.held()) {
            try {
                wheels.backTick();
            } catch (IOException e) {
                failed("back", e);
            }
        }
    }

    @Override
    public void stop() {
        try {
            wheels.stop();
        } catch (IOException e) {
            note("stop failed: " + e.getMessage());
        }
    }

    private void failed(String what, IOException e) {
        note(what + " failed (" + e.getMessage() + ") -- stopping");
        stop();
    }

    private void note(String message) {
        if (trace != null) {
            trace.note(message);
        }
    }
}
