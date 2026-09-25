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
    /** True when the record carried a whole IMUGY= section (explore nav plan U1, KTD1).
     * The gyro fields are signed rates, so -1 is a real value there: check this, not ABSENT. */
    public final boolean hasGyro;
    /** The raw gyroscope rates from IMUGY=, in the controller's counts; ABSENT when
     * !hasGyro. Which axis is yaw, its sign and its scale come from the owner-guided
     * capture (scripts/qa-explore-sensors.py --gyro-circle), never from here. */
    public final int gyroX;
    public final int gyroY;
    public final int gyroZ;

    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2) {
        this(timestampMs, tof, ir1, ir2, ABSENT, ABSENT);
    }

    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2, long wheelLeft, long wheelRight) {
        this(timestampMs, tof, ir1, ir2, wheelLeft, wheelRight, false, ABSENT, ABSENT, ABSENT);
    }

    /** A reading that carried the gyro: its three raw rates. */
    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2, long wheelLeft, long wheelRight,
                          int gyroX, int gyroY, int gyroZ) {
        this(timestampMs, tof, ir1, ir2, wheelLeft, wheelRight, true, gyroX, gyroY, gyroZ);
    }

    private SensorSnapshot(long timestampMs, int tof, int ir1, int ir2, long wheelLeft, long wheelRight,
                           boolean hasGyro, int gyroX, int gyroY, int gyroZ) {
        this.timestampMs = timestampMs;
        this.tof = tof;
        this.ir1 = ir1;
        this.ir2 = ir2;
        this.fault = tof == DEAD_TOF;
        this.wheelLeft = wheelLeft;
        this.wheelRight = wheelRight;
        this.hasGyro = hasGyro;
        this.gyroX = gyroX;
        this.gyroY = gyroY;
        this.gyroZ = gyroZ;
    }

    @Override
    public String toString() {
        return "SensorSnapshot{t=" + timestampMs + " tof=" + tof + " ir1=" + ir1 + " ir2=" + ir2
                + " wheels=" + wheelLeft + "/" + wheelRight
                + (hasGyro ? " gyro=" + gyroX + "," + gyroY + "," + gyroZ : "") + (fault ? " FAULT" : "") + "}";
    }
}
