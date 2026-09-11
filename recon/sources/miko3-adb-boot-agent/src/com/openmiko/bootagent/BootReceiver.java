package com.openmiko.bootagent;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fires on BOOT_COMPLETED (and OEM QUICKBOOT variants). Runs the root payload on a background thread and uses
 * goAsync() so the ~10s broadcast window isn't a constraint. This is the whole "run at boot" mechanism — the same
 * one launchers/utilities use — with no Termux and no Nova.
 */
public class BootReceiver extends BroadcastReceiver {

    private static final String TAG = "OpenMikoBoot";

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
                    Log.e(TAG, "boot payload threw", e); // surfaced in RootOps + the on-device log; do not crash the boot
                } finally {
                    pr.finish();
                }
            }
        }).start();
    }
}
