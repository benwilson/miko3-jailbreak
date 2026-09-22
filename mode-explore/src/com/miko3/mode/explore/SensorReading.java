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
    /** The reply was ERROR_UART, or the keepalive is dead: nothing here can be trusted. */
    final boolean fault;

    SensorReading(long timestampMs, int tof, int ir1, int ir2, Integer cpl, boolean fault) {
        this.timestampMs = timestampMs;
        this.tof = tof;
        this.ir1 = ir1;
        this.ir2 = ir2;
        this.cpl = cpl;
        this.fault = fault;
    }

    @Override
    public String toString() {
        return "t=" + timestampMs + " tof=" + tof + " ir1=" + ir1 + " ir2=" + ir2
                + " cpl=" + cpl + (fault ? " FAULT" : "");
    }
}
