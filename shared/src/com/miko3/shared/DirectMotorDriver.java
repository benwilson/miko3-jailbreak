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
    private static final byte[] POWER_FRAME = buildPowerFrame();

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

    /** Convenience: an explicit stop (zero linear, zero angular). */
    public void stop() throws IOException {
        drive(0, 0, 10);
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

    /** "POWER" + 0x58 padding to 500 bytes — see POWER_FRAME's own field comment. */
    private static byte[] buildPowerFrame() {
        byte[] frame = new byte[POWER_FRAME_SIZE];
        byte[] tag = "POWER".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(tag, 0, frame, 0, tag.length);
        for (int i = tag.length; i < POWER_FRAME_SIZE; i++) {
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
