package com.miko3.bootagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fires on BOOT_COMPLETED (and the OEM QUICKBOOT variants). Runs the root payload on a
 * background thread and uses goAsync() so the short broadcast window is not a constraint.
 * This is the whole "run at boot" mechanism, with no Termux and no launcher dependency.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "Miko3Boot";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "BOOT_COMPLETED received: " + (intent != null ? intent.getAction() : "null"));
        final PendingResult pr = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    RootOps.runBootPayload();
                } catch (RuntimeException e) {
                    // Surface the failure in logcat; never crash the boot.
                    Log.e(TAG, "boot payload threw", e);
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }
}
