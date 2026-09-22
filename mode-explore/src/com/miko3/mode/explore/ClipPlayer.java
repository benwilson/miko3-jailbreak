package com.miko3.mode.explore;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.SoundPool;
import android.util.Log;

import java.io.IOException;
import java.util.Random;

/**
 * Plays the startle chirps (assets/startle-*.wav, rendered by
 * scripts/gen-explore-sounds.py). SoundPool rather than the MediaPlayer that
 * mode-remote-control's SongPlayer uses: the clips are decoded once up front and
 * play() returns immediately, so a startle never delays the stop() the brain
 * issues just before it. Clips must be stored uncompressed in the APK for the
 * AssetFileDescriptor load, which aapt does for .wav by default.
 */
final class ClipPlayer {
    private static final String TAG = "ClipPlayer";
    private static final String[] STARTLE_CLIPS = {"startle-1.wav", "startle-2.wav", "startle-3.wav"};

    private final SoundPool pool;
    private final int[] startleIds;
    private final Random random = new Random();

    ClipPlayer(Context context) {
        pool = new SoundPool.Builder()
                .setMaxStreams(2)
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build())
                .build();
        startleIds = new int[STARTLE_CLIPS.length];
        for (int i = 0; i < STARTLE_CLIPS.length; i++) {
            startleIds[i] = load(context, STARTLE_CLIPS[i]);
        }
    }

    private int load(Context context, String asset) {
        try {
            AssetFileDescriptor afd = context.getAssets().openFd(asset);
            try {
                return pool.load(afd, 1);
            } finally {
                afd.close();
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot load " + asset, e);
            return 0;
        }
    }

    /** One of the startle clips, chosen at random so repeated startles vary. A clip
     * that failed to load (or is still decoding) is silently skipped. */
    void playStartle() {
        int id = startleIds[random.nextInt(startleIds.length)];
        if (id != 0) {
            pool.play(id, 1f, 1f, 1, 0, 1f);
        }
    }

    void release() {
        pool.release();
    }
}
