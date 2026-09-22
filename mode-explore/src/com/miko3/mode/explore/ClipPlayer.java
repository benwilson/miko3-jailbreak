package com.miko3.mode.explore;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.util.Random;

/**
 * Plays the startle chirps (assets/startle-*.wav, rendered by
 * scripts/gen-explore-sounds.py) through MediaPlayer on STREAM_MUSIC, the same
 * path mode-remote-control's SongPlayer uses.
 *
 * Not SoundPool: on this robot SoundPool.play() returns a valid stream and
 * produces no sound at all, while MediaPlayer on the same stream is audible
 * (checked on the device, 2026-09-22). Each clip gets its own player, prepared
 * once up front; playStartle() only posts a seek-and-start to a private
 * thread, so a startle never delays the stop() the brain issues just before
 * it. Clips must be stored uncompressed in the APK for openFd, which aapt
 * does for .wav by default.
 */
final class ClipPlayer {
    private static final String TAG = "ClipPlayer";
    private static final String[] STARTLE_CLIPS = {"startle-1.wav", "startle-2.wav", "startle-3.wav"};

    private final HandlerThread thread = new HandlerThread("explore-clips");
    private final Handler handler;
    private final MediaPlayer[] startles = new MediaPlayer[STARTLE_CLIPS.length];
    private final Random random = new Random();

    ClipPlayer(final Context context) {
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < STARTLE_CLIPS.length; i++) {
                    startles[i] = prepare(context, STARTLE_CLIPS[i]);
                }
            }
        });
    }

    private static MediaPlayer prepare(Context context, String asset) {
        MediaPlayer player = new MediaPlayer();
        try {
            AssetFileDescriptor afd = context.getAssets().openFd(asset);
            try {
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                afd.close();
            }
            player.prepare();
            return player;
        } catch (IOException | IllegalStateException e) {
            Log.e(TAG, "cannot prepare " + asset, e);
            player.release();
            return null;
        }
    }

    /** One of the startle clips, chosen at random so repeated startles vary. Returns at
     * once; a clip that failed to prepare is skipped. */
    void playStartle() {
        final int i = random.nextInt(STARTLE_CLIPS.length);
        handler.post(new Runnable() {
            @Override
            public void run() {
                MediaPlayer p = startles[i];
                if (p == null) {
                    Log.w(TAG, STARTLE_CLIPS[i] + " not prepared; skipping");
                    return;
                }
                try {
                    p.seekTo(0);
                    p.start();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "startle playback failed", e);
                }
            }
        });
    }

    void release() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < startles.length; i++) {
                    if (startles[i] != null) {
                        startles[i].release();
                        startles[i] = null;
                    }
                }
            }
        });
        thread.quitSafely();
    }
}
