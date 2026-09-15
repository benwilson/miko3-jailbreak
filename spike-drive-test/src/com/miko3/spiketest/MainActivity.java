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
import com.miko3.shared.DirectMotorDriver;
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

    /** Set true to run testDirectMotorDriver() instead of the ServiceExam/AIDL
     * bind path below -- isolates DirectMotorDriver.driveSustained() + its own
     * keepalive thread exactly as DriveController uses them, with nothing else
     * (no WS, no ModeApp, no ServiceExam) in the loop. */
    private static final boolean TEST_DIRECT_MOTOR_DRIVER = false;

    /** Set true to run testSensorModuleUart() -- loads the real, system-installed
     * libmiko_drivers.so and drives through its actual createUART/initUART/
     * writeUART natives (see emotix.com.drivers.SensorModule) instead of
     * reimplementing the UART protocol in Java. */
    private static final boolean TEST_SENSOR_MODULE_NATIVE = true;

    /** Set true to run RobotControlClient.driveSustained() repeatedly (every
     * 500ms, matching DriveController's real cadence) through ServiceExam's own
     * GameEvent(135) AIDL path -- the one combination never actually tested:
     * the confirmed-working type=25 payload, sent the way production would
     * really send it (resent, not one-shot), while staying inside ServiceExam's
     * own process the whole time. */
    private static final boolean TEST_RCC_SUSTAINED_RESEND = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        statusView = new TextView(this);
        statusView.setTextSize(22);
        statusView.setPadding(24, 24, 24, 24);
        statusView.setText("SPIKE TEST starting...");
        setContentView(statusView);

        if (TEST_SENSOR_MODULE_NATIVE) {
            testSensorModuleUart();
            return;
        }

        if (TEST_DIRECT_MOTOR_DRIVER) {
            testDirectMotorDriver();
            return;
        }

        if (TEST_RCC_SUSTAINED_RESEND) {
            testRccSustainedResend();
            return;
        }

        Intent intent = new Intent(SERVICE_ACTION);
        intent.setPackage(SERVICE_PACKAGE);
        boolean bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
        Log.e(TAG, "bindService returned " + bound);
        setStatus("bindService=" + bound);
    }

    /** Mirrors DriveController exactly: one DirectMotorDriver, connect() once,
     * driveSustained(20, 0) every 500ms for 20s, then stop(). No WS, no ModeApp,
     * no ServiceExam -- isolates whether the stall lives in DirectMotorDriver's
     * own write path/keepalive threading, not just in its frame byte content
     * (already confirmed correct via ServiceExam's own frame-packing). */
    private void testDirectMotorDriver() {
        final DirectMotorDriver driver = new DirectMotorDriver();
        boolean connected = driver.connect();
        Log.e(TAG, "DirectMotorDriver.connect() = " + connected);
        setStatus("connect()=" + connected);
        if (!connected) {
            return;
        }
        // ONE-SHOT variant (toggle SEND_ONCE): sends driveSustained() exactly once and
        // relies on the MCU's own loop=1 to sustain it, matching how the earlier
        // sendExploreLinear() AIDL test (which worked continuously) actually behaved --
        // that test also only ever sent ONE frame. This build resends every 500ms like
        // DriveController does in production, which is the one combination not yet
        // tested: type=25 correct bytes, but resent repeatedly.
        final boolean SEND_ONCE = true;
        if (SEND_ONCE) {
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        driver.driveContinuous(20, 0);
                        Log.e(TAG, "DMD-TEST ONESHOT driveContinuous(20,0) sent t=" + System.currentTimeMillis());
                        setStatus("ONESHOT sent");
                    } catch (Exception e) {
                        Log.e(TAG, "DMD-TEST ONESHOT failed", e);
                    }
                }
            }, 1000);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    try {
                        driver.stop();
                        Log.e(TAG, "DMD-TEST ONESHOT stop() sent");
                        setStatus("DONE");
                    } catch (Exception e) {
                        Log.e(TAG, "DMD-TEST ONESHOT stop() failed", e);
                    }
                }
            }, 1000 + 20000L);
            return;
        }

        final int[] n = {0};
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (n[0] >= 40) {
                    try {
                        driver.stop();
                        Log.e(TAG, "DMD-TEST stop() sent");
                        setStatus("DONE");
                    } catch (Exception e) {
                        Log.e(TAG, "DMD-TEST stop() failed", e);
                    }
                    return;
                }
                try {
                    driver.driveContinuous(20, 0);
                    Log.e(TAG, "DMD-TEST driveContinuous(20,0) #" + n[0] + " t=" + System.currentTimeMillis());
                } catch (Exception e) {
                    Log.e(TAG, "DMD-TEST driveContinuous failed", e);
                }
                n[0]++;
                handler.postDelayed(this, 500);
            }
        };
        handler.postDelayed(tick, 1000);
    }

    /** Loads the real /system/lib64/libmiko_drivers.so and drives through its
     * actual native createUART/initUART/writeUART -- see emotix.com.drivers.
     * SensorModule's own class javadoc for why. Sends the same POWER keepalive
     * + type=25 kick+sustain VEL1 frame DirectMotorDriver builds, but written via
     * the vendor's own native uart_write()/write() call, not a Java FileOutputStream. */
    private void testSensorModuleUart() {
        final emotix.com.drivers.SensorModule sm = new emotix.com.drivers.SensorModule();
        boolean connected = sm.connectUart("/dev/ttyS2");
        // NOTE: ServiceExam's own SensorModule.init() calls initUART() and discards its
        // return value -- uart_start()'s 0-success return code maps to JNI_FALSE, so
        // "false" here does NOT mean failure. Proceed regardless, matching real usage.
        Log.e(TAG, "SensorModule.connectUart() = " + connected + " (return value is not meaningful, proceeding anyway)");
        setStatus("connectUart()=" + connected);
        final byte[] power = taggedFrame("POWER", 500);
        final byte[] stop = taggedFrame("MTSTP", 500);
        // CAVEAT (confirmed live, 2026-09-15): sustainedFrame() builds the TURN-style
        // two-frame kick+sustain shape (type=25/loop=1) -- sending it once with
        // linear=20, angular=0 confirmed clean UART communication (real POWER
        // telemetry replies, zero stalls, zero ERROR_UART) but did NOT move the
        // robot, because pure linear motion needs the completely different
        // frontContinous shape (type=1, loop=0, resent every tick -- see
        // DirectMotorDriver.buildContinuousFrame()'s own javadoc). This test is still
        // useful for verifying the UART transport itself is healthy; it does not by
        // itself prove a drive command's CONTENT is correct.
        final byte[] drive = sustainedFrame(20, 0);

        // Replicates SocialInteraction_SpeechChat.SendData()'s exact contract: every
        // write to the UART is `synchronized` on ONE lock (serializing POWER polls
        // against drive frames -- our earlier tests never did this), immediately
        // followed by a synchronous readUART() for the reply, with an explicit check
        // for the literal 10-byte "ERROR_UART" sentinel that triggers a real UART
        // reset (resetUART()+initUART()) before continuing. Nothing in this project's
        // testing up to now has EVER read a reply back or handled ERROR_UART -- if the
        // real protocol requires that per-write handshake to stay healthy, that fully
        // explains every earlier "write-only" attempt degrading after a while.
        final Object sendLock = new Object();
        final int[] errorUartCount = {0};
        class SendData {
            void send(byte[] frame, String label) {
                synchronized (sendLock) {
                    sm.write(frame);
                    byte[] reply = sm.read();
                    String replyStr = reply == null ? "null" : new String(reply, 0, Math.min(reply.length, 20));
                    if (reply != null && reply.length == 10 && new String(reply).equals("ERROR_UART")) {
                        errorUartCount[0]++;
                        Log.e(TAG, "SM-TEST " + label + ": ERROR_UART reply #" + errorUartCount[0] + " -- resetting UART");
                        sm.close();
                        sm.connectUart("/dev/ttyS2");
                        return;
                    }
                    Log.e(TAG, "SM-TEST " + label + " sent, reply(len=" + (reply == null ? -1 : reply.length) + ")=\"" + replyStr + "\" t=" + System.currentTimeMillis());
                }
            }
        }
        final SendData sendData = new SendData();

        final boolean[] keepaliveRunning = {true};
        new Thread(new Runnable() {
            @Override
            public void run() {
                while (keepaliveRunning[0]) {
                    sendData.send(power, "POWER");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        return;
                    }
                }
            }
        }, "sm-keepalive").start();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendData.send(drive, "DRIVE");
                setStatus("ONESHOT sent");
            }
        }, 1000);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                sendData.send(stop, "STOP");
                keepaliveRunning[0] = false;
                Log.e(TAG, "SM-TEST stop() sent, total ERROR_UART replies=" + errorUartCount[0]);
                setStatus("DONE, errorUartCount=" + errorUartCount[0]);
            }
        }, 1000 + 20000L);
    }

    private static byte[] taggedFrame(String tag, int size) {
        byte[] frame = new byte[size];
        byte[] tagBytes = tag.getBytes();
        System.arraycopy(tagBytes, 0, frame, 0, tagBytes.length);
        for (int i = tagBytes.length; i < size; i++) {
            frame[i] = 'X';
        }
        return frame;
    }

    private static int wU3(byte[] buf, int offset, int value) {
        buf[offset] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) (value & 0xFF);
        return offset + 3;
    }

    private static int wS3(byte[] buf, int offset, int value) {
        int magnitude = Math.abs(value);
        buf[offset] = (byte) ((magnitude >> 8) & 0xFF);
        buf[offset + 1] = (byte) (magnitude & 0xFF);
        buf[offset + 2] = (byte) (value > 0 ? 1 : (value < 0 ? 2 : 0));
        return offset + 3;
    }

    /** Byte-identical to DirectMotorDriver.buildSustainedFrame(). */
    private static byte[] sustainedFrame(int linear, int angular) {
        byte[] frame = new byte[100];
        int i = 0;
        frame[i++] = 'V';
        frame[i++] = 'E';
        frame[i++] = 'L';
        frame[i++] = '1';
        frame[i++] = '=';
        i = wU3(frame, i, 4);
        i = wU3(frame, i, 39);
        i = wU3(frame, i, 4);
        i = wU3(frame, i, 1);
        i = wU3(frame, i, 2);
        i = wS3(frame, i, linear);
        i = wS3(frame, i, angular);
        i = wU3(frame, i, 40);
        i = wU3(frame, i, 25);
        i = wS3(frame, i, linear);
        i = wS3(frame, i, angular);
        i = wU3(frame, i, 2);
        i = wU3(frame, i, 25);
        for (; i < 100; i++) {
            frame[i] = 'X';
        }
        return frame;
    }

    /** RobotControlClient.connect() + repeated driveSustained() every 500ms for
     * 20s, then stop() -- the one combination never actually tested: the
     * confirmed-working type=25 payload, resent the way production really would,
     * through ServiceExam's own GameEvent(135) AIDL call the whole time. */
    private void testRccSustainedResend() {
        final com.miko3.shared.RobotControlClient[] rccHolder = new com.miko3.shared.RobotControlClient[1];
        com.miko3.shared.RobotControlClient rcc0 = new com.miko3.shared.RobotControlClient(
                this, new com.miko3.shared.RobotControlClient.Listener() {
            @Override
            public void onConnected() {
                final com.miko3.shared.RobotControlClient rcc = rccHolder[0];
                Log.e(TAG, "RCC-TEST connected");
                setStatus("connected");
                final boolean SEND_ONCE = true;
                if (SEND_ONCE) {
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                rcc.driveSustained(2, 0);
                                Log.e(TAG, "RCC-TEST ONESHOT driveSustained(2,0) sent t=" + System.currentTimeMillis());
                                setStatus("ONESHOT sent");
                            } catch (Exception e) {
                                Log.e(TAG, "RCC-TEST ONESHOT failed", e);
                            }
                        }
                    }, 500);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                rcc.stop();
                                Log.e(TAG, "RCC-TEST ONESHOT stop() sent");
                                setStatus("DONE");
                            } catch (Exception e) {
                                Log.e(TAG, "RCC-TEST ONESHOT stop() failed", e);
                            }
                        }
                    }, 500 + 20000L);
                    return;
                }
                final int[] n = {0};
                final Runnable tick = new Runnable() {
                    @Override
                    public void run() {
                        if (n[0] >= 150) {
                            try {
                                rcc.stop();
                                Log.e(TAG, "RCC-TEST stop() sent");
                                setStatus("DONE");
                            } catch (Exception e) {
                                Log.e(TAG, "RCC-TEST stop() failed", e);
                            }
                            return;
                        }
                        try {
                            rcc.driveContinuous(2, 0);
                            Log.e(TAG, "RCC-TEST driveContinuous(2,0) #" + n[0] + " t=" + System.currentTimeMillis());
                        } catch (Exception e) {
                            Log.e(TAG, "RCC-TEST driveContinuous failed", e);
                        }
                        n[0]++;
                        handler.postDelayed(this, 100);
                    }
                };
                handler.postDelayed(tick, 500);
            }

            @Override
            public void onDisconnected() {
                Log.e(TAG, "RCC-TEST disconnected");
                setStatus("disconnected");
            }

            @Override
            public void onSendFailed(android.os.RemoteException e) {
                Log.e(TAG, "RCC-TEST send failed", e);
            }
        });
        rccHolder[0] = rcc0;
        rcc0.connect();
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
