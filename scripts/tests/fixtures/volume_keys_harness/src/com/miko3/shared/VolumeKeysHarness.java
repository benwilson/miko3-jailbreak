package com.miko3.shared;

import android.app.Activity;
import android.media.AudioManager;
import android.view.KeyEvent;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Host harness for VolumeKeys: one PASS/FAIL line per scenario. */
public final class VolumeKeysHarness {
    private static int failures;

    private interface Scenario {
        void run(String name) throws Exception;
    }

    private static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": threw " + t);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    private static Activity activity(AudioManager audio) {
        Activity a = new Activity();
        a.audio = audio;
        return a;
    }

    /** Presses one key (down, then up) and returns the calls made, after checking both were consumed. */
    private static List<String> press(int keyCode, boolean[] consumed) {
        AudioManager audio = new AudioManager();
        Activity a = activity(audio);
        consumed[0] = VolumeKeys.dispatch(a, new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        consumed[1] = VolumeKeys.dispatch(a, new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        return audio.calls;
    }

    private static void pressScenario(String name, int keyCode, String expected) {
        scenario(name, n -> {
            boolean[] consumed = new boolean[2];
            List<String> calls = press(keyCode, consumed);
            check(n, consumed[0] && consumed[1] && calls.equals(Collections.singletonList(expected)),
                    "consumed=" + Arrays.toString(consumed) + " calls=" + calls);
        });
    }

    public static void main(String[] args) {
        pressScenario("volume_up_raises_music_without_ui", KeyEvent.KEYCODE_VOLUME_UP, "3,1,0");
        pressScenario("volume_down_lowers_music_without_ui", KeyEvent.KEYCODE_VOLUME_DOWN, "3,-1,0");
        pressScenario("mute_toggles_music_mute", KeyEvent.KEYCODE_MUTE, "3,101,0");
        pressScenario("volume_mute_toggles_music_mute", KeyEvent.KEYCODE_VOLUME_MUTE, "3,101,0");
        scenario("held_volume_key_keeps_stepping", n -> {
            AudioManager audio = new AudioManager();
            Activity a = activity(audio);
            VolumeKeys.dispatch(a, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 0));
            VolumeKeys.dispatch(a, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 1));
            VolumeKeys.dispatch(a, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN, 2));
            check(n, audio.calls.equals(Arrays.asList("3,-1,0", "3,-1,0", "3,-1,0")), "calls=" + audio.calls);
        });
        scenario("held_mute_key_toggles_once", n -> {
            AudioManager audio = new AudioManager();
            Activity a = activity(audio);
            boolean first = VolumeKeys.dispatch(a, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MUTE, 0));
            boolean repeat = VolumeKeys.dispatch(a, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MUTE, 1));
            check(n, first && repeat && audio.calls.equals(Collections.singletonList("3,101,0")),
                    "calls=" + audio.calls);
        });
        scenario("other_keys_pass_through", n -> {
            boolean[] back = new boolean[2];
            boolean[] power = new boolean[2];
            List<String> backCalls = press(KeyEvent.KEYCODE_BACK, back);
            List<String> powerCalls = press(KeyEvent.KEYCODE_POWER, power);
            check(n, !back[0] && !back[1] && !power[0] && !power[1] && backCalls.isEmpty() && powerCalls.isEmpty(),
                    "back=" + Arrays.toString(back) + " power=" + Arrays.toString(power));
        });
        scenario("no_audio_service_still_consumes_the_key", n -> {
            Activity a = activity(null);
            check(n, VolumeKeys.dispatch(a, new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP)), "");
        });
        scenario("adjustment_for_maps_each_key", n -> {
            check(n, VolumeKeys.adjustmentFor(KeyEvent.KEYCODE_VOLUME_UP) == AudioManager.ADJUST_RAISE
                            && VolumeKeys.adjustmentFor(KeyEvent.KEYCODE_VOLUME_DOWN) == AudioManager.ADJUST_LOWER
                            && VolumeKeys.adjustmentFor(KeyEvent.KEYCODE_MUTE) == AudioManager.ADJUST_TOGGLE_MUTE
                            && VolumeKeys.adjustmentFor(KeyEvent.KEYCODE_VOLUME_MUTE) == AudioManager.ADJUST_TOGGLE_MUTE
                            && VolumeKeys.adjustmentFor(KeyEvent.KEYCODE_BACK) == VolumeKeys.NOT_A_VOLUME_KEY,
                    "");
        });
        scenario("attach_makes_music_the_volume_stream", n -> {
            Activity a = activity(new AudioManager());
            VolumeKeys.attach(a);
            check(n, a.volumeControlStream == AudioManager.STREAM_MUSIC, "stream=" + a.volumeControlStream);
        });
        scenario("silenced_when_muted_or_all_the_way_down", n -> {
            AudioManager audio = new AudioManager();
            boolean loud = VolumeKeys.silenced(audio);
            audio.muted = true;
            boolean muted = VolumeKeys.silenced(audio);
            audio.muted = false;
            audio.volume = 0;
            boolean zero = VolumeKeys.silenced(audio);
            audio.volume = 1;
            boolean one = VolumeKeys.silenced(audio);
            check(n, !loud && muted && zero && !one && !VolumeKeys.silenced(null),
                    "loud=" + loud + " muted=" + muted + " zero=" + zero + " one=" + one);
        });
        scenario("toggle_mute_toggles_music_mute", n -> {
            AudioManager audio = new AudioManager();
            VolumeKeys.toggleMute(activity(audio));
            VolumeKeys.toggleMute(activity(null));
            check(n, audio.calls.equals(Collections.singletonList("3,101,0")), "calls=" + audio.calls);
        });
        System.out.println(failures == 0 ? "ALL OK" : ("FAILURES " + failures));
        System.exit(failures == 0 ? 0 : 1);
    }
}
