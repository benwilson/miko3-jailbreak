package android.content;

import android.os.IBinder;

/** Host-JVM stand-in for android.content.ServiceConnection. */
public interface ServiceConnection {
    void onServiceConnected(ComponentName name, IBinder service);

    void onServiceDisconnected(ComponentName name);
}
