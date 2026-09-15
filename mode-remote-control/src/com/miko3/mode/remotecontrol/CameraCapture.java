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
        /** Fired once per start(), the first time a real frame actually arrives (see
         * onImageAvailable's own comment on why this signal, not session
         * configuration succeeding, is what "working" has to mean here) — lets a
         * caller doing its own retry-with-backoff (ModeApp.startCamera()) know when
         * to reset its attempt counter back to zero. */
        void onCameraReady();
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
    private volatile boolean readyReported;
    // Manual exposure/ISO (U19s, 2026-09-15): see MANUAL_EXPOSURE_NS's own comment
    // for why this exists instead of the variable-FPS-range approach it replaces.
    private static final long MANUAL_EXPOSURE_NS = 140_000_000L; // 140ms
    private static final int MANUAL_SENSITIVITY = 1600;
    private android.util.Range<Long> exposureTimeRange;
    private android.util.Range<Integer> sensitivityRange;
    private boolean manualExposureSupported;

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
            sensitivityRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
            exposureTimeRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            Log.i(TAG, "AE compensation range=" + aeCompensationRange
                    + " step=" + characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
                    + " sensitivityRange=" + sensitivityRange
                    + " exposureTimeRange=" + exposureTimeRange
                    + " hasFlash=" + characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE));
            // See MANUAL_EXPOSURE_NS's own comment: only attempt manual AE_MODE_OFF
            // control if this camera actually lists it as available -- confirmed live
            // via `dumpsys media.camera` this device's android.control.aeAvailableModes
            // is [0, 1] (OFF, ON), so it does, but do not assume that's true for every
            // unit without checking.
            int[] aeAvailableModes = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
            manualExposureSupported = exposureTimeRange != null && sensitivityRange != null
                    && aeAvailableModes != null && contains(aeAvailableModes, CaptureRequest.CONTROL_AE_MODE_OFF);
            Log.i(TAG, "manual exposure supported=" + manualExposureSupported);

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

    private static boolean contains(int[] haystack, int needle) {
        for (int v : haystack) {
            if (v == needle) {
                return true;
            }
        }
        return false;
    }

    private static long clamp(long value, android.util.Range<Long> range) {
        return Math.max(range.getLower(), Math.min(range.getUpper(), value));
    }

    private static int clamp(int value, android.util.Range<Integer> range) {
        return Math.max(range.getLower(), Math.min(range.getUpper(), value));
    }

    /** REVERTED (2026-09-15, U19q) back to always picking a FIXED range (lowest
     * floor, for the most exposure headroom a fixed rate allows) after a brief
     * detour through preferring a variable range earlier the same day.
     *
     * That variable-range attempt was a real brightness win when it worked (a
     * fixed [15,15] target hard-caps every frame's exposure TIME at ~66ms
     * regardless of scene brightness -- CONTROL_AE_TARGET_FPS_RANGE bounds frame
     * duration, which bounds max exposure time -- capping this sensor at ~60ms/
     * ISO 213 even though it supports up to 400ms/ISO 6400; a variable range like
     * [5, 30] lets auto-exposure slow toward the floor in a dark scene instead).
     * But confirmed live, later the same day: repeated open/close cycles of the
     * camera with that [5, 30] range eventually wedge this device's vendor camera
     * HAL into a full failure -- camerahalserver spins forever logging
     * "MtkCam/HalSensor: CMD_SENSOR_GET_PIXEL_RATE: pixel rate should not be 0" /
     * "SeninfDrvImp: pixel rate should not be zero", pegging a full CPU core, and
     * every subsequent capture session fails with CAMERA_ERROR ("Error
     * configuring streams: Broken pipe"). This is the SAME chronic vendor bug
     * CAMERA_ENABLED's own comment already documents (a pixel-rate computation
     * the HAL gets wrong under some capture-parameter combination) -- now
     * narrowed down to specifically a variable/non-fixed CONTROL_AE_TARGET_FPS_
     * RANGE as (at least one) trigger. It does NOT clear on its own: neither
     * restarting cameraserver/camerahalserver nor a full device reboot fixed it
     * while this app kept requesting the same variable range on every retry --
     * confirmed fixed by going back to a fixed range instead.
     *
     * Net effect: back to the dimmer ~60ms/ISO 213 ceiling until a fixed-range
     * approach to the brightness problem is found (e.g. explicitly forcing
     * SENSOR_EXPOSURE_TIME/SENSOR_SENSITIVITY higher via manual AE control
     * instead of relying on CONTROL_AE_TARGET_FPS_RANGE to unlock it) -- a
     * reliably-working dim camera beats an unreliable bright one. Do not go back
     * to preferring a variable range here without first confirming LIVE, across
     * many repeated open/close cycles (not just one), that this specific vendor
     * HAL bug is actually gone. */
    private android.util.Range<Integer> pickLowFpsRange(CameraCharacteristics characteristics) {
        android.util.Range<Integer>[] ranges = characteristics.get(
                CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        Log.i(TAG, "available FPS ranges: " + Arrays.toString(ranges));
        android.util.Range<Integer> bestFixed = ranges[0];
        for (android.util.Range<Integer> r : ranges) {
            if (r.getLower().equals(r.getUpper()) && r.getLower() < bestFixed.getLower()) {
                bestFixed = r;
            }
        }
        return bestFixed;
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
            // Confirmed live (2026-09-15): this fires when another app (e.g. the stock
            // camera app) takes the device out from under an already-open session --
            // previously left the stream dead with no signal at all to ModeApp's own
            // retry-with-backoff, since only onError() (a different callback) reported
            // failures. Camera2 only delivers onDisconnected() for this kind of
            // framework-initiated revocation, never for stop()'s own direct
            // cameraDevice.close() (that path only ever reaches onClosed()), so this
            // can't turn an intentional stop() into a spurious retry.
            errorListener.onCameraError("camera disconnected");
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
                if (manualExposureSupported) {
                    // MANUAL_EXPOSURE_NS (U19s, 2026-09-15): forcing exposure/ISO directly
                    // instead of widening CONTROL_AE_TARGET_FPS_RANGE (this camera's own
                    // pickLowFpsRange()'s earlier approach to the same "looks dark"
                    // problem) — that approach got the same brightness but reliably wedges
                    // this device's vendor HAL into a full failure that survives even a
                    // reboot (see pickLowFpsRange()'s own comment). CONTROL_AE_TARGET_
                    // FPS_RANGE is documented as IGNORED once CONTROL_AE_MODE is OFF, so
                    // the fixed range set above no longer bounds anything here — frame
                    // timing is instead whatever SENSOR_FRAME_DURATION says below, fully
                    // decoupled from the buggy variable-FPS-range path. 140ms/ISO 1600 is
                    // a fixed middle-ground pick (roughly matching what auto-exposure
                    // itself converged to under the now-abandoned variable-range
                    // approach), not adaptive — this scene will over/underexpose if
                    // lighting changes a lot, which a real auto-exposure loop would not,
                    // but a static correct-for-typical-indoor-light image beats an
                    // unreliable camera. SENSOR_FRAME_DURATION must be >= the exposure
                    // time (Camera2 requirement) — set equal, the minimum that's valid.
                    long exposureNs = clamp(MANUAL_EXPOSURE_NS, exposureTimeRange);
                    int sensitivity = clamp(MANUAL_SENSITIVITY, sensitivityRange);
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
                    builder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureNs);
                    builder.set(CaptureRequest.SENSOR_SENSITIVITY, sensitivity);
                    builder.set(CaptureRequest.SENSOR_FRAME_DURATION, exposureNs);
                    Log.i(TAG, "manual exposure=" + exposureNs + "ns sensitivity=" + sensitivity);
                } else {
                    // Fallback for a camera that doesn't list CONTROL_AE_MODE_OFF as
                    // available: auto-exposure plus max compensation (this camera's own
                    // CONTROL_AE_COMPENSATION_RANGE is -4..4 in 1/2 EV steps, so -2EV..
                    // +2EV) — dimmer than manual control can achieve, but the least-bad
                    // option without manual control. TEMPLATE_PREVIEW already implies
                    // CONTROL_AE_MODE_ON, so this is only setting it explicitly for
                    // clarity; compensation stacks on top of whatever auto-exposure picks.
                    builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                    if (aeCompensationRange != null) {
                        builder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, aeCompensationRange.getUpper());
                    }
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
                // Confirmed live (2026-09-15): the vendor HAL's chronic "pixel rate
                // should not be zero" bug (see pickLowFpsRange()'s own comment) can leave
                // a capture session reporting successful configuration while never
                // actually producing a frame — onConfigured() succeeding is NOT a
                // reliable "camera is actually working" signal on this device.  A real
                // frame arriving here is the only signal that actually is, so this is
                // where ModeApp's own retry-with-backoff resets its attempt counter.
                if (!readyReported) {
                    readyReported = true;
                    errorListener.onCameraReady();
                }
            } finally {
                image.close();
            }
        }
    };
}
