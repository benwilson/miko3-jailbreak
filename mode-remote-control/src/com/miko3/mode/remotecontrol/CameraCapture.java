package com.miko3.mode.remotecontrol;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
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
import android.util.Log;
import android.util.Size;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Opens Camera2 with one hardware-JPEG ImageReader (KTD4: ImageFormat.JPEG
 * directly, not YUV_420_888 + software compression, a known CPU cost on
 * this SoC's quad Cortex-A35 cores) and pushes each frame to the given
 * MjpegBroadcaster. One capture session for the whole mode session,
 * regardless of how many MJPEG viewers are attached.
 */
final class CameraCapture {
    private static final String TAG = "CameraCapture";

    interface ErrorListener {
        void onCameraError(String reason);
    }

    private final Context context;
    private final MjpegBroadcaster broadcaster;
    private final ErrorListener errorListener;

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    // Signaled by onClosed()/onDisconnected()/onError() — stop() waits on this so a
    // caller's very next start() (ModeApp.startCamera() releases-then-reopens on
    // every call) never races the still-in-flight async close() with a fresh
    // openCamera(), which is a confirmed-live source of intermittent open failures
    // (Camera2's close() only *starts* teardown; the HAL isn't actually free until
    // the corresponding state callback fires).
    private volatile CountDownLatch closedLatch;
    // Confirmed live: with no target FPS set, this session ran at the camera's
    // default (likely native max, e.g. 30fps) rate — measured via `top`, this
    // process alone was using ~90% of a quad-core SoC's total CPU capacity, and
    // system-wide CPU idle jumped from 8% to 84% the moment this app was stopped.
    // That contention is a direct, plausible cause of the reported inconsistent
    // drive-command latency ("sometimes works, sometimes janky"): an MJPEG preview
    // doesn't need 30fps to look reasonable, so start() picks the slowest
    // available range (>=10fps if one exists) and onConfigured() applies it.
    private android.util.Range<Integer> targetFpsRange;
    private android.util.Range<Integer> aeCompensationRange;

    CameraCapture(Context context, MjpegBroadcaster broadcaster, ErrorListener errorListener) {
        this.context = context.getApplicationContext();
        this.broadcaster = broadcaster;
        this.errorListener = errorListener;
    }

    void start() {
        // Armed before any early return: stop()'s wait needs a countDown() on every
        // path, including the ones below that never actually call openCamera() (no
        // state callback will ever fire for those, so nothing else would signal it).
        closedLatch = new CountDownLatch(1);
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            errorListener.onCameraError("CAMERA permission not granted");
            closedLatch.countDown();
            return;
        }
        backgroundThread = new HandlerThread("camera-capture");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());

        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            String cameraId = pickCameraId(manager);
            if (cameraId == null) {
                errorListener.onCameraError("no camera found");
                closedLatch.countDown();
                return;
            }
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);
            Integer hwLevel = characteristics.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
            Log.i(TAG, "camera " + cameraId + " INFO_SUPPORTED_HARDWARE_LEVEL=" + hwLevel);

            targetFpsRange = pickLowFpsRange(characteristics);
            Log.i(TAG, "using target FPS range " + targetFpsRange);

            // Confirmed live (2026-09-15): this camera exposes JPEG, PRIVATE (opaque,
            // not readable), and two raw YUV formats (YUV_420_888, YV12) but no
            // hardware video codec (h.264/etc.) at all -- JPEG via the hardware
            // encoder (this class's own original KTD4 choice, see class javadoc) is
            // already the most efficient encode path available on this SoC, not
            // something to move away from for "better" streaming. The real lever is
            // resolution: confirmed 9 available JPEG sizes from 320x240 up to
            // 2560x1920, with 320x240 originally picked as the smallest specifically
            // to minimize WiFi bandwidth contention with the drive-command WebSocket
            // (see this method's own pickSmallestJpegSize()). aeCompensationRange is
            // queried here (not hardcoded) since it's a real per-device camera
            // characteristic, used below to brighten the image.
            aeCompensationRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            Log.i(TAG, "AE compensation range=" + aeCompensationRange
                    + " step=" + characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    + " sensitivityRange=" + characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
                    + " exposureTimeRange=" + characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
                    + " hasFlash=" + characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));

            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size jpegSize = pickTargetJpegSize(map);
            if (jpegSize == null) {
                errorListener.onCameraError("no JPEG output size available");
                closedLatch.countDown();
                return;
            }
            Log.i(TAG, "using JPEG size " + jpegSize.getWidth() + "x" + jpegSize.getHeight());

            imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(),
                    android.graphics.ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(onImageAvailable, backgroundHandler);

            // closedLatch counts down only in onClosed() from here — that's the sole
            // point Camera2 guarantees the HAL has actually released the device.
            manager.openCamera(cameraId, cameraStateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "openCamera failed", e);
            errorListener.onCameraError("camera access failed: " + e.getMessage());
            closedLatch.countDown();
        } catch (SecurityException e) {
            Log.e(TAG, "openCamera denied", e);
            errorListener.onCameraError("camera permission denied at open time");
            closedLatch.countDown();
        }
    }

    void stop() {
        if (captureSession != null) {
            try {
                captureSession.close();
            } catch (Exception ignored) {
            }
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        // Waits for onClosed() (see closedLatch's field comment) BEFORE quitting the
        // background thread below — onClosed() is delivered on that thread's own
        // Handler, so quitting it first would silently drop the very callback this
        // wait depends on, defeating the wait entirely. A bounded timeout means a
        // wedged HAL delays, but never hangs, the next start().
        CountDownLatch latch = closedLatch;
        if (latch != null) {
            try {
                if (!latch.await(2, TimeUnit.SECONDS)) {
                    Log.w(TAG, "camera close did not confirm within 2s — proceeding anyway");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            try {
                backgroundThread.join();
            } catch (InterruptedException ignored) {
            }
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private String pickCameraId(CameraManager manager) throws CameraAccessException {
        String[] ids = manager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    /** REVISED (2026-09-15) to prioritize a genuinely VARIABLE range (lower < upper)
     * with the lowest floor, over a fixed rate — the original version here picked
     * the fixed [15,15] range specifically to bound bandwidth, without realizing
     * that choice also hard-caps every frame's exposure TIME at ~66ms regardless of
     * scene brightness (CONTROL_AE_TARGET_FPS_RANGE bounds frame duration, which
     * bounds max exposure time), which is what an operator-reported "looks dark"
     * traced back to: this camera's sensor supports up to 400ms exposure and ISO
     * 6400, but a fixed 15fps target never let auto-exposure use more than ~60ms/
     * ISO 213 no matter how much CONTROL_AE_EXPOSURE_COMPENSATION was requested.
     * A variable range (confirmed available on this camera: [5, 30]) lets
     * auto-exposure slow down toward the floor automatically in a dark scene
     * (more light per frame, no fixed frame-rate tradeoff needed) while still
     * running fast in good light — strictly better for this problem than any fixed
     * rate. Falls back to the fixed range with the lowest floor (most exposure
     * headroom) if no variable range is offered, or null (camera default) if
     * nothing is reported. */
    private android.util.Range<Integer> pickLowFpsRange(CameraCharacteristics characteristics) {
        android.util.Range<Integer>[] ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        Log.i(TAG, "available FPS ranges: " + Arrays.toString(ranges));
        android.util.Range<Integer> bestVariable = null;
        android.util.Range<Integer> bestFixed = ranges[0];
        for (android.util.Range<Integer> r : ranges) {
            if (r.getLower().equals(r.getUpper())) {
                if (r.getLower() < bestFixed.getLower()) {
                    bestFixed = r;
                }
            } else if (bestVariable == null || r.getLower() < bestVariable.getLower()) {
                bestVariable = r;
            }
        }
        return bestVariable != null ? bestVariable : bestFixed;
    }

    /** TARGET_JPEG_WIDTH x TARGET_JPEG_HEIGHT is deliberately NOT this camera's
     * smallest available JPEG size (320x240, confirmed live to be the smallest of
     * 9 sizes up to 2560x1920) — that choice, made when this stream first shipped,
     * traded away most of the picture quality headroom to minimize WiFi bandwidth
     * contention with the drive-command WebSocket, before anyone had actually
     * measured whether a step up was safe. 640x480 (4x the pixels) is the next
     * confirmed-available size up; test drive responsiveness and measured
     * frame/bandwidth under this size live before going any higher, the same way
     * every other empirical choice in this project was validated. */
    private static final int TARGET_JPEG_WIDTH = 640;
    private static final int TARGET_JPEG_HEIGHT = 480;

    private Size pickTargetJpegSize(StreamConfigurationMap map) {
        if (map == null) {
            return null;
        }
        Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        StringBuilder sb = new StringBuilder("available JPEG sizes:");
        for (Size s : sizes) {
            sb.append(' ').append(s.getWidth()).append('x').append(s.getHeight());
        }
        Log.i(TAG, sb.toString());
        for (Size s : sizes) {
            if (s.getWidth() == TARGET_JPEG_WIDTH && s.getHeight() == TARGET_JPEG_HEIGHT) {
                return s;
            }
        }
        // Exact target not offered by this camera -- fall back to the smallest size
        // at or above the target pixel count, or the overall smallest if the target
        // exceeds everything this camera offers.
        List<Size> sorted = Arrays.asList(sizes);
        java.util.Collections.sort(sorted, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                return Integer.compare(a.getWidth() * a.getHeight(), b.getWidth() * b.getHeight());
            }
        });
        int targetPixels = TARGET_JPEG_WIDTH * TARGET_JPEG_HEIGHT;
        for (Size s : sorted) {
            if (s.getWidth() * s.getHeight() >= targetPixels) {
                return s;
            }
        }
        return sorted.get(sorted.size() - 1);
    }

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            try {
                List<android.view.Surface> surfaces = Arrays.asList(imageReader.getSurface());
                camera.createCaptureSession(surfaces, captureSessionCallback, backgroundHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "createCaptureSession failed", e);
                errorListener.onCameraError("failed to start capture session: " + e.getMessage());
            }
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            Log.w(TAG, "camera disconnected");
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            Log.e(TAG, "camera error " + error);
            camera.close();
            cameraDevice = null;
            errorListener.onCameraError("camera error code " + error);
        }

        @Override
        public void onClosed(CameraDevice camera) {
            // The one point Camera2 guarantees the HAL has actually released this
            // device — see closedLatch's field comment. Fires after close() from
            // any of onDisconnected/onError/stop()'s own call, so this is the sole
            // place that counts it down.
            CountDownLatch latch = closedLatch;
            if (latch != null) {
                latch.countDown();
            }
        }
    };

    private final CameraCaptureSession.StateCallback captureSessionCallback =
            new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            captureSession = session;
            try {
                CaptureRequest.Builder builder =
                        cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                builder.addTarget(imageReader.getSurface());
                if (targetFpsRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFpsRange);
                }
                // Operator-reported (2026-09-15): the stream looked dark with no
                // exposure compensation set at all (auto-exposure default only).
                // Confirmed live this camera's own CONTROL_AE_COMPENSATION_RANGE is
                // -4..4 in CONTROL_AE_COMPENSATION_STEP=1/2 EV units (so -2EV..+2EV) --
                // request the top of that range (brightest available) rather than a
                // hardcoded guess, so this still does something sane on hardware with a
                // different range. TEMPLATE_PREVIEW already implies CONTROL_AE_MODE_ON
                // (auto-exposure enabled), so compensation stacks on top of whatever the
                // auto-exposure algorithm itself picks, not a replacement for it.
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                if (aeCompensationRange != null) {
                    builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeCompensationRange.getUpper());
                }
                session.setRepeatingRequest(builder.build(), null, backgroundHandler);
            } catch (CameraAccessException e) {
                Log.e(TAG, "setRepeatingRequest failed", e);
                errorListener.onCameraError("failed to start streaming: " + e.getMessage());
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            Log.e(TAG, "capture session configuration failed");
            errorListener.onCameraError("capture session configuration failed");
        }
    };

    private final ImageReader.OnImageAvailableListener onImageAvailable =
            new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            if (image == null) {
                return;
            }
            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] jpeg = new byte[buffer.remaining()];
                buffer.get(jpeg);
                broadcaster.publishFrame(jpeg);
            } finally {
                image.close();
            }
        }
    };
}
