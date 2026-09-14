package com.miko3.mode.remotecontrol;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.miko3.shared.LauncherProtocol;

/**
 * Full-screen WebView shell hosting the mode's own served page (R11).
 * Launched by the launcher via explicit Intent, not HOME. Requests the
 * CAMERA/RECORD_AUDIO runtime permissions U6/U8's capture code needs,
 * matching how launcher/MainActivity.java already requests
 * ACCESS_FINE_LOCATION — a denial is reported, not silently retried.
 *
 * Owns the drive lease for its whole lifetime (U7): acquired in
 * onCreate, released in onDestroy — not tied to onResume/onPause like the
 * camera, since briefly backgrounding (e.g. a notification shade) should
 * not drop control the way an explicit exit does.
 *
 * launchMode="singleTop" (see AndroidManifest.xml) plus onNewIntent()
 * below is U10's launcher-to-mode exit request (EXTRA_FORCE_EXIT): the
 * launcher re-delivers an Intent to this already-running Activity rather
 * than killing its process, and this runs the exact same release/finish
 * path as the operator's own "Exit mode" button.
 *
 * No lambdas/method references, matching the rest of this repo — see
 * launcher/README.md for why.
 */
public class MainActivity extends Activity {
    private static final String TAG = "ModeMainActivity";
    private static final int REQ_PERMISSIONS = 2001;

    private WebView webView;
    private DriveController driveController;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logWebViewCapability();

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDomStorageEnabled(true);
        webView.addJavascriptInterface(new NativeCaptureBridge(this), "AndroidCapture");
        setContentView(webView);

        requestRuntimePermissionsThenLoad();
        activateDriveController();

        if (getIntent() != null && getIntent().getBooleanExtra(LauncherProtocol.EXTRA_FORCE_EXIT, false)) {
            exitMode();
        }
    }

    /** Creates and starts a fresh DriveController, acquiring the lease. Called from
     * onCreate (a genuinely new instance) and, defensively, from onNewIntent (see
     * there) if this instance was reused while not currently active. */
    private void activateDriveController() {
        String clientId = "mode-" + android.os.Process.myPid() + "-" + System.currentTimeMillis();
        driveController = new DriveController(this, clientId, new DriveController.ErrorListener() {
            @Override
            public void onDriveError(final String reason) {
                Log.e(TAG, "drive error: " + reason);
            }
        });
        driveController.start();
        ModeApp app = (ModeApp) getApplication();
        app.setDriveController(driveController);
        app.setExitRunnable(new Runnable() {
            @Override
            public void run() {
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        exitMode();
                    }
                });
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.getBooleanExtra(LauncherProtocol.EXTRA_FORCE_EXIT, false)) {
            exitMode();
        } else if (driveController == null) {
            // A plain (non-exit) launch Intent redelivered to this already-existing
            // singleTop instance instead of creating a fresh one — happens if this
            // instance is still mid-teardown from an earlier exit request when the
            // launcher's next launch Intent arrives. Reactivate in place rather than
            // silently doing nothing: recreate the drive controller and reload the
            // page so this instance ends up in the same state a truly fresh launch
            // would have produced.
            Log.i(TAG, "reactivating in place (redelivered launch intent, not currently active)");
            activateDriveController();
            webView.loadUrl("http://127.0.0.1:" + ModeApp.PORT + "/");
        }
    }

    private void exitMode() {
        if (driveController != null) {
            driveController.release();
        }
        ModeApp app = (ModeApp) getApplication();
        app.setDriveController(null);
        // Releases the shared port pair (U11: launcher and this mode now serve on
        // the same PORT/HTTPS_PORT) so LauncherApp.MainActivity's onResume() can
        // rebind them once this mode's Activity finishes and the launcher's own
        // Activity comes back to the foreground.
        app.stopServer();
        finish();
    }

    @Override
    protected void onDestroy() {
        if (driveController != null) {
            driveController.release();
        }
        ((ModeApp) getApplication()).setDriveController(null);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        ((ModeApp) getApplication()).startCamera();
    }

    @Override
    protected void onPause() {
        ((ModeApp) getApplication()).stopCamera();
        super.onPause();
    }

    private void logWebViewCapability() {
        PackageInfo wv = WebView.getCurrentWebViewPackage();
        if (wv != null) {
            Log.i(TAG, "WebView provider: " + wv.packageName + " versionName=" + wv.versionName);
        } else {
            Log.w(TAG, "WebView.getCurrentWebViewPackage() returned null");
        }
    }

    private void requestRuntimePermissionsThenLoad() {
        boolean needCamera = checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED;
        boolean needAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED;
        if (needCamera || needAudio) {
            requestPermissions(new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO},
                    REQ_PERMISSIONS);
        }
        // Load regardless of the outcome — a denial is reported by U6/U8's own
        // capture-error path when their capture actually fails to start, matching
        // how U6's camera-open-failure handling works, rather than blocking the
        // whole page on a permission the operator might grant later from Settings.
        webView.loadUrl("http://127.0.0.1:" + ModeApp.PORT + "/");
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMISSIONS) {
            for (int i = 0; i < permissions.length; i++) {
                Log.i(TAG, permissions[i] + " granted=" + (grantResults[i] == PackageManager.PERMISSION_GRANTED));
            }
        }
    }
}
