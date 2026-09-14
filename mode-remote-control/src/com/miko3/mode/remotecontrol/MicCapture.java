package com.miko3.mode.remotecontrol;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/**
 * Captures the robot's own microphone as raw 16-bit PCM (KTD8: no
 * container — sufficient at LAN scale and readable directly by the Web
 * Audio API) and pushes chunks to an AudioBroadcaster. Toggled on/off
 * independently of the camera and drive control (U8).
 */
final class MicCapture {
    private static final String TAG = "MicCapture";
    static final int SAMPLE_RATE = 16000;
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

    interface ErrorListener {
        void onMicError(String reason);
    }

    private final Context context;
    private final AudioBroadcaster broadcaster;
    private final ErrorListener errorListener;

    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean running;

    MicCapture(Context context, AudioBroadcaster broadcaster, ErrorListener errorListener) {
        this.context = context.getApplicationContext();
        this.broadcaster = broadcaster;
        this.errorListener = errorListener;
    }

    void start() {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            errorListener.onMicError("RECORD_AUDIO permission not granted");
            return;
        }
        int minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufSize <= 0) {
            errorListener.onMicError("unsupported AudioRecord configuration");
            return;
        }
        try {
            audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    CHANNEL_CONFIG, AUDIO_FORMAT, minBufSize * 2);
        } catch (SecurityException e) {
            errorListener.onMicError("RECORD_AUDIO denied at open time");
            return;
        }
        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            errorListener.onMicError("AudioRecord failed to initialize");
            audioRecord = null;
            return;
        }
        running = true;
        audioRecord.startRecording();
        captureThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                int consecutiveErrors = 0;
                while (running) {
                    int n = audioRecord.read(buf, 0, buf.length);
                    if (n > 0) {
                        consecutiveErrors = 0;
                        broadcaster.publishChunk(buf, n);
                    } else {
                        // A negative result is a real AudioRecord/HAL error code (e.g. the
                        // mic already held exclusively by the stock always-listening
                        // wake-word service — observed live as native error -38), not a
                        // transient empty read; looping tightly on it just busy-spins.
                        consecutiveErrors++;
                        Log.e(TAG, "AudioRecord.read() returned " + n);
                        if (consecutiveErrors >= 5) {
                            running = false;
                            errorListener.onMicError("microphone busy or unavailable (AudioRecord error " + n + ")");
                            break;
                        }
                    }
                }
            }
        }, "mic-capture");
        captureThread.start();
        Log.i(TAG, "mic capture started");
    }

    void stop() {
        running = false;
        if (captureThread != null) {
            try {
                captureThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            captureThread = null;
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
            }
            audioRecord.release();
            audioRecord = null;
        }
        Log.i(TAG, "mic capture stopped");
    }
}
