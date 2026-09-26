package com.miko3.mode.explore;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Range;
import android.util.Size;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Explore's camera, open only during curiosity (camera curiosity KTD3, R2),
 * with the recognizer run on each frame it can keep up with. The brain calls
 * open() and close(), which only post to the camera thread and return at once,
 * and polls latest() for the newest recognized frame.
 *
 * Adapted from remote-control's CameraCapture (a copy, not a shared refactor):
 * one hardware-JPEG ImageReader at 640x480, and a FIXED frame-rate range,
 * since a variable one trips this HAL's "pixel rate should not be zero" bug
 * (docs/hardware/camera-vision.md). Exposure is set by hand (explore nav
 * plan U9, KTD10): remote-control's fixed manual exposure washed out U1's test
 * frame, and auto exposure at its largest compensation left real frames
 * near-black in office light, so Brightness steers exposure time and
 * sensitivity from each scored frame's mean luma, capped short while he
 * drives. The request is re-issued only when they change, on the camera
 * thread, and each open starts from the last settings that worked. A camera
 * that does not list CONTROL_AE_MODE_OFF keeps auto exposure with the largest
 * compensation. The frame-rate range stays the fixed one either way.
 *
 * Frames that arrive while the recognizer is busy (about a second a frame,
 * U1) are dropped, so every result is from a frame captured after the one
 * before it. The recognizer is loaded on first use and kept for the session.
 *
 * Each look also carries an openness profile (explore nav plan U3, KTD3): the
 * kept JPEG is decoded a second time at a quarter scale, the whole frame
 * averaged down again and the floor band below the horizon kept at that
 * sharper scale, and scored by Openness with the look's boxes. Only the
 * scoring time is logged, never pixels or profiles (R15). The brain's
 * floor-clear flag rides with each captured frame so Openness learns the
 * floor only from frames he could safely drive onto.
 */
final class ExploreCamera implements ExploreBrain.Camera {
    private static final String TAG = "ExploreCamera";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    /**
     * How long the HAL gets after a close before the next open. Reopening ~50 ms
     * after the previous device disconnected made this HAL hang configureStreams
     * for 10 s (mtkcam-dev3 err -110) and report an AEE exception, so that stop got
     * no look and curiosity went off (seen on the robot with back-to-back stops).
     */
    private static final long REOPEN_GAP_MS = 3000;
    /**
     * The owner's openness gate check (explore nav plan U3): with
     * log.tag.MikoExploreNavDebug=DEBUG, the last scored look's JPEG and its
     * profile numbers go to this app's private files directory (last-nav.jpg,
     * last-nav.txt), overwritten each time. Never shared storage, never the log.
     */
    static final String NAV_DEBUG_TAG = "MikoExploreNavDebug";
    static final String LAST_NAV = "last-nav.jpg";
    static final String LAST_NAV_PROFILE = "last-nav.txt";
    /** The openness decode: a quarter of the camera's 640x480 (160x120). */
    private static final int OPENNESS_SAMPLE = 4;

    /** Builds the recognizer on the detect thread, the first time a frame needs it. */
    interface RecognizerFactory {
        Recognizer create() throws Exception;
    }

    private final Context context;
    private final ExploreBrain.Clock clock;
    private final RecognizerFactory factory;
    private final boolean permitted;

    private final HandlerThread cameraThread = new HandlerThread("explore-camera");
    private final HandlerThread detectThread = new HandlerThread("explore-detect");
    private final Handler cameraHandler;
    private final Handler detectHandler;

    // Camera-thread state.
    private CameraDevice device;
    private CameraCaptureSession session;
    private ImageReader reader;
    private boolean wanted;
    /** An openCamera() whose callback has not come yet. */
    private boolean opening;
    /** When the last device finished closing (elapsedRealtime), for REOPEN_GAP_MS. */
    private long closedAtMs = Long.MIN_VALUE / 2;
    /**
     * When a device close was requested and onClosed() has not come yet, else
     * NOT_CLOSING. device is nulled at the request, so without this an open
     * arriving in between would go straight through (seen: 16 ms after).
     */
    private long closingSinceMs = NOT_CLOSING;
    private static final long NOT_CLOSING = Long.MIN_VALUE;
    /** Stop waiting for an onClosed() that never comes. */
    private static final long CLOSE_WAIT_MAX_MS = 5000;
    /** Counted down once the device is really closed, for release(). */
    private volatile CountDownLatch closed;

    // Detect-thread state.
    private Recognizer recognizer;
    private volatile boolean recognizerFailed;
    private final BitmapFactory.Options decode = new BitmapFactory.Options();
    private final BitmapFactory.Options small = new BitmapFactory.Options();
    private int[] smallPixels = new int[0];
    /** halve()'s output, reused frame to frame (Openness keeps no pixels). */
    private int[] halfPixels = new int[0];
    /** Holds the floor model; used on the detect thread (the brain's calls are posted there). */
    private final Openness openness = new Openness();
    /** The brain's latest "floor clear and wheels free" (U3), stamped on each captured frame. */
    private volatile boolean floorClear;
    /** Mean luma of the last openness decode (detect thread); NaN when it failed. */
    private double decodedLuma = Double.NaN;

    /** Manual exposure for the session (U9); outlives each open, so the next starts where this left off. */
    private final Brightness brightness = new Brightness();
    /** Set per open on the camera thread: the camera lists CONTROL_AE_MODE_OFF and both ranges. */
    private volatile boolean manualExposure;
    /** The brain's latest "moving", so a repeat call costs nothing. */
    private volatile Boolean moving;
    // Camera-thread state for re-issuing the repeating request.
    private Range<Integer> sessionFps;
    private int sessionEv;

    private volatile boolean busy;
    /** close() has run on the camera thread and open() has not been called since. */
    private volatile boolean closedDone = true;
    private volatile ExploreBrain.Look latest;
    /** Bumped on every open and close; a result from an older generation is dropped. */
    private volatile int generation;

    ExploreCamera(Context context, ExploreBrain.Clock clock, RecognizerFactory factory) {
        this.context = context.getApplicationContext();
        this.clock = clock;
        this.factory = factory;
        this.permitted = this.context.checkSelfPermission(Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED;
        if (!permitted) {
            Log.w(TAG, "CAMERA permission not granted; curiosity is off");
        }
        cameraThread.start();
        detectThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
        detectHandler = new Handler(detectThread.getLooper());
        decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
        decode.inMutable = true;
        small.inPreferredConfig = Bitmap.Config.ARGB_8888;
        small.inMutable = true;
        small.inSampleSize = OPENNESS_SAMPLE;
    }

    @Override
    public boolean available() {
        return permitted && !recognizerFailed;
    }

    @Override
    public void open() {
        generation++;
        latest = null;
        closedDone = false;
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                wanted = true;
                openIfWanted.run();
            }
        });
    }

    /** Camera thread: open now, or once REOPEN_GAP_MS has passed since the last close. */
    private final Runnable openIfWanted = new Runnable() {
        @Override
        public void run() {
            if (!wanted || device != null || opening) {
                return;
            }
            long now = SystemClock.elapsedRealtime();
            if (closingSinceMs != NOT_CLOSING && now - closingSinceMs < CLOSE_WAIT_MAX_MS) {
                // onClosed() runs this again; the timeout is only a backstop.
                cameraHandler.removeCallbacks(this);
                cameraHandler.postDelayed(this, closingSinceMs + CLOSE_WAIT_MAX_MS - now);
                return;
            }
            // Normally from onClosed(); from the close request if that never came.
            long settledSince = closingSinceMs == NOT_CLOSING ? closedAtMs : closingSinceMs;
            long wait = settledSince + REOPEN_GAP_MS - now;
            if (wait > 0) {
                cameraHandler.removeCallbacks(this);
                cameraHandler.postDelayed(this, wait);
                return;
            }
            openNow();
        }
    };

    @Override
    public void close() {
        final int gen = ++generation;
        latest = null;
        closedDone = false;
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                wanted = false;
                closeNow();
                if (gen == generation) {
                    closedDone = true;
                }
            }
        });
    }

    /** Closed on the camera thread, and no detector run in flight (the brain speaks only then, R6). */
    @Override
    public boolean quiet() {
        return closedDone && !busy;
    }

    @Override
    public ExploreBrain.Look latest() {
        return latest;
    }

    /** A turn to false is a hazard or stall: drop the floor patches seen up to now. */
    @Override
    public void setFloorClear(final long nowMs, boolean clearAndFree) {
        boolean was = floorClear;
        floorClear = clearAndFree;
        if (was && !clearAndFree) {
            detectHandler.post(new Runnable() {
                @Override
                public void run() {
                    openness.floorHazard(nowMs);
                }
            });
        }
    }

    /** Driving caps the exposure short against blur (U9); a change is applied on the camera thread. */
    @Override
    public void setMoving(boolean moving) {
        Boolean was = this.moving;
        if (was != null && was == moving) {
            return;
        }
        this.moving = moving;
        if (brightness.setMoving(clock.nowMs(), moving) != null && manualExposure) {
            cameraHandler.post(applyExposure);
        }
    }

    @Override
    public void floorDrivenOver(final long throughFrameMs) {
        detectHandler.post(new Runnable() {
            @Override
            public void run() {
                openness.floorDrivenOver(throughFrameMs);
            }
        });
    }

    /**
     * Exit: close the camera and wait (bounded) until Camera2 confirms it, then
     * release the recognizer and both threads. As remote-control's CameraCapture:
     * onClosed() arrives on the camera thread, so it must outlive the close, and
     * a device still owned when the mode relaunches makes the next open fail.
     */
    void release() {
        generation++;
        latest = null;
        final CountDownLatch done = new CountDownLatch(1);
        closed = done;
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                wanted = false;
                boolean pending = device != null || opening;
                closeNow();
                if (!pending) {
                    done.countDown();
                }
                // Otherwise onClosed() counts it down, including for an open still
                // in flight, which onOpened() closes because wanted is false.
            }
        });
        await(done);
        cameraThread.quitSafely();
        detectHandler.post(new Runnable() {
            @Override
            public void run() {
                if (recognizer != null) {
                    recognizer.close();
                    recognizer = null;
                }
            }
        });
        detectThread.quitSafely();
        try {
            // An in-flight look takes up to ~2 s; don't start a second session over it.
            detectThread.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(2, TimeUnit.SECONDS)) {
                Log.w(TAG, "camera close not confirmed within 2 s; releasing anyway");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- camera thread ----

    private void openNow() {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] ids = manager.getCameraIdList();
            if (ids.length == 0) {
                Log.w(TAG, "no camera");
                return;
            }
            CameraCharacteristics c = manager.getCameraCharacteristics(ids[0]);
            final Range<Integer> fps = fixedFpsRange(c);
            final int ev = maxCompensation(c);
            manualExposure = manualExposureRanges(c);
            Size size = jpegSize(c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP));
            reader = ImageReader.newInstance(size.getWidth(), size.getHeight(), android.graphics.ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(onImage, cameraHandler);
            opening = true;
            manager.openCamera(ids[0], new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    opening = false;
                    if (!wanted) {
                        closeDevice(camera);
                        return;
                    }
                    device = camera;
                    startSession(fps, ev);
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    Log.w(TAG, "camera disconnected");
                    opening = false;
                    closeDevice(camera);
                    device = null;
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    // The brain notices no looks arriving and backs curiosity off (KTD8).
                    Log.e(TAG, "camera error " + error);
                    opening = false;
                    closeDevice(camera);
                    device = null;
                }

                @Override
                public void onClosed(CameraDevice camera) {
                    closedAtMs = SystemClock.elapsedRealtime();
                    closingSinceMs = NOT_CLOSING;
                    cameraHandler.removeCallbacks(openIfWanted);
                    openIfWanted.run();
                    CountDownLatch latch = closed;
                    if (latch != null) {
                        latch.countDown();
                    }
                }
            }, cameraHandler);
        } catch (CameraAccessException | SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "camera open failed", e);
            opening = false;
            closeNow();
        }
    }

    private void startSession(final Range<Integer> fps, final int ev) {
        sessionFps = fps;
        sessionEv = ev;
        try {
            List<android.view.Surface> surfaces = Arrays.asList(reader.getSurface());
            device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (device == null) {
                        return;
                    }
                    session = s;
                    Brightness.Settings manual = manualExposure ? brightness.start(clock.nowMs()) : null;
                    if (repeat(manual)) {
                        if (manual != null) {
                            logSettings(manual, fps);
                        } else {
                            Log.i(TAG, "camera streaming at " + fps + ", exposure compensation " + ev);
                        }
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession s) {
                    Log.e(TAG, "capture session configuration failed");
                }
            }, cameraHandler);
        } catch (CameraAccessException | IllegalStateException e) {
            Log.e(TAG, "createCaptureSession failed", e);
        }
    }

    /** Camera thread: re-issue the repeating request with Brightness's current settings. */
    private final Runnable applyExposure = new Runnable() {
        @Override
        public void run() {
            if (!manualExposure || session == null || device == null) {
                return;
            }
            Brightness.Settings s = brightness.current();
            if (repeat(s)) {
                logSettings(s, null);
            }
        }
    };

    /**
     * Camera thread: the one repeating request, with the fixed frame-rate range and
     * either these manual settings (AE off, as remote-control's CameraCapture) or,
     * when null, auto exposure at the largest compensation. False when it failed.
     */
    private boolean repeat(Brightness.Settings manual) {
        try {
            CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(reader.getSurface());
            if (sessionFps != null) {
                b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, sessionFps);
            }
            if (manual != null) {
                // SENSOR_FRAME_DURATION must be >= the exposure; Brightness sees to it.
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, manual.exposureNs);
                b.set(CaptureRequest.SENSOR_SENSITIVITY, manual.sensitivity);
                b.set(CaptureRequest.SENSOR_FRAME_DURATION, manual.frameDurationNs);
            } else {
                b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, sessionEv);
            }
            session.setRepeatingRequest(b.build(), null, cameraHandler);
            return true;
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "setRepeatingRequest failed", e);
            return false;
        }
    }

    /** Only the numbers, and only when they change (R15). */
    private static void logSettings(Brightness.Settings s, Range<Integer> fps) {
        Log.i(TAG, (fps != null ? "camera streaming at " + fps + ", " : "") + "exposure " + s.exposureMs()
                + " ms, ISO " + s.sensitivity);
    }

    /** Every device close goes through here, so the reopen waits for onClosed(). */
    private void closeDevice(CameraDevice camera) {
        closingSinceMs = SystemClock.elapsedRealtime();
        camera.close();
    }

    private void closeNow() {
        cameraHandler.removeCallbacks(openIfWanted);
        if (session != null) {
            try {
                session.close();
            } catch (IllegalStateException ignored) {
                // Already closed with its device.
            }
            session = null;
        }
        if (device != null) {
            closeDevice(device);
            device = null;
        }
        if (reader != null) {
            reader.close();
            reader = null;
        }
    }

    private final ImageReader.OnImageAvailableListener onImage = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader r) {
            Image image = r.acquireLatestImage();
            if (image == null) {
                return;
            }
            try {
                if (busy || !wanted) {
                    return;
                }
                ByteBuffer buf = image.getPlanes()[0].getBuffer();
                byte[] jpeg = new byte[buf.remaining()];
                buf.get(jpeg);
                busy = true;
                recognize(jpeg, clock.nowMs(), generation, floorClear);
            } finally {
                image.close();
            }
        }
    };

    // ---- detect thread ----

    private void recognize(final byte[] jpeg, final long frameMs, final int gen, final boolean teachable) {
        detectHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (recognizer == null && !recognizerFailed) {
                        long t0 = clock.nowMs();
                        try {
                            recognizer = factory.create();
                            Log.i(TAG, "recognizer ready in " + (clock.nowMs() - t0) + " ms");
                        } catch (Throwable e) {
                            // Throwable: a native library that fails to load throws an Error.
                            Log.e(TAG, "recognizer failed to load; curiosity is off", e);
                            recognizerFailed = true;
                        }
                    }
                    if (recognizer == null || gen != generation) {
                        return;
                    }
                    Bitmap frame = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, decode);
                    if (frame == null) {
                        return;
                    }
                    decode.inBitmap = frame;
                    long t0 = clock.nowMs();
                    List<Detection> found = recognizer.detect(frame);
                    if (gen == generation) {
                        Log.i(TAG, "look in " + (clock.nowMs() - t0) + " ms: " + found);
                        long s0 = clock.nowMs();
                        Openness.Profile profile = scoreOpenness(jpeg, found, frameMs, teachable);
                        Log.i(TAG, "openness in " + (clock.nowMs() - s0) + " ms");
                        adjustBrightness(frameMs, gen);
                        if (gen == generation) {
                            // The JPEG rides along for Claude's look request (explore on Claude U4, R1).
                            latest = new ExploreBrain.Look(frameMs, found, jpeg, profile);
                            debugNav(jpeg, profile);
                        }
                    }
                } catch (Exception | OutOfMemoryError | LinkageError e) {
                    Log.e(TAG, "recognition failed", e);
                } finally {
                    busy = false;
                }
            }
        });
    }

    /**
     * Detect thread: the frame again at a quarter scale, as a whole frame averaged
     * down to an eighth and the floor band below the horizon kept at a quarter,
     * scored with the look's boxes. Null when it can't be decoded.
     */
    private Openness.Profile scoreOpenness(byte[] jpeg, List<Detection> found, long frameMs, boolean teachable) {
        decodedLuma = Double.NaN;
        try {
            Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, small);
            if (bmp == null) {
                return null;
            }
            small.inBitmap = bmp;
            int w = bmp.getWidth();
            int h = bmp.getHeight();
            if (smallPixels.length != w * h) {
                smallPixels = new int[w * h];
            }
            bmp.getPixels(smallPixels, 0, w, 0, 0, w, h);
            decodedLuma = Brightness.meanLuma(smallPixels, w * h);
            int first = Math.min(h - 1, (int) (Openness.HORIZON * h));
            Openness.Frame floorBand = new Openness.Frame(smallPixels, w, h - first, (float) first / h, 1f, first);
            return openness.score(halve(smallPixels, w, h), floorBand, found, frameMs, teachable);
        } catch (RuntimeException | OutOfMemoryError e) {
            Log.w(TAG, "openness decode failed: " + e.getClass().getSimpleName());
            small.inBitmap = null;
            decodedLuma = Double.NaN;
            return null;
        }
    }

    /**
     * Detect thread: feed the decoded frame's brightness to the controller (U9) and,
     * when the settings change, re-issue the request on the camera thread. A frame
     * whose openness decode failed is skipped.
     */
    private void adjustBrightness(long frameMs, int gen) {
        if (!manualExposure || gen != generation || Double.isNaN(decodedLuma)) {
            return;
        }
        if (brightness.onFrame(frameMs, clock.nowMs(), decodedLuma) != null) {
            cameraHandler.post(applyExposure);
        }
    }

    /** The whole frame at half the given scale, each pixel the mean of a 2x2 block (detect thread). */
    private Openness.Frame halve(int[] px, int w, int h) {
        int hw = Math.max(1, w / 2);
        int hh = Math.max(1, h / 2);
        if (halfPixels.length != hw * hh) {
            halfPixels = new int[hw * hh];
        }
        int[] out = halfPixels;
        for (int y = 0; y < hh; y++) {
            for (int x = 0; x < hw; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                int n = 0;
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        int sx = Math.min(w - 1, x * 2 + dx);
                        int sy = Math.min(h - 1, y * 2 + dy);
                        int p = px[sy * w + sx];
                        r += (p >> 16) & 0xff;
                        g += (p >> 8) & 0xff;
                        b += p & 0xff;
                        n++;
                    }
                }
                out[y * hw + x] = ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
        }
        return new Openness.Frame(out, hw, hh, 0f, 1f);
    }

    /** The owner's gate check (NAV_DEBUG_TAG): the look and its numbers, private files only. */
    private void debugNav(byte[] jpeg, Openness.Profile profile) {
        if (profile == null || !Log.isLoggable(NAV_DEBUG_TAG, Log.DEBUG)) {
            return;
        }
        File dir = context.getFilesDir();
        write(new File(dir, LAST_NAV), jpeg);
        write(new File(dir, LAST_NAV_PROFILE),
                (profile.toString() + "\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static void write(File f, byte[] bytes) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(f);
            out.write(bytes);
        } catch (IOException e) {
            Log.w(NAV_DEBUG_TAG, "could not write " + f.getName() + ": " + e.getMessage());
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (IOException ignored) {
                    // Nothing more to do for a debug file.
                }
            }
        }
    }

    // ---- selection, as remote-control's CameraCapture ----

    /** The lowest fixed range: a variable one wedges this HAL (camera-vision.md). */
    /** The largest exposure-compensation step the camera offers, or 0 if none. */
    private static int maxCompensation(CameraCharacteristics c) {
        Range<Integer> range = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        return range == null ? 0 : Math.max(0, range.getUpper());
    }

    /**
     * Manual exposure is offered (as remote-control's CameraCapture): AE_MODE_OFF is
     * listed and both ranges are reported. Hands the ranges to Brightness.
     */
    private boolean manualExposureRanges(CameraCharacteristics c) {
        Range<Long> exposure = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        Range<Integer> sensitivity = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        int[] modes = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        boolean off = false;
        if (modes != null) {
            for (int m : modes) {
                off |= m == CaptureRequest.CONTROL_AE_MODE_OFF;
            }
        }
        if (!off || exposure == null || sensitivity == null) {
            return false;
        }
        brightness.setRanges(exposure.getLower(), exposure.getUpper(), sensitivity.getLower(),
                sensitivity.getUpper(), c.get(CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION));
        return true;
    }

    private static Range<Integer> fixedFpsRange(CameraCharacteristics c) {
        Range<Integer>[] ranges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        Range<Integer> best = null;
        for (Range<Integer> r : ranges) {
            if (r.getLower().equals(r.getUpper()) && (best == null || r.getLower() < best.getLower())) {
                best = r;
            }
        }
        return best != null ? best : ranges[0];
    }

    /** 640x480 when offered, else the smallest size at least that large, else the largest. */
    private static Size jpegSize(StreamConfigurationMap map) {
        Size[] sizes = map == null ? null : map.getOutputSizes(android.graphics.ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            return new Size(WIDTH, HEIGHT);
        }
        Size best = null;
        Size largest = sizes[0];
        for (Size s : sizes) {
            if (s.getWidth() == WIDTH && s.getHeight() == HEIGHT) {
                return s;
            }
            long px = (long) s.getWidth() * s.getHeight();
            if (px > (long) largest.getWidth() * largest.getHeight()) {
                largest = s;
            }
            if (px >= (long) WIDTH * HEIGHT
                    && (best == null || px < (long) best.getWidth() * best.getHeight())) {
                best = s;
            }
        }
        return best != null ? best : largest;
    }
}
