package com.miko3.launcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.miko3.shared.VolumeKeys;

/**
 * The physical mute button on top of the robot (owner 2026-10-02). It is a switch on
 * its own input device ("mutekey", /dev/input/event2) with no key layout, so Android
 * drops it and no activity ever sees a key. The boot agent's root watcher
 * (bootagent/native/miko3-mute-watch.sh) reads the switch and sends one explicit
 * broadcast here per press; this toggles STREAM_MUSIC's mute, exactly as a mute
 * keycode does, which Explore hears as do-not-disturb.
 *
 * Not exported and with no intent filter: only root (the watcher's {@code am}) and the
 * system can deliver to it, so no other app can mute the robot.
 */
public class MuteKeyReceiver extends BroadcastReceiver {
    static final String ACTION = "com.miko3.launcher.action.MUTE_KEY";
    private static final String TAG = "MuteKeyReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION.equals(intent.getAction())) {
            return;
        }
        Log.i(TAG, "mute button (" + intent.getStringExtra("why") + "): toggling the speaker's mute");
        VolumeKeys.toggleMute(context);
    }
}
