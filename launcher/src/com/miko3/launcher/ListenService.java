package com.miko3.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.RobotListen;

/**
 * Exported bound Service through which our mode apps hear one short spoken
 * reply (explore-on-claude plan U3; R11, R12, KTD1, KTD4, KTD8). Built like
 * SpeechService: every call first checks who is calling (CallerGate, the
 * pinned-certificate check), and only then claims the microphone, so a
 * vendor app gets a SecurityException and never records anything.
 *
 * The listen itself runs on the launcher's ListenEngine thread
 * (LauncherApp.listen()): it waits for the speech queue to go idle, records
 * until the speaker stops or the cap, and answers over the caller's one-way
 * callback. One listen at a time; a second caller gets IllegalStateException
 * ("already listening"). A caller that dies mid-listen just loses its
 * answer; the listen ends at its cap anyway.
 */
public class ListenService extends Service {
    private static final String TAG = "ListenService";
    public static final String ACTION_BIND = LauncherProtocol.ROBOT_LISTEN_ACTION;

    private final RobotListen.Stub binder = new RobotListen.Stub() {
        @Override
        public void listen(long maxMs, final RobotListen.Callback callback) {
            enforceCaller();
            if (callback == null) {
                throw new IllegalArgumentException("no callback");
            }
            final int uid = Binder.getCallingUid();
            ListenEngine engine = ears();
            try {
                engine.session().claim();
            } catch (IllegalStateException e) {
                Log.i(TAG, "uid " + uid + " refused: " + e.getMessage());
                throw e;
            }
            Log.i(TAG, "uid " + uid + " listening for up to " + ListenSession.clampCap(maxMs) + " ms");
            engine.listen(maxMs, new ListenEngine.Answer() {
                @Override
                public void answer(ListenSession.Result r) {
                    Log.i(TAG, "uid " + uid + " listen " + r + (r.outcome == ListenSession.Outcome.HEARD
                            ? ", " + (r.text == null ? 0 : r.text.length()) + " chars" : ""));
                    try {
                        switch (r.outcome) {
                            case HEARD:
                                callback.heard(r.text);
                                break;
                            case NO_SPEECH:
                                callback.noSpeech();
                                break;
                            default:
                                callback.failed(r.reason);
                                break;
                        }
                    } catch (RemoteException | RuntimeException e) {
                        // The caller is gone; nothing to tell.
                    }
                }
            });
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private ListenEngine ears() {
        return ((LauncherApp) getApplication()).listen();
    }

    /** Throws SecurityException unless the calling uid owns a pinned Miko 3
     * package (CallerGate). Must run on the Binder thread, inside the call. */
    private void enforceCaller() {
        CallerGate.enforce(this, TAG);
    }
}
