package com.miko3.mode.remotecontrol;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.util.Log;

import java.io.IOException;

/**
 * Plays a bundled audio asset out the robot's own speaker (STREAM_MUSIC),
 * looping for as long as the operator's toggle is on. Bundled as a plain
 * asset (shared/assets/danger-zone.mp3, merged into this APK the same way
 * pico.min.css is -- see scripts/build_common.py's stage_assets()) rather
 * than fetched or streamed, so playback works with no network dependency.
 */
final class SongPlayer {
    private static final String TAG = "SongPlayer";
    private static final String ASSET_NAME = "danger-zone.mp3";

    private final Context context;
    private MediaPlayer mediaPlayer;

    SongPlayer(Context context) {
        this.context = context.getApplicationContext();
    }

    synchronized void start() {
        if (mediaPlayer != null) {
            return;
        }
        MediaPlayer player = new MediaPlayer();
        try {
            AssetFileDescriptor afd = context.getAssets().openFd(ASSET_NAME);
            try {
                player.setAudioStreamType(AudioManager.STREAM_MUSIC);
                player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                afd.close();
            }
            player.setLooping(true);
            player.prepare();
            player.start();
            mediaPlayer = player;
            Log.i(TAG, "song started");
        } catch (IOException e) {
            Log.e(TAG, "failed to start song", e);
            player.release();
        }
    }

    synchronized void stop() {
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
            Log.i(TAG, "song stopped");
        }
    }

    synchronized boolean isPlaying() {
        return mediaPlayer != null;
    }
}
