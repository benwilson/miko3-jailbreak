package android.util;

/** Host-JVM stand-in for android.util.Log so the shared module's classes run
 * outside Android in the WebSocket conformance harness. Writes to stderr. */
public final class Log {
    private Log() {
    }

    public static int v(String tag, String msg) {
        return print("V", tag, msg, null);
    }

    public static int d(String tag, String msg) {
        return print("D", tag, msg, null);
    }

    public static int i(String tag, String msg) {
        return print("I", tag, msg, null);
    }

    public static int w(String tag, String msg) {
        return print("W", tag, msg, null);
    }

    public static int w(String tag, String msg, Throwable tr) {
        return print("W", tag, msg, tr);
    }

    public static int e(String tag, String msg) {
        return print("E", tag, msg, null);
    }

    public static int e(String tag, String msg, Throwable tr) {
        return print("E", tag, msg, tr);
    }

    private static int print(String level, String tag, String msg, Throwable tr) {
        System.err.println(level + "/" + tag + ": " + msg + (tr != null ? " (" + tr + ")" : ""));
        return 0;
    }
}
