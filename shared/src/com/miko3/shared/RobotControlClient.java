package com.miko3.shared;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.root.aidlFiles.AnalyticsAIDL;
import com.root.aidlFiles.ExpressionEventAIDL;
import com.root.aidlFiles.GameControllerAIDL;
import com.root.aidlFiles.TouchEventAIDL;
import com.root.aidlFiles.UIDataAIDL;
import com.root.aidlFiles.UIEventAIDL;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Random;

/**
 * Wraps ServiceExam's AIDL surface (com.example.root.serviceexam) with a
 * connect()/drive() API for robot-control modes. Sends a GameEvent(135)
 * motion-expression request through ServiceClientInterface.12's direct
 * loadExpressionString(ServiceRequest.path) dispatch — a different entry
 * point, with a different payload shape (raw JSON in .path, no XML
 * wrapper, since loadExpressionString Gson-parses its argument directly),
 * from parseAIMLexpression()'s XML-wrapped path that startDOAThread() uses
 * internally. Reuses the same underlying ExpressionMsg/MotionMsg/
 * writeUART() pipeline either way, over the same unmodified AIDL stubs
 * (see shared/src/com/root/aidlFiles), rather than touching
 * libmiko_drivers.so directly.
 *
 * See docs/hardware/aidl-dispatch.md (bind/init sequence) and
 * docs/hardware/motors-wheels.md's "DEFINITIVE RESULT" section (the
 * confirmed-working payload shape, live-verified via ServiceExam's own
 * logcat reaching the peripheral motor board with a positive completion
 * ACK).
 */
public class RobotControlClient {
    private static final String TAG = "RobotControlClient";
    private static final String SERVICE_ACTION = "my.service";
    private static final String SERVICE_PACKAGE = "com.example.root.serviceexam";

    /** AIDL code 135: expression playback (TTM-aware) — used for motion-expression frames. */
    private static final int CODE_EXPRESSION_PLAYBACK = 135;

    public interface Listener {
        void onConnected();
        void onDisconnected();
        /** A drive()/stop() call failed because the AIDL channel is unreachable right now. */
        void onSendFailed(RemoteException e);
    }

    private final Context context;
    private final Listener listener;
    private volatile GameControllerAIDL gameController;
    private volatile boolean bound;
    private final Random idGen = new Random();

    private final ExpressionEventAIDL.Stub expressionEventStub = new ExpressionEventAIDL.Stub() {
        @Override
        public void expressionEvent(String data) {
            // Face/expression events FROM ServiceExam — not consumed by drive control.
        }
    };

    private final UIDataAIDL.Stub uiDataStub = new UIDataAIDL.Stub() {
        @Override
        public void updateUIData(String data) {
            // Generic data back-channel FROM ServiceExam — not consumed here.
        }

        @Override
        public void onCameraFrame(Bitmap bitmap, int i) {
            // ServiceExam's own face-cam frames — this client uses Camera2 directly (U6), not this.
        }
    };

    private final TouchEventAIDL.Stub touchEventStub = new TouchEventAIDL.Stub() {
        @Override
        public void touchEvent(String data) {
            // Touch sensor events FROM ServiceExam — not consumed by drive control.
        }

        @Override
        public void init(AnalyticsAIDL analytics, GameControllerAIDL controller) {
            // Arrives asynchronously on ServiceExam's own Binder callback thread,
            // strictly after uiEvent.init() returns to the caller (confirmed live:
            // U1's spike observed this land ~30ms after init() returned, on a
            // different thread) — this callback, not init()'s return, is what
            // actually means "ready to drive".
            gameController = controller;
            bound = true;
            if (listener != null) {
                listener.onConnected();
            }
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            UIEventAIDL uiEvent = UIEventAIDL.Stub.asInterface(binder);
            try {
                uiEvent.init(expressionEventStub, touchEventStub, uiDataStub);
                // Do not report onConnected() here — see touchEventStub.init() above.
            } catch (RemoteException | SecurityException e) {
                Log.e(TAG, "init() failed", e);
                bound = false;
                if (listener != null) {
                    listener.onDisconnected();
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            bound = false;
            gameController = null;
            if (listener != null) {
                listener.onDisconnected();
            }
        }
    };

    public RobotControlClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
    }

    /** Starts the bind; connection completion (or failure) arrives via the Listener. */
    public void connect() {
        Intent intent = new Intent(SERVICE_ACTION);
        intent.setPackage(SERVICE_PACKAGE);
        boolean started = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        if (!started) {
            bound = false;
            if (listener != null) {
                listener.onDisconnected();
            }
        }
    }

    public void disconnect() {
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // never bound
        }
        bound = false;
        gameController = null;
    }

    public boolean isConnected() {
        return bound && gameController != null;
    }

    /**
     * Sends a single motion-expression frame. linear/angular are raw signed
     * values in whatever units MotionFrame uses (only linear=0/angular=±20
     * has been observed live in the stock app; other values are an
     * extrapolation, not independently confirmed). timeCentiseconds is the
     * frame's duration; only "10" has been observed live.
     */
    public void drive(int linear, int angular, int timeCentiseconds) throws RemoteException {
        GameControllerAIDL controller = gameController;
        if (controller == null) {
            throw new RemoteException("not connected to ServiceExam");
        }
        String expressionJson = buildMotionExpressionJson(linear, angular, timeCentiseconds);
        JSONObject request = new JSONObject();
        try {
            request.put("name", "");
            // Code 135's real handler (ServiceClientInterface.12) reads
            // ServiceRequest.path via loadExpressionString(), NOT .data — .data is
            // read separately and, if non-empty, becomes spoken TTS text layered on
            // top. loadExpressionString() Gson-parses its argument directly as JSON,
            // with no XML wrapper — the "<block><expression>...</expression></block>"
            // wrapping is only meaningful to the *other* entry point,
            // parseAIMLexpression() (what startDOAThread() calls), which this AIDL
            // client never reaches. Sending the payload via .data with an XML wrapper
            // (as this method originally did) throws inside ServiceExam on both counts
            // once the envelope-encoding bug is fixed: a NullPointerException from
            // loadExpressionString(null) since .path was never set, or (if a
            // placeholder .path were sent) a JsonSyntaxException from parsing text
            // starting with '<' as JSON. .data must still be present as "" — it's
            // read unconditionally via .length() with no null check. See
            // docs/hardware/motors-wheels.md's "DEFINITIVE RESULT" section — this
            // exact shape was confirmed via live ServiceExam logcat reaching the
            // physical peripheral board with a positive CPL=1 completion ACK.
            request.put("path", expressionJson);
            request.put("data", "");
            request.put("audioPath", "");
        } catch (JSONException e) {
            throw new RemoteException("failed to build drive payload: " + e.getMessage());
        }
        JSONObject envelope = new JSONObject();
        try {
            // ServiceExam's ServiceData deserializes "data" as Map<Integer, String> — the
            // value at each code must be a JSON-encoded STRING (a serialized ServiceRequest),
            // not a nested raw object. Sending the JSONObject directly here previously made
            // every drive command fail Gson deserialization silently inside ServiceExam's own
            // process (JsonSyntaxException: "Expected a string but was BEGIN_OBJECT"): the
            // AIDL call itself returned cleanly with no exception, so this went undetected
            // until a live logcat capture of ServiceExam's own process caught the parse
            // failure and corroborated it against unchanging wheel-encoder telemetry across
            // a full multi-attempt drive-command battery. See
            // docs/hardware/motors-wheels.md's "ROOT CAUSE FOUND" section.
            envelope.put("data", new JSONObject().put(String.valueOf(CODE_EXPRESSION_PLAYBACK), request.toString()));
        } catch (JSONException e) {
            throw new RemoteException("failed to build drive envelope: " + e.getMessage());
        }
        controller.GameEvent(envelope.toString());
    }

    /** Convenience: an explicit stop (zero linear, zero angular). */
    public void stop() throws RemoteException {
        drive(0, 0, 10);
    }

    /** Raw JSON only — no XML wrapper. loadExpressionString() (what code 135 actually
     * calls) Gson-parses its argument directly as ExpressionMsg; the
     * "&lt;block&gt;&lt;expression&gt;...&lt;/expression&gt;&lt;/block&gt;" wrapping some
     * earlier analysis assumed is only meaningful to parseAIMLexpression(), a different
     * entry point this AIDL client never reaches. */
    private String buildMotionExpressionJson(int linear, int angular, int timeCentiseconds) {
        String mx = "{\"type\":4,\"size\":0,\"motion_type\":4,\"loop\":1,\"kp\":0,\"ki\":0,\"kd\":0,"
                + "\"pidcontrol\":0,\"seqCount\":1,\"seq\":[{\"linear\":" + linear
                + ",\"angular\":" + angular + ",\"time\":" + timeCentiseconds
                + ",\"type\":1,\"id\":0}]}";
        String empty = "{\"type\":0,\"size\":0,\"loop\":0,\"seqCount\":0,\"seq\":[]}";
        return "{\"tx\":" + empty + ",\"ax\":" + empty + ",\"mx\":" + mx
                + ",\"ix\":{\"type\":0,\"size\":0,\"imagetype\":0,\"loop\":0,\"seqCount\":0}"
                + ",\"rx\":" + empty + ",\"id\":" + Math.abs(idGen.nextInt(1000)) + "}";
    }
}
