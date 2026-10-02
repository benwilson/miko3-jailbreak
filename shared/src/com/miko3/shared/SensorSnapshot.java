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
    /** True when the record carried both wheel counts (Left=/Right=). The counts are
     * signed (live 2026-09-25: reverse counts down, from 0 at power-up), so -1 is a
     * real count: check this, never ABSENT. */
    public final boolean hasWheels;
    /** The wheel encoder counts from the record's Left=/Right= fields; ABSENT when
     * !hasWheels. Signed and cumulative: forward counts up, reverse counts down, and
     * they stand still while a wheel is stalled. */
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
    /** True when the record carried a whole IMUAC= section (meeting plan U1, KTD5).
     * The fields are signed, so -1 is a real value there: check this, not ABSENT. */
    public final boolean hasAccel;
    /** The raw accelerometer values from IMUAC=, in the controller's counts; ABSENT
     * when !hasAccel. Which axis points where, and what a shove looks like, come from
     * the owner-run measurement (meeting plan U2), never from here. */
    public final int accelX;
    public final int accelY;
    public final int accelZ;
    /** The reply's POWER section (SensorReply.Power: dock state, signed current,
     * percentage), or null when it had none or it was malformed. Read whatever the ToF
     * says: on the owner's dock tof is 16383 (fault) while POWER says charging. */
    public final SensorReply.Power power;

    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2) {
        this(timestampMs, tof, ir1, ir2, ABSENT, ABSENT);
    }

    /** Wheel counts given as values (ABSENT for none): a count of -1 can't be carried this way. */
    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2, long wheelLeft, long wheelRight) {
        this(timestampMs, tof, ir1, ir2, wheelLeft != ABSENT && wheelRight != ABSENT, wheelLeft, wheelRight,
                false, ABSENT, ABSENT, ABSENT);
    }

    /** A reading that carried the gyro: its three raw rates (wheel counts as above). */
    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2, long wheelLeft, long wheelRight,
                          int gyroX, int gyroY, int gyroZ) {
        this(timestampMs, tof, ir1, ir2, wheelLeft != ABSENT && wheelRight != ABSENT, wheelLeft, wheelRight,
                true, gyroX, gyroY, gyroZ);
    }

    /** Every field but the accelerometer, with explicit presence for the signed wheel
     * counts and gyro rates. */
    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2, boolean hasWheels, long wheelLeft,
                          long wheelRight, boolean hasGyro, int gyroX, int gyroY, int gyroZ) {
        this(timestampMs, tof, ir1, ir2, hasWheels, wheelLeft, wheelRight, hasGyro, gyroX, gyroY, gyroZ,
                false, ABSENT, ABSENT, ABSENT);
    }

    /** Every field but POWER, with explicit presence for the signed wheel counts, gyro
     * rates and accelerometer values. */
    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2, boolean hasWheels, long wheelLeft,
                          long wheelRight, boolean hasGyro, int gyroX, int gyroY, int gyroZ,
                          boolean hasAccel, int accelX, int accelY, int accelZ) {
        this(timestampMs, tof, ir1, ir2, hasWheels, wheelLeft, wheelRight, hasGyro, gyroX, gyroY, gyroZ,
                hasAccel, accelX, accelY, accelZ, null);
    }

    /** Every field, POWER included (null for none). */
    public SensorSnapshot(long timestampMs, int tof, int ir1, int ir2, boolean hasWheels, long wheelLeft,
                          long wheelRight, boolean hasGyro, int gyroX, int gyroY, int gyroZ,
                          boolean hasAccel, int accelX, int accelY, int accelZ, SensorReply.Power power) {
        this.power = power;
        this.timestampMs = timestampMs;
        this.tof = tof;
        this.ir1 = ir1;
        this.ir2 = ir2;
        this.fault = tof == DEAD_TOF;
        this.hasWheels = hasWheels;
        this.wheelLeft = hasWheels ? wheelLeft : ABSENT;
        this.wheelRight = hasWheels ? wheelRight : ABSENT;
        this.hasGyro = hasGyro;
        this.gyroX = gyroX;
        this.gyroY = gyroY;
        this.gyroZ = gyroZ;
        this.hasAccel = hasAccel;
        this.accelX = hasAccel ? accelX : ABSENT;
        this.accelY = hasAccel ? accelY : ABSENT;
        this.accelZ = hasAccel ? accelZ : ABSENT;
    }

    @Override
    public String toString() {
        return "SensorSnapshot{t=" + timestampMs + " tof=" + tof + " ir1=" + ir1 + " ir2=" + ir2
                + (hasWheels ? " wheels=" + wheelLeft + "/" + wheelRight : "")
                + (hasGyro ? " gyro=" + gyroX + "," + gyroY + "," + gyroZ : "")
                + (hasAccel ? " accel=" + accelX + "," + accelY + "," + accelZ : "")
                + (power != null ? " " + power : "") + (fault ? " FAULT" : "") + "}";
    }
}
