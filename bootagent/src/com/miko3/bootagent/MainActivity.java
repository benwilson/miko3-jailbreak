package com.miko3.bootagent;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

/**
 * A minimal launcher activity. Its only jobs: let the app be started once so Android clears the
 * post-install "stopped" state and arms {@link BootReceiver}, and run the payload immediately on
 * that first launch. It has no ongoing role.
 */
public class MainActivity extends Activity {

    private static final String TAG = "Miko3Boot";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView tv = new TextView(this);
        tv.setText("Miko3 Boot Agent is installed.\n\n"
                + "Root adb over USB and Wi-Fi (tcp 5555) now comes up automatically at every boot,\n"
                + "with the anti-tamper watchdog defused first.\n\n"
                + "Log: /data/local/tmp/miko3-boot.log");
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
