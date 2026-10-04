package com.miko3.shared;

import android.app.Activity;
import android.content.Context;
import android.media.AudioManager;
import android.view.KeyEvent;

/**
 * The volume keys on top of the robot, for every activity that can be in front
 * (owner 2026-10-02: "turn them down or mute them if I'm having a conversation
 * with someone else"). The robot's speech (the launcher's speech service) and
 * Explore's clips both play on STREAM_MUSIC, so that is the stream the keys move.
 *
 * <pre>
 *   protected void onCreate(Bundle b) { ...; VolumeKeys.attach(this); }
 *   public boolean dispatchKeyEvent(KeyEvent e) {
 *       return VolumeKeys.dispatch(this, e) || super.dispatchKeyEvent(e);
 *   }
 * </pre>
 *
 * dispatchKeyEvent runs before the full-screen WebView sees the key, which on the
 * robot swallowed volume presses (no stream moved with Explore in front).
 *
 * Volume up and down step STREAM_MUSIC one notch, and keep stepping while held;
 * raising a muted stream also unmutes it (AudioService). Either mute keycode
 * toggles its mute, once per press. The device's key layout maps Linux key 113 to
 * MUTE (KEYCODE_MUTE); the generic layout maps it to VOLUME_MUTE, so both count.
 * No system volume panel is shown.
 */
public final class VolumeKeys {
    /** adjustmentFor()'s answer for a key that is not one of ours. */
    public static final int NOT_A_VOLUME_KEY = Integer.MIN_VALUE;

    private VolumeKeys() {
    }

    /** Makes the hardware keys' default stream STREAM_MUSIC; call from onCreate. */
    public static void attach(Activity activity) {
        activity.setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    /** The STREAM_MUSIC adjustment a key asks for, or NOT_A_VOLUME_KEY. */
    public static int adjustmentFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_UP:
                return AudioManager.ADJUST_RAISE;
            case KeyEvent.KEYCODE_VOLUME_DOWN:
                return AudioManager.ADJUST_LOWER;
            case KeyEvent.KEYCODE_VOLUME_MUTE:
            case KeyEvent.KEYCODE_MUTE:
                return AudioManager.ADJUST_TOGGLE_MUTE;
            default:
                return NOT_A_VOLUME_KEY;
        }
    }

    /**
     * Handles a volume or mute key and returns true (both its down and up are
     * consumed); returns false for every other key, for the caller's super.
     */
    public static boolean dispatch(Context context, KeyEvent event) {
        int adjustment = adjustmentFor(event.getKeyCode());
        if (adjustment == NOT_A_VOLUME_KEY) {
            return false;
        }
        boolean act = event.getAction() == KeyEvent.ACTION_DOWN
                && (adjustment != AudioManager.ADJUST_TOGGLE_MUTE || event.getRepeatCount() == 0);
        if (act) {
            adjust(context, adjustment);
        }
        return true;
    }

    /**
     * Toggles STREAM_MUSIC's mute, exactly as a mute keycode does. For the physical
     * mute button, which is a switch on its own input device that no app sees: the
     * boot agent's root watcher relays each press to the launcher's MuteKeyReceiver.
     */
    public static void toggleMute(Context context) {
        adjust(context, AudioManager.ADJUST_TOGGLE_MUTE);
    }

    private static void adjust(Context context, int adjustment) {
        Object service = context.getSystemService(Context.AUDIO_SERVICE);
        if (service instanceof AudioManager) {
            ((AudioManager) service).adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, 0);
        }
    }

    /**
     * Whether the robot cannot be heard: STREAM_MUSIC muted, or turned all the way
     * down. Explore treats this as do-not-disturb. False with no audio service.
     */
    public static boolean silenced(AudioManager audio) {
        return audio != null && (audio.isStreamMute(AudioManager.STREAM_MUSIC)
                || audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0);
    }
}
