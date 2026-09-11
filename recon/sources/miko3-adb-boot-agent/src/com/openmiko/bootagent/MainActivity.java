package com.openmiko.bootagent;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/**
 * A minimal launcher activity. Its only jobs: (1) let the app be started once during setup (pm install + am start
 * over the first adb session) so Android clears the post-install "stopped" state and arms {@link BootReceiver};
 * (2) run the payload immediately on that first launch so adb is up right away. It has no ongoing role.
 */
public class MainActivity extends Activity {

    private static final String TAG = "OpenMikoBoot";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("OpenMiko Boot Agent is installed.\n\n"
                + "adb-over-wifi (tcp 5555) now comes up automatically at every boot,\n"
                + "with the anti-tamper watchdog defused first. No Termux, no Nova.\n\n"
                + "Log: /data/local/tmp/openmiko-boot.log");
        tv.setPadding(40, 80, 40, 40);
        setContentView(tv);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RootOps.runBootPayload();
                } catch (RuntimeException e) {
                    Log.e(TAG, "first-launch payload threw", e);
                }
            }
        }).start();
    }
}
