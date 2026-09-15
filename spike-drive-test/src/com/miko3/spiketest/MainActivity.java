package com.miko3.spiketest;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.TextView;
import com.root.aidlFiles.AnalyticsAIDL;
import com.root.aidlFiles.ExpressionEventAIDL;
import com.root.aidlFiles.GameControllerAIDL;
import com.root.aidlFiles.TouchEventAIDL;
import com.root.aidlFiles.UIDataAIDL;
import com.root.aidlFiles.UIEventAIDL;
import org.json.JSONObject;

/**
 * Standalone spike test: binds directly to ServiceExam's MyService and sends a
 * *correctly formed* GameEvent envelope (the "data" map value double-encoded as a
 * JSON string, per ServiceData/callEvent's real deserialization contract), bypassing
 * RobotControlClient's malformed envelope entirely. Exists only to answer one
 * question: does a well-formed drive command actually move the wheels.
 */
public class MainActivity extends Activity {
    private static final String TAG = "SPIKETEST";
    private static final String SERVICE_ACTION = "my.service";
    private static final String SERVICE_PACKAGE = "com.example.root.serviceexam";

    private volatile GameControllerAIDL gameController;
    private TextView statusView;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ExpressionEventAIDL.Stub expressionEventStub = new ExpressionEventAIDL.Stub() {
        @Override
        public void expressionEvent(String s) {
            Log.e(TAG, "expressionEvent callback: " + s);
        }
    };

    private final UIDataAIDL.Stub uiDataStub = new UIDataAIDL.Stub() {
        @Override
        public void updateUIData(String s) {
        }

        @Override
        public void onCameraFrame(Bitmap bitmap, int i) {
        }
    };

    private final TouchEventAIDL.Stub touchEventStub = new TouchEventAIDL.Stub() {
        @Override
        public void touchEvent(String s) {
        }

        @Override
        public void init(AnalyticsAIDL analyticsAIDL, GameControllerAIDL g) {
            Log.e(TAG, "handshake complete, GameControllerAIDL obtained");
            gameController = g;
            setStatus("CONNECTED - sending ACTIVE_OTHERS state-machine handshake");
            try {
                gameController.GameEvent("{\"data\":{\"122\":\"ACTIVE_OTHERS\"}}");
                Log.e(TAG, "sent code 122 ACTIVE_OTHERS");
            } catch (Exception e) {
                Log.e(TAG, "ACTIVE_OTHERS send failed", e);
            }
            setStatus("CONNECTED - firing Explore/Linear.txt-shaped frame ONCE");
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendExploreLinear(20, 0, "EXPLORE-LINEAR");
                }
            }, 1500);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    sendDrive(0, 0, 10, "STOP");
                    setStatus("DONE - sequence complete, check robot");
                }
            }, 1500 + 20000L);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder iBinder) {
            Log.e(TAG, "onServiceConnected, calling UIEventAIDL.init");
            try {
                UIEventAIDL.Stub.asInterface(iBinder)
                        .init(expressionEventStub, touchEventStub, uiDataStub);
                setStatus("bound, awaiting handshake...");
            } catch (Exception e) {
                Log.e(TAG, "UIEventAIDL.init failed", e);
                setStatus("init() failed: " + e);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            Log.e(TAG, "onServiceDisconnected");
            setStatus("disconnected");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusView = new TextView(this);
        statusView.setTextSize(22);
        statusView.setPadding(24, 24, 24, 24);
        statusView.setText("SPIKE TEST starting...");
        setContentView(statusView);

        Intent intent = new Intent(SERVICE_ACTION);
        intent.setPackage(SERVICE_PACKAGE);
        boolean bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Log.e(TAG, "bindService returned " + bound);
        setStatus("bindService=" + bound);
    }

    private void setStatus(final String s) {
        Log.e(TAG, "STATUS: " + s);
        handler.post(new Runnable() {
            @Override
            public void run() {
                statusView.setText(s);
            }
        });
    }

    /** Replicates /sdcard/klug/APPS/expressions/AutoMode/Explore/Linear.txt's exact "mx"
     * payload verbatim (frame.type=25, seqCount=2, times 40/2) instead of this class's
     * own hand-built type=1 single-frame payload, to test whether type=25 is what lets
     * idle-mode driving sustain motion where our own drive path stalls after ~300ms. */
    private void sendExploreLinear(int linear, int angular, String label) {
        GameControllerAIDL gc = gameController;
        if (gc == null) {
            Log.e(TAG, label + ": sendExploreLinear called but not connected");
            return;
        }
        try {
            String mx = "{\"type\":4,\"size\":0,\"motion_type\":4,\"loop\":1,\"kp\":0,\"ki\":0,\"kd\":0,"
                    + "\"pidcontrol\":0,\"seqCount\":2,\"seq\":["
                    + "{\"linear\":" + linear + ",\"angular\":" + angular + ",\"time\":40,\"type\":25,\"id\":0},"
                    + "{\"linear\":" + linear + ",\"angular\":" + angular + ",\"time\":2,\"type\":25,\"id\":1}"
                    + "]}";
            String rawJson = "{\"tx\":{\"type\":0,\"size\":0,\"loop\":0,\"seqCount\":0,\"seq\":[]},"
                    + "\"ax\":{\"type\":0,\"size\":0,\"loop\":0,\"seqCount\":0,\"seq\":[]},\"mx\":" + mx + ","
                    + "\"ix\":{\"type\":0,\"size\":0,\"imagetype\":0,\"loop\":0,\"seqCount\":0},"
                    + "\"rx\":{\"type\":0,\"size\":0,\"loop\":0,\"seqCount\":0,\"seq\":[]},"
                    + "\"id\":" + (int) (Math.random() * 1000) + "}";

            JSONObject serviceRequest = new JSONObject();
            serviceRequest.put("name", "");
            serviceRequest.put("path", rawJson);
            serviceRequest.put("data", "");
            serviceRequest.put("audioPath", "");

            JSONObject dataMap = new JSONObject();
            dataMap.put("135", serviceRequest.toString());
            JSONObject envelope = new JSONObject();
            envelope.put("data", dataMap);

            String payload = envelope.toString();
            Log.e(TAG, label + ": sending EXPLORE-LINEAR payload: " + payload);
            gc.GameEvent(payload);
            setStatus(label + " linear=" + linear + " angular=" + angular);
        } catch (Exception e) {
            Log.e(TAG, label + ": sendExploreLinear failed", e);
            setStatus(label + " failed: " + e);
        }
    }

    /** Builds the CORRECT envelope: {"data":{"135": "<ServiceRequest JSON, as a string>"}} */
    private void sendDrive(int linear, int angular, int timeCs, String label) {
        GameControllerAIDL gc = gameController;
        if (gc == null) {
            Log.e(TAG, label + ": sendDrive called but not connected");
            return;
        }
        try {
            String mx = "{\"type\":4,\"size\":0,\"motion_type\":4,\"loop\":1,\"kp\":0,\"ki\":0,\"kd\":0,"
                    + "\"pidcontrol\":0,\"seqCount\":1,\"seq\":[{\"linear\":" + linear
                    + ",\"angular\":" + angular + ",\"time\":" + timeCs + ",\"type\":1,\"id\":0}]}";
            // loadExpressionString(path) does Gson.fromJson(path, ExpressionMsg.class) directly —
            // it wants raw JSON, NOT the <block><expression>...</expression></block> XML wrapper
            // (that wrapper is only unwrapped by the *other* method, parseAIMLexpression).
            String rawJson = "{\"tx\":{\"type\":0,\"size\":0,\"loop\":0,\"seqCount\":0,\"seq\":[]},"
                    + "\"ax\":{\"type\":0,\"size\":0,\"loop\":0,\"seqCount\":0,\"seq\":[]},\"mx\":" + mx + ","
                    + "\"ix\":{\"type\":0,\"size\":0,\"imagetype\":0,\"loop\":0,\"seqCount\":0},"
                    + "\"rx\":{\"type\":0,\"size\":0,\"loop\":0,\"seqCount\":0,\"seq\":[]},"
                    + "\"id\":" + (int) (Math.random() * 1000) + "}";

            JSONObject serviceRequest = new JSONObject();
            serviceRequest.put("name", "");
            serviceRequest.put("path", rawJson);
            serviceRequest.put("data", "");
            serviceRequest.put("audioPath", "");

            // THE FIX: the map value must be the ServiceRequest JSON *as a string*,
            // not a nested raw object — matches ServiceData.data: HashMap<Integer,String>.
            JSONObject dataMap = new JSONObject();
            dataMap.put("135", serviceRequest.toString());
            JSONObject envelope = new JSONObject();
            envelope.put("data", dataMap);

            String payload = envelope.toString();
            Log.e(TAG, label + ": sending FIXED payload: " + payload);
            gc.GameEvent(payload);
            setStatus(label + " linear=" + linear + " angular=" + angular);
        } catch (Exception e) {
            Log.e(TAG, label + ": sendDrive failed", e);
            setStatus(label + " failed: " + e);
        }
    }
}
