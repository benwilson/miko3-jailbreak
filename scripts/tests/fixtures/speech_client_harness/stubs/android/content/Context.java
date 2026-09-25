package android.content;

/** Host-JVM stand-in for android.content.Context: just what
 * RobotSpeechClient calls. The harness overrides bindService/unbindService. */
public class Context {
    public static final int BIND_AUTO_CREATE = 1;

    public Context getApplicationContext() {
        return this;
    }

    public boolean bindService(Intent service, ServiceConnection conn, int flags) {
        return false;
    }

    public void unbindService(ServiceConnection conn) {
    }
}
