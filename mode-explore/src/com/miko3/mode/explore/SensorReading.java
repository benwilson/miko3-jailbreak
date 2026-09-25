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
    /** Wheel encoder counts, or -1 when the reply carried none. They stand still while
     * the wheels are stalled, e.g. against something too low for the front sensor. */
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

    SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault) {
        this(timestampMs, tof, ir1, ir2, cpl, fault, -1, -1);
    }

    SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault,
                  long wheelLeft, long wheelRight) {
        this(timestampMs, tof, ir1, ir2, cpl, fault, wheelLeft, wheelRight, false, 0, 0, 0);
    }

    /** A reading that carried the gyro's three raw rates. */
    SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault,
                  long wheelLeft, long wheelRight, int gyroX, int gyroY, int gyroZ) {
        this(timestampMs, tof, ir1, ir2, cpl, fault, wheelLeft, wheelRight, true, gyroX, gyroY, gyroZ);
    }

    private SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault,
                          long wheelLeft, long wheelRight, boolean hasGyro, int gyroX, int gyroY, int gyroZ) {
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
        this.wheelLeft = wheelLeft;
        this.wheelRight = wheelRight;
    }

    boolean hasWheels() {
        return wheelLeft >= 0 && wheelRight >= 0;
    }

    @Override
    public String toString() {
        return "t=" + timestampMs + " tof=" + tof + " ir1=" + ir1 + " ir2=" + ir2
                + " cpl=" + cpl + (hasWheels() ? " wheels=" + wheelLeft + "/" + wheelRight : "")
                + (hasGyro ? " gyro=" + gyroX + "," + gyroY + "," + gyroZ : "")
                + (fault ? " FAULT" : "");
    }
}
