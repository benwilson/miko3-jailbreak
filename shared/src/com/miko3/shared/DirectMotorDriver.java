package com.miko3.shared;

import android.os.SystemClock;
import android.util.Log;

import emotix.com.drivers.SensorModule;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Drives the robot's wheels by writing "VEL1=" motion frames directly to
 * /dev/ttyS2 — the same serial line ServiceExam's SensorModule/
 * libmiko_drivers.so JNI layer uses — bypassing ServiceExam's AIDL surface,
 * its ExpressionMsg/GameEvent(135) JSON round-trip, and the actual
 * motivation: a confirmed-from-source shared lock
 * (SocialInteraction_SpeechChat.SendData()'s own intrinsic monitor) that
 * ties every motor command to ServiceExam's speech recognition, TTS, and
 * cloud-network-call machinery. If ServiceExam is mid-conversation when a
 * drive command arrives via the AIDL path, that command blocks on the same
 * lock a network call might be holding — a real, structural cause of the
 * intermittent drive freezes RobotControlClient's AIDL path has shown.
 *
 * Frame format (100 bytes total) — verified directly against this
 * project's decompiled ServiceExam sources (tools/serviceexam_jadx), not
 * secondhand:
 * com.common_source.emotix.acattsandroidsdkdemo.MotionMsg#packbytes(),
 * com.emotix.arya.app_utils.ByteUtils#convertToBytes()/convertToBytesSigned(),
 * and com.common_source.emotix.interaction.interaction.ExpressionMsg
 * #packData1()'s "MOTION1"/"MOTION3" framing (both produce byte-identical
 * wire output for a normal, non-PID frame):
 *
 * <pre>
 *   "VEL1="        (5 ASCII bytes: 0x56 0x45 0x4C 0x31 0x3D)
 *   type=4         (3B unsigned, big-endian)   — MotionMsg's own "type", not the frame's
 *   datasize=27    (3B unsigned)                — total byte length of everything below,
 *                                                  including these first two fields; fixed
 *                                                  at 27 since this class always sends
 *                                                  exactly one frame (seqCount=1)
 *   motion_type=4  (3B unsigned)
 *   loop=1         (3B unsigned)
 *   seqCount=1     (3B unsigned)
 *   linear         (3B signed — see writeSigned3())
 *   angular        (3B signed — see writeSigned3())
 *   time           (3B unsigned, centiseconds)
 *   frame.type=1   (3B unsigned — "normal" velocity frame; MotionMsg.packbytes() also
 *                    supports a frame.type==5 "PID/config" layout this class does not
 *                    implement, since no caller in this project has ever used it)
 *   'X' (0x58) padding out to 100 bytes total
 * </pre>
 *
 * KNOWN, DISCLOSED RISK — concurrent access: /dev/ttyS2 is world-writable
 * (chmod 0666 by an OEM boot script) and, confirmed live in this project's
 * own testing, does NOT enforce exclusive-open — a second reader was able to
 * open it and successfully capture a live telemetry packet meant for
 * ServiceExam. ServiceExam keeps its own file descriptor to this device open
 * essentially the entire time it runs, and can write its own motion frames
 * independent of anything this class does (idle-mode movement, a
 * voice-triggered reaction). A raw serial line has no built-in arbitration:
 * if this class and ServiceExam happen to write at the same moment, the two
 * 100-byte frames can interleave at the byte level, corrupting whichever the
 * peripheral MCU ends up reading. This class does not attempt to solve that
 * — it's safest used only during an actively held drive session (mirroring
 * this project's existing DriveLeaseService model for the AIDL path), not
 * left open continuously alongside otherwise-normal robot operation.
 *
 * SINGLE READER: every write here is followed by a synchronous read of the
 * MCU's reply (see sendFrame()), and this class is the only reader of the device
 * node — a second reader steals replies this one is waiting for (confirmed live
 * against ServiceExam, which is now disabled). The replies to the POWER poll carry
 * the front ToF sensor's readings, which sendFrame() parses and publishes as
 * latestSensors() (docs/hardware/tof-sensor.md); anything that needs sensor data
 * reads it from here instead of opening the device again. Motor commands stay
 * fire-and-forget: callers get no per-command ack.
 *
 * REQUIRED KEEPALIVE — the actual missing piece, found via strace: a bare
 * VEL1 write alone does NOT move the wheels if ServiceExam has never
 * initialized the port this boot, and — more importantly — stops working
 * again within roughly a second of ServiceExam being stopped, even after it
 * successfully initialized things earlier. Attaching strace to ServiceExam's
 * PID while it ran showed it isn't just reading telemetry passively; it's
 * actively writing a 500-byte "POWER" + 0x58-padding poll frame (see
 * POWER_FRAME below) to this same fd roughly every 100ms, continuously, for
 * as long as it runs — and the MCU's own telemetry responses (the
 * "POWER=..." strings this class's own earlier sniffing captured) are
 * replies to that poll, not a free-running push. Confirmed live: with
 * ServiceExam fully disabled, sending nothing but our own periodic
 * POWER_FRAME poll plus a VEL1 drive frame moved the wheels; without the
 * poll running, the same VEL1 frame was silently ignored. This reads as a
 * host-liveness watchdog on the MCU's side (stop polling, the peripheral
 * assumes the host is gone and ignores motion commands) — connect() below
 * starts a background thread sending POWER_FRAME every KEEPALIVE_INTERVAL_MS
 * for as long as this instance stays connected, independent of ServiceExam.
 */
public final class DirectMotorDriver {
    private static final String TAG = "DirectMotorDriver";
    private static final String DEVICE_PATH = "/dev/ttyS2";
    private static final int FRAME_SIZE = 100;
    private static final int KEEPALIVE_INTERVAL_MS = 100; // matches ServiceExam's own observed cadence
    private static final int POWER_FRAME_SIZE = 500;

    /** "POWER" (5 ASCII bytes) + 0x58 padding to 500 bytes — no encoded payload beyond
     * the tag itself; confirmed via strace against ServiceExam's own write() calls. */
    private static final byte[] POWER_FRAME = buildTaggedFrame("POWER", POWER_FRAME_SIZE);

    /**
     * "MTSTP" (5 ASCII bytes) + 0x58 padding to 500 bytes — the REAL stop command.
     * Confirmed via decompiled-source research (2026-09-15) that a zero-velocity
     * "VEL1=" frame — this class's original stop() implementation — is NOT how
     * ServiceExam stops the motors: ServiceExam only ever emits "VEL1=" when it has
     * an actual nonzero motion sequence to run (ExpressionMsg.packData1() only packs
     * a frame when getSeqCount() > 0), routed through an asynchronous, queued
     * MOTION_PLAY path. Stopping is a completely separate, synchronous call —
     * SocialInteraction_SpeechChat.motionStop() — that writes this literal 500-byte
     * "MTSTP" buffer (serialDevice.generate500ByteData("MTSTP", 500)) straight to
     * /dev/ttyS2, bypassing the VEL1/motion-queue path entirely. Confirmed live: a
     * zero-velocity VEL1 frame let the MCU keep executing the last nonzero motion
     * INDEFINITELY until a new, different VEL1 frame arrived — sending this instead
     * is the actual fix, not a zero-velocity frame repeated more insistently.
     */
    private static final byte[] STOP_FRAME = buildTaggedFrame("MTSTP", POWER_FRAME_SIZE);

    /** "TOFEN" + 0x58 padding to 500 bytes: switches the front ToF sensor on, exactly as
     * SocialInteraction_SpeechChat.startTof() does (generate500ByteData("TOFEN", 500)).
     * ServiceExam sends the matching TOFDS on some boot and login paths, which would
     * leave the ToF reporting a stuck value; enableTof() undoes that. */
    private static final byte[] TOF_ENABLE_FRAME = buildTaggedFrame("TOFEN", POWER_FRAME_SIZE);

    private SensorModule sensorModule;
    private Thread keepaliveThread;
    private volatile boolean keepaliveRunning;

    /** The newest TOFIR reading from a POWER-poll reply, or null before the first one. */
    private volatile SensorSnapshot latestSensors;
    /** When a reply last carried CPL=2 (the MCU refusing forward motion), or 0 if never. */
    private volatile long lastRefusalMs;

    /** How long the keepalive waits before retrying after a failed write, doubling to the cap. */
    private static final int RECONNECT_BACKOFF_MS = 200;
    private static final int RECONNECT_BACKOFF_CAP_MS = 2000;

    /**
     * Opens the UART via SensorModule (JNI into the same system-installed
     * libmiko_drivers.so ServiceExam itself uses — see build-mode-remote-control.py's
     * own comment for how it gets bundled into this app's APK), NOT a bare
     * FileOutputStream on /dev/ttyS2.
     *
     * CONFIRMED LIVE (2026-09-15, via `strace -f` against a process calling
     * SensorModule.connectUart() directly) that this is not just a style
     * preference: opening the device node is only half the story. ServiceExam's own
     * native init does exactly two additional things a bare FileOutputStream never
     * does at all — `ioctl(fd, TCFLSH, TCIFLUSH)` (flush stale buffered bytes), then
     * `ioctl(fd, TCSETS, {B460800 -opost -isig -icanon -echo ...})`: sets the port to
     * **460800 baud, raw mode** (not 2,000,000 — that figure in this doc's own
     * earlier notes was for a DIFFERENT serial channel, the Arya/TransportLayer one,
     * not this VEL1/SensorModule motion channel). A plain FileOutputStream inherits
     * whatever baud/termios settings the device node was already left at by
     * whatever last configured it (kernel tty state, not per-fd) — if that's ever
     * anything other than 460800/raw, writes and reads desync at the byte level.
     * This fully explains this project's own confirmed-live "genuinely wedged
     * driving, no code-level bug, survives an app restart, a service restart, and
     * even a full device reboot" incident from earlier the same day: none of those
     * reset the tty's *termios* state, only SensorModule's own explicit TCSETS call
     * does. See docs/hardware/motors-wheels.md's "Open questions" #13 (now
     * resolved) for the full writeup.
     */
    public synchronized boolean connect() {
        try {
            sensorModule = new SensorModule();
            // Return value not meaningful (see SensorModule's own class comment,
            // matching ServiceExam's own SensorModule.init(): initUART()'s 0-success
            // C return maps to JNI_FALSE) — proceed regardless, matching real usage.
            sensorModule.connectUart(DEVICE_PATH);
            startKeepalive();
            return true;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "connect() failed -- libmiko_drivers.so not loadable", e);
            sensorModule = null;
            return false;
        }
    }

    /** See class javadoc's REQUIRED KEEPALIVE section — without this running
     * continuously, the MCU stops honoring drive() within about a second. */
    private void startKeepalive() {
        keepaliveRunning = true;
        keepaliveThread = new Thread(new Runnable() {
            @Override
            public void run() {
                int failures = 0;
                while (keepaliveRunning) {
                    try {
                        synchronized (DirectMotorDriver.this) {
                            if (sensorModule == null) {
                                break;
                            }
                            sendFrame(POWER_FRAME);
                        }
                        failures = 0;
                    } catch (IOException e) {
                        // A dead keepalive would leave isConnected() true with no sensor
                        // data and no motion honored, so reopen the port and keep going.
                        // Readers see the gap as a stale latestSensors() timestamp.
                        Log.w(TAG, "keepalive write failed -- reopening the UART", e);
                        if (!reopenUart()) {
                            break;
                        }
                        failures++;
                    }
                    try {
                        Thread.sleep(failures == 0 ? KEEPALIVE_INTERVAL_MS
                                : Math.min(RECONNECT_BACKOFF_CAP_MS, RECONNECT_BACKOFF_MS << Math.min(failures - 1, 4)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }, "direct-motor-keepalive");
        keepaliveThread.setDaemon(true);
        keepaliveThread.start();
    }

    public synchronized boolean isConnected() {
        return sensorModule != null;
    }

    /** Close and reopen the device after a failed keepalive write; false once
     * disconnect() has torn the driver down, which ends the keepalive loop. */
    private boolean reopenUart() {
        synchronized (this) {
            if (sensorModule == null || !keepaliveRunning) {
                return false;
            }
            sensorModule.close();
            sensorModule.connectUart(DEVICE_PATH);
            return true;
        }
    }

    /** Switches the front ToF sensor on (see TOF_ENABLE_FRAME). Safe to repeat. */
    public synchronized void enableTof() throws IOException {
        sendFrame(TOF_ENABLE_FRAME);
    }

    /** The newest front-sensor reading, or null before the first POWER reply. Callers
     * judge freshness from its timestampMs (SystemClock.elapsedRealtime()). */
    public SensorSnapshot latestSensors() {
        return latestSensors;
    }

    /** SystemClock.elapsedRealtime() of the last reply carrying CPL=2 (the MCU refused
     * forward motion for an edge/obstacle), or 0 if none has. */
    public long lastRefusalMs() {
        return lastRefusalMs;
    }

    /** The literal 10-byte reply SocialInteraction_SpeechChat.SendData() checks for
     * (confirmed from source) that signals the UART itself needs resetting — a
     * condition a write-only FileOutputStream had no way to ever detect at all. */
    private static final String ERROR_UART = "ERROR_UART";

    /** Debug-only capture of every raw MCU reply, off unless enabled with
     * `setprop log.tag.MikoDmdRaw DEBUG` (scripts/qa-explore-sensors.py does this).
     * Exists to document the reply format (docs/hardware/tof-sensor.md). */
    private static final String RAW_TAG = "MikoDmdRaw";

    /** One logcat line per reply: the sent frame's tag (e.g. POWER, VEL1=) and the
     * reply with its trailing 'X' padding trimmed, non-printables as '.'. */
    private static void logRawReply(byte[] frame, byte[] reply) {
        int tagEnd = 0;
        while (tagEnd < frame.length && tagEnd < 5 && frame[tagEnd] >= 'A' && frame[tagEnd] <= 'Z') {
            tagEnd++;
        }
        String sent = new String(frame, 0, tagEnd, StandardCharsets.US_ASCII);
        if (reply == null) {
            Log.d(RAW_TAG, "sent=" + sent + " reply=null");
            return;
        }
        int end = reply.length;
        while (end > 0 && reply[end - 1] == 'X') {
            end--;
        }
        StringBuilder text = new StringBuilder(end);
        for (int i = 0; i < end; i++) {
            byte b = reply[i];
            text.append(b >= 0x20 && b < 0x7f ? (char) b : '.');
        }
        Log.d(RAW_TAG, "sent=" + sent + " len=" + reply.length + " reply=" + text);
    }

    /** Every write to the UART is immediately followed by a synchronous read of the
     * MCU's reply, matching SocialInteraction_SpeechChat.SendData()'s own contract
     * exactly (confirmed via decompiled source AND live strace) — callers must hold
     * this instance's monitor already (every public method here is `synchronized`).
     * An ERROR_UART reply resets the port (close + reconnectUart, mirroring
     * SendData()'s own resetUART()+initUART() recovery) before returning, so a
     * transient UART-level fault self-heals on the very next call instead of
     * leaving every subsequent write silently degraded. */
    private void sendFrame(byte[] frame) throws IOException {
        if (sensorModule == null) {
            throw new IOException("not connected");
        }
        if (!sensorModule.write(frame)) {
            throw new IOException("SensorModule.write() failed");
        }
        byte[] reply = sensorModule.read();
        if (Log.isLoggable(RAW_TAG, Log.DEBUG)) {
            logRawReply(frame, reply);
        }
        if (reply != null) {
            long now = SystemClock.elapsedRealtime();
            if (frame == POWER_FRAME) {
                SensorSnapshot reading = SensorReply.parse(reply, now);
                if (reading != null) {
                    latestSensors = reading;
                }
            }
            if (SensorReply.parseCpl(reply) == 2) {
                lastRefusalMs = now;
            }
        }
        if (reply != null && reply.length == ERROR_UART.length()
                && new String(reply, StandardCharsets.US_ASCII).equals(ERROR_UART)) {
            Log.w(TAG, "ERROR_UART reply -- resetting UART");
            sensorModule.close();
            sensorModule.connectUart(DEVICE_PATH);
        }
    }

    /**
     * Fire-and-forget from the CALLER's perspective (matches RobotControlClient.
     * drive()'s own contract, and this project's own established convention — see
     * class javadoc's SINGLE READER note) even though sendFrame() reads a reply
     * internally: that read serves UART health (ERROR_UART) and the published sensor
     * readings, not a per-command application-level ack, so callers still don't get one.
     */
    public synchronized void drive(int linear, int angular, int timeCentiseconds) throws IOException {
        sendFrame(buildFrame(linear, angular, timeCentiseconds));
    }

    /**
     * The held-TURN command — matches ServiceExam's own TeleConnect held-drive
     * feature's leftContinous_new/rightContinous_new exactly (type=24, loop=1,
     * two-frame kick=25/sustain=8): self-sustains once sent, does not need
     * resending. Only the SIGN of angular is used (fixed vendor magnitudes, not
     * scaled) — see buildTurnFrame()'s own javadoc. Do not use this for linear
     * motion (forward/back) — see driveContinuous() below for why that needs a
     * completely different shape.
     */
    public synchronized void driveTurnSustained(int angular) throws IOException {
        sendFrame(buildTurnFrame(angular > 0));
    }

    /**
     * The held-LINEAR-drive command (forward/back) — matches ServiceExam's own
     * TeleConnect held-drive feature's frontContinous exactly (type=1, loop=0,
     * single frame, time=10): does NOT self-sustain, MUST be resent every tick
     * (U19j, 2026-09-15's own confirmed-live magnitude of 2 — see
     * buildContinuousFrame()'s own javadoc for the full story of why the
     * earlier type=25/loop=1 "Explore" shape looked right in short isolated
     * tests but degraded under real repeated use, and why this is the actual
     * correct recipe instead). Callers must resend this periodically (this
     * project's own DriveSection.java uses 250ms) for as long as the direction
     * is held, and call stop() on release.
     */
    public synchronized void driveContinuous(int linear, int angular) throws IOException {
        sendFrame(buildContinuousFrame(linear, angular));
    }

    /** The real stop command — see STOP_FRAME's own field comment for why this is
     * NOT drive(0, 0, ...): a zero-velocity VEL1 frame is not how ServiceExam stops
     * the motors and, confirmed live, does not reliably stop them here either. */
    public synchronized void stop() throws IOException {
        sendFrame(STOP_FRAME);
    }

    public void disconnect() {
        // Stops the keepalive thread's loop and waits for it to actually exit BEFORE
        // tearing down sensorModule below — it reads that same field under this
        // instance's monitor each iteration, so signaling it to stop without waiting
        // could otherwise race a concurrent close() out from under its next write().
        keepaliveRunning = false;
        Thread t = keepaliveThread;
        if (t != null) {
            try {
                t.join(KEEPALIVE_INTERVAL_MS * 2L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }
        synchronized (this) {
            if (sensorModule != null) {
                sensorModule.close();
            }
            sensorModule = null;
        }
        keepaliveThread = null;
    }

    /** ASCII tag + 0x58 padding to the given total size — the shape both POWER_FRAME
     * and STOP_FRAME share (see their own field comments), matching
     * serialDevice.generate500ByteData()'s own construction. */
    private static byte[] buildTaggedFrame(String tag, int size) {
        byte[] frame = new byte[size];
        byte[] tagBytes = tag.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tagBytes, 0, frame, 0, tagBytes.length);
        for (int i = tagBytes.length; i < size; i++) {
            frame[i] = 'X';
        }
        return frame;
    }

    /** Package-private (not private) so a unit test can call it directly without a device. */
    static byte[] buildFrame(int linear, int angular, int timeCentiseconds) {
        byte[] frame = new byte[FRAME_SIZE];
        int i = 0;
        frame[i++] = 'V';
        frame[i++] = 'E';
        frame[i++] = 'L';
        frame[i++] = '1';
        frame[i++] = '=';
        i = writeUnsigned3(frame, i, 4);  // MotionMsg's own "type"
        i = writeUnsigned3(frame, i, 27); // datasize -- fixed: single-frame blob is always 27 bytes
        i = writeUnsigned3(frame, i, 4);  // motion_type
        i = writeUnsigned3(frame, i, 1);  // loop
        i = writeUnsigned3(frame, i, 1);  // seqCount -- this class only ever sends one frame
        i = writeSigned3(frame, i, linear);
        i = writeSigned3(frame, i, angular);
        i = writeUnsigned3(frame, i, timeCentiseconds);
        i = writeUnsigned3(frame, i, 1);  // frame.type=1 ("normal" velocity frame)
        for (; i < FRAME_SIZE; i++) {
            frame[i] = 'X'; // 0x58 padding, matching ExpressionMsg.packData1()'s own fill byte
        }
        return frame;
    }

    /**
     * A two-frame VEL1 sequence for TURNING ONLY, byte-for-byte matching
     * ServiceExam's TeleConnect.leftContinous_new/rightContinous_new (U19k,
     * 2026-09-15) — found by pulling and decompiling this robot's actual
     * companion phone app (com.miko.mikoplus) and cross-referencing
     * ServiceClientInterface.java's own playExpressionMap constants: type=24,
     * loop=1, seqCount=2, kick angular=±25 (50ms) + sustain angular=±8 (100ms),
     * linear always 0. Magnitudes are FIXED vendor constants, not scaled by the
     * caller's own angular value — only its sign selects left vs. right.
     *
     * This session earlier guessed a DIFFERENT shape for turning AND forward
     * alike (type=25/loop=1, symmetric magnitude, times 40/2 — the vendor's
     * AUTONOMOUS idle-wandering recipe from AutoMode/Explore/Linear.txt, not
     * any held-drive feature). That guess happened to test fine for turning in
     * isolation but was never the real recipe; forward degraded badly under
     * real repeated use with it (see driveContinuous()/buildContinuousFrame()'s
     * own javadoc for the full story and why forward needed a completely
     * different fix). Turning has stayed reliable throughout using it, but this
     * is the actual vendor-confirmed shape now that it's been found — matching
     * it exactly is safer long-term than continuing on the shape that happened
     * to work.
     */
    static byte[] buildTurnFrame(boolean positive) {
        byte[] frame = new byte[FRAME_SIZE];
        int i = 0;
        frame[i++] = 'V';
        frame[i++] = 'E';
        frame[i++] = 'L';
        frame[i++] = '1';
        frame[i++] = '=';
        i = writeUnsigned3(frame, i, 4);  // MotionMsg's own "type"
        i = writeUnsigned3(frame, i, 39); // datasize -- 15-byte header + 2x12-byte frames
        i = writeUnsigned3(frame, i, 4);  // motion_type
        i = writeUnsigned3(frame, i, 1);  // loop -- self-sustains, matches leftContinous_new
        i = writeUnsigned3(frame, i, 2);  // seqCount -- two frames
        int kick = positive ? 25 : -25;
        int sustain = positive ? 8 : -8;
        // Frame 1: the kick (50ms).
        i = writeSigned3(frame, i, 0);
        i = writeSigned3(frame, i, kick);
        i = writeUnsigned3(frame, i, 5);
        i = writeUnsigned3(frame, i, 24); // frame.type=24 -- matches leftContinous_new/rightContinous_new
        // Frame 2: the sustain (100ms).
        i = writeSigned3(frame, i, 0);
        i = writeSigned3(frame, i, sustain);
        i = writeUnsigned3(frame, i, 10);
        i = writeUnsigned3(frame, i, 24);
        for (; i < FRAME_SIZE; i++) {
            frame[i] = 'X';
        }
        return frame;
    }

    /**
     * A single VEL1 frame for LINEAR DRIVE ONLY (forward/back), byte-for-byte
     * matching ServiceExam's TeleConnect.frontContinous (U19j, 2026-09-15) —
     * type=1, loop=0, seqCount=1, time=10 (100ms), magnitude 2. Found the same
     * way as buildTurnFrame() above.
     *
     * loop=0 means this does NOT self-sustain — it must be resent by the
     * caller for as long as the direction is held (driveContinuous()'s own
     * javadoc has the calling contract). This is the OPPOSITE of
     * buildTurnFrame()'s loop=1 self-sustaining shape, and that asymmetry is
     * real, not an oversight: there is no "backContinous"/frontContinous
     * loop=1 variant anywhere in the vendor source at all. Confirmed live
     * (encoder/GLPOS telemetry, not just a CPL=1 ack) that resending THIS
     * exact frame every 250ms, entirely outside ServiceExam's own process (raw
     * /dev/ttyS2 writes via scripts/bypass-drive-test.py --shape single, with
     * both mode-remote-control and ServiceExam force-stopped), drives
     * continuously and reliably. The earlier type=25/loop=1 "Explore"
     * (autonomous idle-wandering) shape was WRONG for this direction — it
     * looked reliable in short isolated tests (including sent once through
     * this same raw-serial path) but consistently degraded into a lurch/
     * freeze cycle under real repeated production use, on both this
     * class's own write path AND ServiceExam's own GameEvent() AIDL call.
     * Do not go back to a self-sustaining loop=1 shape for forward/back
     * without a live encoder-telemetry test across many real repeated holds,
     * not just one or two short ones — that is exactly the kind of test that
     * validated the wrong shape before.
     */
    static byte[] buildContinuousFrame(int linear, int angular) {
        byte[] frame = new byte[FRAME_SIZE];
        int i = 0;
        frame[i++] = 'V';
        frame[i++] = 'E';
        frame[i++] = 'L';
        frame[i++] = '1';
        frame[i++] = '=';
        i = writeUnsigned3(frame, i, 4);  // MotionMsg's own "type"
        i = writeUnsigned3(frame, i, 27); // datasize -- 15-byte header + 1x12-byte frame
        i = writeUnsigned3(frame, i, 4);  // motion_type
        i = writeUnsigned3(frame, i, 0);  // loop=0 -- does NOT self-sustain, see this method's own javadoc
        i = writeUnsigned3(frame, i, 1);  // seqCount -- single frame
        i = writeSigned3(frame, i, linear);
        i = writeSigned3(frame, i, angular);
        i = writeUnsigned3(frame, i, 10); // time=10 (100ms) -- matches frontContinous
        i = writeUnsigned3(frame, i, 1);  // frame.type=1 -- matches frontContinous
        for (; i < FRAME_SIZE; i++) {
            frame[i] = 'X';
        }
        return frame;
    }

    /** Matches com.emotix.arya.app_utils.ByteUtils#convertToBytes(int): plain 3-byte
     * big-endian, for the small non-negative values (type/datasize/loop/etc.) this
     * class ever sends. */
    private static int writeUnsigned3(byte[] buf, int offset, int value) {
        buf[offset] = (byte) ((value >> 16) & 0xFF);
        buf[offset + 1] = (byte) ((value >> 8) & 0xFF);
        buf[offset + 2] = (byte) (value & 0xFF);
        return offset + 3;
    }

    /** Matches com.emotix.arya.app_utils.ByteUtils#convertToBytesSigned(int): NOT
     * two's complement — 2 big-endian magnitude bytes followed by a trailing sign
     * byte (1=positive, 2=negative, 0=zero). Confirmed directly from that method's
     * decompiled source, including for the linear/angular magnitude range
     * (well under 32768) this class actually uses. */
    private static int writeSigned3(byte[] buf, int offset, int value) {
        int magnitude = Math.abs(value);
        buf[offset] = (byte) ((magnitude >> 8) & 0xFF);
        buf[offset + 1] = (byte) (magnitude & 0xFF);
        buf[offset + 2] = (byte) (value > 0 ? 1 : (value < 0 ? 2 : 0));
        return offset + 3;
    }
}
