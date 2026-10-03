package com.miko3.launcher;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.miko3.shared.LauncherProtocol;
import com.miko3.shared.RobotEars;
import com.miko3.shared.RobotListen;

/**
 * Exported bound Service through which our mode apps hear (explore-on-claude
 * plan U3; meeting plan U3). Two Binders, picked by the bind action:
 *
 * ROBOT_LISTEN, the one-shot RobotListen: one short spoken reply, for callers
 * with no ears session open (a second one gets IllegalStateException
 * "already listening"; any caller gets "ears session open" while a session
 * is held, KTD1). The listen runs on the launcher's ListenEngine thread: it
 * waits for the speech queue to go idle, records until the speaker stops or
 * the cap, and answers over the caller's one-way callback.
 *
 * ROBOT_EARS, the continuous RobotEars session (KTD1, KTD6): open, renew,
 * close, a conversation listen, a clip window and the shove stamp, bound to
 * the uid that opened it; utterances stream back one-way through the
 * callback. Built like SpeechService: every call first checks who is calling
 * (CallerGate, the pinned-certificate check), so a vendor app gets a
 * SecurityException and never records anything. Logs uids and counters,
 * never words.
 */
public class ListenService extends Service {
    private static final String TAG = "ListenService";
    public static final String ACTION_BIND = LauncherProtocol.ROBOT_LISTEN_ACTION;
    public static final String ACTION_BIND_EARS = LauncherProtocol.ROBOT_EARS_ACTION;

    private final RobotListen.Stub binder = new RobotListen.Stub() {
        @Override
        public void listen(long maxMs, final RobotListen.Callback callback) {
            enforceCaller();
            if (callback == null) {
                throw new IllegalArgumentException("no callback");
            }
            final int uid = Binder.getCallingUid();
            ListenEngine engine = engine();
            try {
                engine.refuseOneShot();
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

    private final RobotEars.Stub earsBinder = new RobotEars.Stub() {
        @Override
        public void open(RobotEars.Callback callback, boolean chargerLatched) {
            enforceCaller();
            if (callback == null) {
                throw new IllegalArgumentException("no callback");
            }
            int uid = Binder.getCallingUid();
            try {
                engine().openEars(uid, callback, chargerLatched);
            } catch (IllegalStateException e) {
                Log.i(TAG, "uid " + uid + " ears refused: " + e.getMessage());
                throw e;
            }
            Log.i(TAG, "uid " + uid + " opened the ears" + (chargerLatched ? " (charger latched)" : ""));
        }

        @Override
        public boolean renew(boolean chargerLatched) {
            enforceCaller();
            int uid = Binder.getCallingUid();
            return engine().ears().renew(String.valueOf(uid), chargerLatched);
        }

        @Override
        public void close() {
            enforceCaller();
            int uid = Binder.getCallingUid();
            if (engine().ears().close(String.valueOf(uid))) {
                Log.i(TAG, "uid " + uid + " closed the ears");
            }
        }

        @Override
        public void listen(long maxMs) {
            enforceCaller();
            int uid = Binder.getCallingUid();
            boolean ok = engine().ears().listen(String.valueOf(uid), maxMs);
            Log.i(TAG, "uid " + uid + " conversation listen " + (ok ? "for a start within "
                    + ListenSession.clampCap(maxMs) + " ms (an answer is cut at " + EarsSession.LISTEN_HARD_CAP_MS
                    + " ms)" : "refused"));
        }

        @Override
        public void clipWindow(long durationMs) {
            enforceCaller();
            int uid = Binder.getCallingUid();
            engine().ears().clipWindow(String.valueOf(uid), durationMs);
        }

        @Override
        public void shoved(long atElapsedMs) {
            enforceCaller();
            int uid = Binder.getCallingUid();
            engine().ears().shoved(String.valueOf(uid), atElapsedMs);
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        if (intent != null && ACTION_BIND_EARS.equals(intent.getAction())) {
            return earsBinder;
        }
        return binder;
    }

    private ListenEngine engine() {
        return ((LauncherApp) getApplication()).listen();
    }

    /** Throws SecurityException unless the calling uid owns a pinned Miko 3
     * package (CallerGate). Must run on the Binder thread, inside the call. */
    private void enforceCaller() {
        CallerGate.enforce(this, TAG);
    }
}
