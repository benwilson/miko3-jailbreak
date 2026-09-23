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
 * (docs/hardware/camera-vision.md). Unlike remote-control it leaves exposure
 * on auto: its manual exposure washed out U1's test frame.
 *
 * Frames that arrive while the recognizer is busy (about a second a frame,
 * U1) are dropped, so every result is from a frame captured after the one
 * before it. The recognizer is loaded on first use and kept for the session.
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

    private volatile boolean busy;
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
    }

    @Override
    public boolean available() {
        return permitted && !recognizerFailed;
    }

    @Override
    public void open() {
        generation++;
        latest = null;
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
        generation++;
        latest = null;
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                wanted = false;
                closeNow();
            }
        });
    }

    @Override
    public ExploreBrain.Look latest() {
        return latest;
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
                    startSession(fps);
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

    private void startSession(final Range<Integer> fps) {
        try {
            List<android.view.Surface> surfaces = Arrays.asList(reader.getSurface());
            device.createCaptureSession(surfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession s) {
                    if (device == null) {
                        return;
                    }
                    session = s;
                    try {
                        CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                        b.addTarget(reader.getSurface());
                        if (fps != null) {
                            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps);
                        }
                        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                        s.setRepeatingRequest(b.build(), null, cameraHandler);
                        Log.i(TAG, "camera streaming at " + fps);
                    } catch (CameraAccessException | IllegalStateException e) {
                        Log.e(TAG, "setRepeatingRequest failed", e);
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
                recognize(jpeg, clock.nowMs(), generation);
            } finally {
                image.close();
            }
        }
    };

    // ---- detect thread ----

    private void recognize(final byte[] jpeg, final long frameMs, final int gen) {
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
                        latest = new ExploreBrain.Look(frameMs, found);
                        Log.i(TAG, "look in " + (clock.nowMs() - t0) + " ms: " + found);
                    }
                } catch (Exception | OutOfMemoryError | LinkageError e) {
                    Log.e(TAG, "recognition failed", e);
                } finally {
                    busy = false;
                }
            }
        });
    }

    // ---- selection, as remote-control's CameraCapture ----

    /** The lowest fixed range: a variable one wedges this HAL (camera-vision.md). */
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
