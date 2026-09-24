package com.miko3.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.RobotSpeech;

/**
 * Exported bound Service through which our mode apps make the robot speak
 * (voice plan U5; R8, R11, KTD4, KTD5). Every call first checks who is
 * calling (CallerGate, the same pinned-certificate check as
 * RobotSettingsService) and only then touches the queue, so a vendor app gets
 * a SecurityException and queues nothing.
 *
 * Lines go to the launcher's one SpeechEngine (LauncherApp.speech()), which
 * loaded the voice at launcher start. A line belongs to the calling uid:
 * cancel() only reaches that uid's lines. Each line's callback binder is
 * linked to death, so a mode that dies with lines queued has them cancelled
 * the same way.
 */
public class SpeechService extends Service {
    private static final String TAG = "SpeechService";
    public static final String ACTION_BIND = LauncherProtocol.ROBOT_SPEECH_ACTION;

    private final RobotSpeech.Stub binder = new RobotSpeech.Stub() {
        @Override
        public void speak(String text, RobotSpeech.Callback callback) {
            enforceCaller();
            if (callback == null) {
                throw new IllegalArgumentException("no callback");
            }
            int uid = Binder.getCallingUid();
            LineCallback line = new LineCallback(callback);
            // Linked before queueing, so a death at any point after this cancels it.
            line.link();
            try {
                line.id = speech().queue().speak(uid, text, line);
            } catch (IllegalArgumentException e) {
                line.unlink();
                Log.i(TAG, "uid " + uid + " refused: " + e.getMessage());
                throw e;
            }
            Log.i(TAG, "uid " + uid + " queued line " + line.id + ": \"" + text + "\"");
            if (!callback.asBinder().isBinderAlive()) {
                speech().queue().cancelLine(line.id);
            }
        }

        @Override
        public void cancel() {
            enforceCaller();
            int uid = Binder.getCallingUid();
            Log.i(TAG, "uid " + uid + " cancelled its lines");
            speech().queue().cancel(uid);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private SpeechEngine speech() {
        return ((LauncherApp) getApplication()).speech();
    }

    /** Throws SecurityException unless the calling uid owns a pinned Miko 3
     * package (CallerGate). Must run on the Binder thread, inside the call. */
    private void enforceCaller() {
        CallerGate.enforce(this, TAG);
    }

    /** One line's link to its caller: forwards the queue's verdict over the
     * one-way callback, and cancels the line if the caller's process dies. */
    private final class LineCallback implements SpeechQueue.Listener, IBinder.DeathRecipient {
        final RobotSpeech.Callback callback;
        volatile long id = -1;

        LineCallback(RobotSpeech.Callback callback) {
            this.callback = callback;
        }

        void link() {
            try {
                callback.asBinder().linkToDeath(this, 0);
            } catch (RemoteException e) {
                // Already dead; the isBinderAlive() check after queueing cancels it.
            }
        }

        void unlink() {
            try {
                callback.asBinder().unlinkToDeath(this, 0);
            } catch (RuntimeException ignored) {
                // Never linked (the caller was already dead).
            }
        }

        @Override
        public void binderDied() {
            Log.i(TAG, "caller of line " + id + " died; cancelling it");
            speech().queue().cancelLine(id);
        }

        @Override
        public void finished() {
            unlink();
            try {
                callback.finished();
            } catch (RemoteException e) {
                // The caller is gone; nothing to tell.
            }
        }

        @Override
        public void cancelled() {
            unlink();
            try {
                callback.cancelled();
            } catch (RemoteException e) {
                // The caller is gone; nothing to tell.
            }
        }
    }
}
