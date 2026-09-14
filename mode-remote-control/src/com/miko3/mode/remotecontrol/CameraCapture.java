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

            StreamConfigurationMap map = characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size jpegSize = pickSmallestJpegSize(map);
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

    private Size pickSmallestJpegSize(StreamConfigurationMap map) {
        if (map == null) {
            return null;
        }
        Size[] sizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        List<Size> sorted = Arrays.asList(sizes);
        java.util.Collections.sort(sorted, new Comparator<Size>() {
            @Override
            public int compare(Size a, Size b) {
                return Integer.compare(a.getWidth() * a.getHeight(), b.getWidth() * b.getHeight());
            }
        });
        return sorted.get(0);
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
