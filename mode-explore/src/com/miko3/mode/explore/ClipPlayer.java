package com.miko3.mode.explore;

import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Plays the startle chirps and the idle songs (assets/startle-*.wav and
 * assets/song-*.wav, rendered by scripts/gen-explore-sounds.py) through
 * MediaPlayer on STREAM_MUSIC, the same path mode-remote-control's SongPlayer
 * uses.
 *
 * Singing: while he sits still (resting or eyes-only) he hums a short phrase
 * every so often, like Wall-E. The first phrase waits a few seconds, so a
 * brief stop (the moment at start-up before readings arrive) stays quiet, and
 * stopSinging() cuts any phrase off the moment he moves again. All song state
 * lives on the clip thread.
 *
 * Not SoundPool: on this robot SoundPool.play() returns a valid stream and
 * produces no sound at all, while MediaPlayer on the same stream is audible
 * (checked on the device, 2026-09-22). Each clip gets its own player, prepared
 * once up front; playStartle() only posts a seek-and-start to a private
 * thread, so a startle never delays the stop() the brain issues just before
 * it. Clips must be stored uncompressed in the APK for openFd, which aapt
 * does for .wav by default.
 *
 * Curiosity clips (camera curiosity plan U6): playName() says a detector label
 * (assets/name-<label with spaces as dashes>.wav, from scripts/gen-explore-voice.py)
 * and playReaction() plays a random variant of a reaction group
 * (assets/react-<group>-<n>.wav, from scripts/gen-explore-sounds.py). There are
 * too many names to hold a prepared player for each, so these are prepared on the
 * clip thread when asked and released when they finish. Only one plays at a time:
 * a new one cuts off the one still playing.
 */
final class ClipPlayer {
    private static final String TAG = "ClipPlayer";
    private static final String[] STARTLE_CLIPS = {"startle-1.wav", "startle-2.wav", "startle-3.wav"};
    private static final String[] SONG_CLIPS = {"song-1.wav", "song-2.wav", "song-3.wav", "song-4.wav"};
    /** Still for this long before the first phrase. */
    private static final long FIRST_SONG_DELAY_MS = 3000;
    /** Quiet gap between phrases, drawn from this range. */
    private static final long SONG_GAP_MIN_MS = 6000;
    private static final long SONG_GAP_MAX_MS = 12000;

    private final Context context;
    private final HandlerThread thread = new HandlerThread("explore-clips");
    private final Handler handler;
    private final MediaPlayer[] startles = new MediaPlayer[STARTLE_CLIPS.length];
    private final MediaPlayer[] songs = new MediaPlayer[SONG_CLIPS.length];
    /** Clip thread only: whether a singing session is on, and the phrase playing now. */
    private boolean singing;
    private MediaPlayer currentSong;
    /** Clip thread only: reaction group -> its variant assets, and the one-shot playing now. */
    private final Map<String, List<String>> reactions = new HashMap<>();
    private MediaPlayer currentOneShot;
    private final Random random = new Random();

    ClipPlayer(final Context context) {
        this.context = context;
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                for (int i = 0; i < STARTLE_CLIPS.length; i++) {
                    startles[i] = prepare(context, STARTLE_CLIPS[i]);
                }
                for (int i = 0; i < SONG_CLIPS.length; i++) {
                    songs[i] = prepare(context, SONG_CLIPS[i]);
                }
                indexReactions();
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

    /** Say a detector label's name ("ooh, a plant"), e.g. playName("potted plant").
     * Returns at once; a label with no clip is logged and skipped. */
    void playName(String cocoLabel) {
        final String asset = "name-" + cocoLabel.replace(' ', '-') + ".wav";
        handler.post(new Runnable() {
            @Override
            public void run() {
                playOneShot(asset);
            }
        });
    }

    /** A random variant of a reaction group: "curious", "thinking", "disappointed",
     * "delighted" or "puzzled". Returns at once; an unknown group is logged and skipped. */
    void playReaction(final String group) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                List<String> variants = reactions.get(group);
                if (variants == null || variants.isEmpty()) {
                    Log.w(TAG, "no clips for reaction " + group + "; skipping");
                    return;
                }
                playOneShot(variants.get(random.nextInt(variants.size())));
            }
        });
    }

    /** Clip thread: find the react-<group>-<n>.wav assets once, so the variant count lives
     * only in the generator. */
    private void indexReactions() {
        try {
            String[] names = context.getAssets().list("");
            if (names == null) {
                return;
            }
            for (String name : names) {
                if (!name.startsWith("react-") || !name.endsWith(".wav")) {
                    continue;
                }
                int dash = name.lastIndexOf('-');
                if (dash <= "react-".length()) {
                    continue;
                }
                String group = name.substring("react-".length(), dash);
                List<String> variants = reactions.get(group);
                if (variants == null) {
                    variants = new ArrayList<>();
                    reactions.put(group, variants);
                }
                variants.add(name);
            }
        } catch (IOException e) {
            Log.e(TAG, "cannot list reaction clips", e);
        }
    }

    /** Clip thread: prepare an asset, play it once and release it when it ends (or
     * fails), cutting off any one-shot still playing. */
    private void playOneShot(String asset) {
        stopOneShot();
        final MediaPlayer p = prepare(context, asset);
        if (p == null) {
            return;
        }
        // Created on the clip thread, so these callbacks run on it too.
        p.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                releaseOneShot(mp);
            }
        });
        p.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                Log.w(TAG, "clip error " + what + "/" + extra);
                releaseOneShot(mp);
                return true;
            }
        });
        currentOneShot = p;
        try {
            p.start();
        } catch (IllegalStateException e) {
            Log.w(TAG, asset + " playback failed", e);
            releaseOneShot(p);
        }
    }

    private void releaseOneShot(MediaPlayer p) {
        if (currentOneShot == p) {
            currentOneShot = null;
        }
        p.release();
    }

    private void stopOneShot() {
        if (currentOneShot != null) {
            releaseOneShot(currentOneShot);
        }
    }

    /** Start humming now and then until stopSinging(). Idempotent; returns at once. */
    void startSinging() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (singing) {
                    return;
                }
                singing = true;
                handler.postDelayed(singPhrase, FIRST_SONG_DELAY_MS);
            }
        });
    }

    /** Stop humming, cutting off any phrase in progress. Idempotent; returns at once. */
    void stopSinging() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                singing = false;
                handler.removeCallbacks(singPhrase);
                if (currentSong != null) {
                    try {
                        if (currentSong.isPlaying()) {
                            currentSong.pause();
                        }
                        currentSong.seekTo(0);
                    } catch (IllegalStateException e) {
                        Log.w(TAG, "could not stop the song", e);
                    }
                    currentSong = null;
                }
            }
        });
    }

    /** One phrase, then the next after a quiet gap, for as long as singing stays on. */
    private final Runnable singPhrase = new Runnable() {
        @Override
        public void run() {
            if (!singing) {
                return;
            }
            MediaPlayer p = songs[random.nextInt(songs.length)];
            long gap = SONG_GAP_MIN_MS + (long) (random.nextDouble() * (SONG_GAP_MAX_MS - SONG_GAP_MIN_MS));
            if (p != null) {
                try {
                    p.seekTo(0);
                    p.start();
                    currentSong = p;
                    gap += p.getDuration();
                } catch (IllegalStateException e) {
                    Log.w(TAG, "song playback failed", e);
                }
            }
            handler.postDelayed(this, gap);
        }
    };

    void release() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                singing = false;
                handler.removeCallbacks(singPhrase);
                currentSong = null;
                stopOneShot();
                releaseAll(startles);
                releaseAll(songs);
            }
        });
        thread.quitSafely();
    }

    private static void releaseAll(MediaPlayer[] players) {
        for (int i = 0; i < players.length; i++) {
            if (players[i] != null) {
                players[i].release();
                players[i] = null;
            }
        }
    }
}
