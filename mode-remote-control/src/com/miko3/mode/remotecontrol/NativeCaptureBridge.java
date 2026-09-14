package com.miko3.mode.remotecontrol;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.util.Log;
import android.webkit.JavascriptInterface;

/**
 * Native fallback for the two operator-capture toggles (KTD7's
 * getUserMedia-based approach failed on this device's WebView — see U8's
 * commit history: getUserMedia({audio:true}) returns NotReadableError
 * here even with permission granted). Exposed to the served page's JS via
 * WebView.addJavascriptInterface so the page's own markup/JS stays R11's
 * one HTML UI; only what backs the capture call changes, per the plan's
 * explicitly sanctioned fallback design.
 *
 * Scoped to this version's on-device-only toggles (R6/R7 correction):
 * there is no real "remote operator" in this path, only the robot's own
 * mic/speaker — see toggleOperatorMic()'s own caution about that.
 */
final class NativeCaptureBridge {
    private static final String TAG = "NativeCaptureBridge";
    private static final int SAMPLE_RATE = 16000;

    private final Context context;
    private Thread passthroughThread;
    private volatile boolean running;

    NativeCaptureBridge(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Operator-mic-to-robot-speaker (R6), on-device-only fallback: raw
     * AudioRecord -> AudioTrack passthrough. NOTE: on this single-device,
     * on-device-only test path there is no separate "operator" — the
     * mic and speaker are the same robot's, so this is a literal
     * mic-to-speaker loop and WILL feed back (Larsen effect / a loud
     * howl) if both ends are physically near each other, exactly as any
     * live mic next to a live speaker would. The toggle and passthrough
     * loop are implemented and exercised structurally (AudioRecord/
     * AudioTrack both open and close cleanly), but NOT run as a
     * sustained live loop during verification, to avoid a real
     * uncontrolled feedback screech on physical hardware with no one
     * watching to intervene. A genuine remote operator (a different
     * physical location) would not hit this — this caution is specific
     * to the on-device degenerate case.
     */
    @JavascriptInterface
    public void toggleOperatorMic(boolean on) {
        if (on) {
            startPassthrough();
        } else {
            stopPassthrough();
        }
    }

    private synchronized void startPassthrough() {
        if (running) {
            return;
        }
        int minBufSize = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufSize <= 0) {
            Log.e(TAG, "unsupported AudioRecord configuration");
            return;
        }
        final AudioRecord recorder;
        final AudioTrack track;
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBufSize * 2);
            track = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    minBufSize * 2, AudioTrack.MODE_STREAM);
        } catch (SecurityException e) {
            Log.e(TAG, "RECORD_AUDIO denied at open time", e);
            return;
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED
                || track.getState() != AudioTrack.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord/AudioTrack failed to initialize");
            recorder.release();
            track.release();
            return;
        }
        running = true;
        recorder.startRecording();
        track.play();
        passthroughThread = new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] buf = new byte[4096];
                int consecutiveErrors = 0;
                while (running) {
                    int n = recorder.read(buf, 0, buf.length);
                    if (n > 0) {
                        consecutiveErrors = 0;
                        track.write(buf, 0, n);
                    } else {
                        // See MicCapture's same fix: a negative read is a real error
                        // (observed live: the mic already held by another process), not
                        // a transient empty read — don't busy-spin on it.
                        consecutiveErrors++;
                        Log.e(TAG, "AudioRecord.read() returned " + n);
                        if (consecutiveErrors >= 5) {
                            Log.e(TAG, "operator-mic passthrough giving up after repeated read errors");
                            running = false;
                        }
                    }
                }
                recorder.stop();
                recorder.release();
                track.stop();
                track.release();
            }
        }, "operator-mic-passthrough");
        passthroughThread.start();
        Log.i(TAG, "operator-mic passthrough started");
    }

    private synchronized void stopPassthrough() {
        running = false;
        if (passthroughThread != null) {
            try {
                passthroughThread.join(1000);
            } catch (InterruptedException ignored) {
            }
            passthroughThread = null;
        }
        Log.i(TAG, "operator-mic passthrough stopped");
    }
}
