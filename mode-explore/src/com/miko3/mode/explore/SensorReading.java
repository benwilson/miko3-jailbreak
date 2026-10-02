package com.miko3.mode.explore;

/**
 * One sensor reading as the wander brain sees it (U3): the fields parsed from a
 * single keepalive reply (KTD1), stamped with the time it arrived. Immutable.
 *
 * Deliberately the brain's own type rather than the shared driver's snapshot,
 * so HazardClassifier and ExploreBrain compile on the host JVM with nothing but
 * this package (scripts/tests/fixtures/explore_brain_harness). ExploreLoop (U5)
 * copies each driver snapshot into one of these.
 *
 * Units of tof, ir1 and ir2 are the controller's raw counts; what they mean on
 * this firmware is recorded in docs/hardware/tof-sensor.md (U1), and the
 * thresholds that turn them into "edge" or "obstacle" come from the on-device
 * calibration (ExploreTuning.Calibration, KTD9).
 */
final class SensorReading {
    /** When the reply carrying these values arrived, on the brain's clock. */
    final long timestampMs;
    /** Front time-of-flight value; 16383 is the sensor's fault value (KTD3). */
    final int tof;
    final int ir1;
    final int ir2;
    /** The motion-ack field, or null when the reply did not carry one. 2 = forward refused. */
    final Integer cpl;
    /** Nothing in this reading can be trusted. The device side currently never sets it
     * (a dead keepalive shows up as staleness instead); it stays for a future producer. */
    final boolean fault;
    /** True when the reply carried both wheel counts. The counters are signed and
     * cumulative (live 2026-09-25: forward counts up, reverse counts down, from 0 at
     * power-up), so a negative count is real: check this, never the counts' sign. */
    private final boolean wheels;
    /** Wheel encoder counts (0 when !hasWheels()). They stand still while the wheels
     * are stalled, e.g. against something too low for the front sensor; use their
     * differences, with Math.abs where direction doesn't matter. */
    final long wheelLeft;
    final long wheelRight;
    /** True when the reply carried the gyro (explore nav plan U1, KTD1). The rates are
     * signed, so -1 is a real value: check this rather than the fields. */
    final boolean hasGyro;
    /** Raw gyroscope rates, in the controller's counts; which one is yaw, its sign and its
     * scale are in the calibration file (ExploreCalibration.Gyro). 0 when !hasGyro. */
    final int gyroX;
    final int gyroY;
    final int gyroZ;
    /** The charger latch (meeting plan U1, KTD6): true from the last motion
     * acknowledgement carrying CPL=3 until a later one carrying another value. The
     * controller refuses every motion while docked, so the classifier reads it as
     * motion refused; unlike cpl it is not the reply's own field but the driver's latch. */
    final boolean charger;
    /** True when the reply carried the accelerometer (meeting plan U1, KTD5). The
     * values are signed, so -1 is real: check this rather than the fields. */
    final boolean hasAccel;
    /** Raw accelerometer values, in the controller's counts; 0 when !hasAccel. */
    final int accelX;
    final int accelY;
    final int accelZ;
    /** The dock as the reply's POWER section says it (2026-10-02, SensorReply.Power.docked()):
     * TRUE on the dock, FALSE off it, null when the reply had no readable POWER. Unlike
     * the charger latch it needs no motion command, and it is read whatever the ToF says:
     * on the owner's dock tof is at its fault value while POWER says charging. The brain
     * debounces leaving the dock (ExploreTuning.dockOffReadings). */
    final Boolean docked;

    /** No wheel counts and no gyro. */
    SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault) {
        this(timestampMs, tof, ir1, ir2, cpl, fault, false, 0, 0, false, 0, 0, 0);
    }

    /** With both wheel counts (any sign). */
    SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault,
                  long wheelLeft, long wheelRight) {
        this(timestampMs, tof, ir1, ir2, cpl, fault, true, wheelLeft, wheelRight, false, 0, 0, 0);
    }

    /** With both wheel counts and the gyro's three raw rates. */
    SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault,
                  long wheelLeft, long wheelRight, int gyroX, int gyroY, int gyroZ) {
        this(timestampMs, tof, ir1, ir2, cpl, fault, true, wheelLeft, wheelRight, true, gyroX, gyroY, gyroZ);
    }

    /** Every field but the charger latch and the accelerometer, with the wheels' and
     * the gyro's presence explicit. */
    SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault, boolean hasWheels,
                  long wheelLeft, long wheelRight, boolean hasGyro, int gyroX, int gyroY, int gyroZ) {
        this(timestampMs, tof, ir1, ir2, cpl, fault, hasWheels, wheelLeft, wheelRight, hasGyro, gyroX, gyroY,
                gyroZ, false, false, 0, 0, 0);
    }

    /** Every field but POWER's dock verdict, with the wheels', the gyro's and the accelerometer's presence explicit. */
    SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault, boolean hasWheels,
                  long wheelLeft, long wheelRight, boolean hasGyro, int gyroX, int gyroY, int gyroZ,
                  boolean charger, boolean hasAccel, int accelX, int accelY, int accelZ) {
        this(timestampMs, tof, ir1, ir2, cpl, fault, hasWheels, wheelLeft, wheelRight, hasGyro, gyroX, gyroY, gyroZ,
                charger, hasAccel, accelX, accelY, accelZ, null);
    }

    /** Every field, POWER's dock verdict included (null for none). */
    SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault, boolean hasWheels,
                  long wheelLeft, long wheelRight, boolean hasGyro, int gyroX, int gyroY, int gyroZ,
                  boolean charger, boolean hasAccel, int accelX, int accelY, int accelZ, Boolean docked) {
        this.docked = docked;
        this.charger = charger;
        this.hasAccel = hasAccel;
        this.accelX = hasAccel ? accelX : 0;
        this.accelY = hasAccel ? accelY : 0;
        this.accelZ = hasAccel ? accelZ : 0;
        this.hasGyro = hasGyro;
        this.gyroX = gyroX;
        this.gyroY = gyroY;
        this.gyroZ = gyroZ;
        this.timestampMs = timestampMs;
        this.tof = tof;
        this.ir1 = ir1;
        this.ir2 = ir2;
        this.cpl = cpl;
        this.fault = fault;
        this.wheels = hasWheels;
        this.wheelLeft = hasWheels ? wheelLeft : 0;
        this.wheelRight = hasWheels ? wheelRight : 0;
    }

    boolean hasWheels() {
        return wheels;
    }

    @Override
    public String toString() {
        return "t=" + timestampMs + " tof=" + tof + " ir1=" + ir1 + " ir2=" + ir2
                + " cpl=" + cpl + (hasWheels() ? " wheels=" + wheelLeft + "/" + wheelRight : "")
                + (hasGyro ? " gyro=" + gyroX + "," + gyroY + "," + gyroZ : "")
                + (hasAccel ? " accel=" + accelX + "," + accelY + "," + accelZ : "")
                + (charger ? " CHARGER" : "") + (docked == null ? "" : docked ? " DOCKED" : " UNDOCKED") + (fault ? " FAULT" : "");
    }
}
