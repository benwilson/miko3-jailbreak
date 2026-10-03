package android.util;

/** Host stub: silent, and every tag is off. */
public final class Log {
    public static final int DEBUG = 3;

    private Log() {
    }

    public static boolean isLoggable(String tag, int level) {
        return false;
    }

    public static int d(String tag, String msg) {
        return 0;
    }

    public static int i(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg) {
        return 0;
    }

    public static int w(String tag, String msg, Throwable t) {
        return 0;
    }

    public static int e(String tag, String msg) {
        return 0;
    }

    public static int e(String tag, String msg, Throwable t) {
        return 0;
    }
}
