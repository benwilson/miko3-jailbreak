package com.miko3.shared;

/**
 * One reading of the front ToF/edge sensor, parsed from a single MCU reply to
 * the POWER poll (see SensorReply and docs/hardware/tof-sensor.md). Immutable;
 * DirectMotorDriver publishes the latest one for a mode to read.
 *
 * Plain Java (no android.*) so the parser can be tested on the host JVM.
 */
public final class SensorSnapshot {
    /** A field the record did not carry (all-'X' padding, or missing). */
    public static final int ABSENT = -1;
    /** The value the ToF reads when the sensor is dead or disconnected (bent-pin fault). */
    public static final int DEAD_TOF = 16383;

    /** When the reply was read, in the clock the caller passed (elapsedRealtime on device). */
    public final long timestampMs;
    public final int tof;
    public final int ir1;
    public final int ir2;
    /** True when tof is DEAD_TOF: the sensor is reporting, but nothing it says is usable. */
    public final boolean fault;
    /** The wheel encoder counts from the record's Left=/Right= fields, or ABSENT. They
     * climb while a wheel turns and stand still while it is stalled. */
    public final long wheelLeft;
    public final long wheelRight;

    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2) {
        this(timestampMs, tof, ir1, ir2, ABSENT, ABSENT);
    }

    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2, long wheelLeft, long wheelRight) {
        this.timestampMs = timestampMs;
        this.tof = tof;
        this.ir1 = ir1;
        this.ir2 = ir2;
        this.fault = tof == DEAD_TOF;
        this.wheelLeft = wheelLeft;
        this.wheelRight = wheelRight;
    }

    @Override
    public String toString() {
        return "SensorSnapshot{t=" + timestampMs + " tof=" + tof + " ir1=" + ir1 + " ir2=" + ir2
                + " wheels=" + wheelLeft + "/" + wheelRight + (fault ? " FAULT" : "") + "}";
    }
}
