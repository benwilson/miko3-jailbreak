package com.miko3.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Debug-only: scripts/robot-say.py's "heard text" (2026-10-02). Takes one utterance
 * per broadcast and hands it to the ears session's EarsInject, which plays it through
 * the real wake-word, gate and recogniser path as if someone had said it.
 *
 * Inert unless the debug property EarsInject.PROPERTY reads "1" (unset by default;
 * the script sets it and clears it on exit): with it off, the words are never read.
 * Not exported and with no intent filter, like MuteKeyReceiver: only root (the
 * script's `adb shell am broadcast`, adbd runs as root) and the system can deliver
 * it. Logs whether it was taken, never the words.
 */
public class EarsInjectReceiver extends BroadcastReceiver {
    private static final String TAG = "EarsInject";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!EarsInject.ACTION.equals(intent.getAction())) {
            return;
        }
        Context app = context.getApplicationContext();
        ListenEngine listen = app instanceof LauncherApp ? ((LauncherApp) app).listen() : null;
        if (listen == null) {
            Log.w(TAG, "ignored: the ears are not running");
            return;
        }
        EarsInject inject = listen.inject();
        if (!inject.armed()) {
            Log.w(TAG, "ignored: " + EarsInject.PROPERTY + " is not 1");
            return;
        }
        boolean wake = intent.getBooleanExtra(EarsInject.EXTRA_WAKE, false);
        boolean taken = inject.offer(intent.getStringExtra(EarsInject.EXTRA_TEXT), wake);
        Log.i(TAG, (taken ? "queued " : "refused ") + (wake ? "a call" : "an utterance"));
    }
}
