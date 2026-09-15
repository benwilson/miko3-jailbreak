package com.miko3.mode.remotecontrol;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;

/**
 * Plays a remote operator's own microphone audio out the robot's physical
 * speaker (R6's genuine remote path, as opposed to NativeCaptureBridge's
 * on-device mic-to-speaker loopback fallback): /operator-audio-upload feeds
 * raw 16-bit PCM chunks here directly from the HTTP request-handling
 * thread, written straight to a streaming AudioTrack. No broadcaster/fan-out
 * needed here unlike the *Broadcaster classes -- there is exactly one
 * destination (this robot's own speaker), not N subscribers.
 */
final class OperatorSpeakerPlayer {
    private static final String TAG = "OperatorSpeakerPlayer";
    static final int SAMPLE_RATE = 16000;

    private AudioTrack track;

    synchronized void write(byte[] data, int len) {
        if (track == null) {
            int minBufSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBufSize <= 0) {
                Log.e(TAG, "unsupported AudioTrack configuration");
                return;
            }
            AudioTrack newTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize * 2, AudioTrack.MODE_STREAM);
            if (newTrack.getState() != AudioTrack.STATE_INITIALIZED) {
                Log.e(TAG, "AudioTrack failed to initialize");
                newTrack.release();
                return;
            }
            newTrack.play();
            track = newTrack;
            Log.i(TAG, "operator-audio playback started");
        }
        track.write(data, 0, len);
    }

    synchronized void stop() {
        if (track != null) {
            try {
                track.stop();
            } catch (IllegalStateException ignored) {
            }
            track.release();
            track = null;
            Log.i(TAG, "operator-audio playback stopped");
        }
    }
}
