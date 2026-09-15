package com.miko3.shared;

import android.util.Log;

import java.io.FileOutputStream;
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
 * WRITE-ONLY BY DESIGN: this class never reads from the device. ServiceExam
 * continuously receives MCU telemetry (~10Hz "PI3 callback" strings —
 * POWER=/IMUAC=/IMUGY=/GLPOS=/TOFIR=/Left=/Right=) over this same line; a
 * second reader steals bytes ServiceExam needs, with no way to give them
 * back (confirmed live). Motor commands are fire-and-forget from this
 * class's side, same contract as the existing AIDL-based
 * RobotControlClient, which also never inspects the MCU's ack.
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

    private FileOutputStream out;
    private Thread keepaliveThread;
    private volatile boolean keepaliveRunning;

    /**
     * Opens the UART device directly — no su, no shell, no subprocess at all.
     * Confirmed live this session that root was never actually required here:
     * /dev/ttyS2 is chmod 0666 (world read/write) and this device's SELinux
     * policy is permissive (denials logged, never enforced), so a plain
     * FileOutputStream from this app's own unprivileged process opens and
     * writes to it exactly as successfully as "adb shell cat > /dev/ttyS2"
     * does with no su involved. An earlier version of this class spent a
     * long detour trying to get su to elevate from inside the app process
     * (ProcessBuilder("su"), then the full path "/system/bin/su", matching
     * bootagent/RootOps.java's own documented pattern) before empirically
     * confirming, via a bare "adb shell" write with no su prefix, that su
     * was solving a problem that didn't exist for this specific device node.
     */
    public synchronized boolean connect() {
        try {
            out = new FileOutputStream(DEVICE_PATH);
            startKeepalive();
            return true;
        } catch (IOException e) {
            Log.e(TAG, "connect() failed", e);
            disconnect();
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
                while (keepaliveRunning) {
                    try {
                        synchronized (DirectMotorDriver.this) {
                            if (out == null) {
                                break;
                            }
                            out.write(POWER_FRAME);
                            out.flush();
                        }
                    } catch (IOException e) {
                        Log.w(TAG, "keepalive write failed", e);
                        break;
                    }
                    try {
                        Thread.sleep(KEEPALIVE_INTERVAL_MS);
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
        return out != null;
    }

    /**
     * Fire-and-forget, matching RobotControlClient.drive()'s own contract —
     * see class javadoc's WRITE-ONLY note for why no ack is read back.
     */
    public synchronized void drive(int linear, int angular, int timeCentiseconds) throws IOException {
        if (out == null) {
            throw new IOException("not connected");
        }
        out.write(buildFrame(linear, angular, timeCentiseconds));
        out.flush();
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
        if (out == null) {
            throw new IOException("not connected");
        }
        out.write(buildTurnFrame(angular > 0));
        out.flush();
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
        if (out == null) {
            throw new IOException("not connected");
        }
        out.write(buildContinuousFrame(linear, angular));
        out.flush();
    }

    /** The real stop command — see STOP_FRAME's own field comment for why this is
     * NOT drive(0, 0, ...): a zero-velocity VEL1 frame is not how ServiceExam stops
     * the motors and, confirmed live, does not reliably stop them here either. */
    public synchronized void stop() throws IOException {
        if (out == null) {
            throw new IOException("not connected");
        }
        out.write(STOP_FRAME);
        out.flush();
    }

    public void disconnect() {
        // Stops the keepalive thread's loop and waits for it to actually exit BEFORE
        // tearing down out below — it reads that same field under this instance's
        // monitor each iteration, so signaling it to stop without waiting could
        // otherwise race a concurrent close() out from under its next write().
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
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ignored) {
            }
            out = null;
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
