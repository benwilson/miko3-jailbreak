package android.os;

/** Host stub: the driver stamps readings with this. */
public final class SystemClock {
    private SystemClock() {
    }

    public static long elapsedRealtime() {
        return System.nanoTime() / 1000000L;
    }
}
