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
    // See ModeApp.bumpGeneration()'s javadoc: captured whenever this instance
    // (re)activates, compared against ModeApp.currentGeneration() before this
    // instance tears down shared (camera/drive) state, so a stale instance's
    // delayed teardown can't clobber a newer instance's already-live state.
    private long myGeneration;
    // Kept so the exit path can stop its on-device mic passthrough (another
    // AudioRecord holder besides ModeApp's MicCapture).
    private NativeCaptureBridge captureBridge;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        logWebViewCapability();

        // No theme override in AndroidManifest.xml means the default Android theme's
        // ActionBar/title bar was always showing here — confirmed live via screencap:
        // "Miko3 Remote Control" across the top, over the blank content below. Harmless
        // on the old full-content page but out of place now that this screen is
        // supposed to be just black or the operator's video (U13), nothing else.
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDomStorageEnabled(true);
        captureBridge = new NativeCaptureBridge(this);
        webView.addJavascriptInterface(captureBridge, "AndroidCapture");
        // Confirmed live (screencap showed a blank page under the title bar above):
        // without a WebViewClient, the default SSL-error handling for the
        // http://127.0.0.1:PORT load below — which redirects to https://127.0.0.1:
        // HTTPS_PORT once RoutingHttpServer's HTTPS listener is up — treats our
        // self-signed certificate as fatal and silently aborts the whole load. Safe to
        // proceed unconditionally here: this WebView only ever talks to our own
        // server on the loopback interface, never anything the operator navigates to.
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                    android.net.http.SslError error) {
                handler.proceed();
            }
        });
        setContentView(webView);

        requestRuntimePermissionsThenLoad();
        activateDriveController();

        if (getIntent() != null && getIntent().getBooleanExtra(LauncherProtocol.EXTRA_FORCE_EXIT, false)) {
            exitMode();
        }
    }

    /** Creates and starts a fresh DriveController (acquiring the lease) and starts the
     * camera. Called from onCreate (a genuinely new instance) and, defensively, from
     * onNewIntent (see there) if this instance was reused while not currently active.
     * Deliberately NOT tied to onResume/onPause (confirmed live: it was previously,
     * and the robot's own on-device screen switching away from this Activity — e.g.
     * back to the launcher, or the screen simply locking — stopped the camera even
     * while a remote operator was still actively watching the stream over the mode's
     * always-on server). Camera now runs for as long as this activation is current,
     * released only in exitMode()/onDestroy(). */
    private void activateDriveController() {
        ModeApp app = (ModeApp) getApplication();
        myGeneration = app.bumpGeneration();
        app.startCamera();
        String clientId = "mode-" + android.os.Process.myPid() + "-" + System.currentTimeMillis();
        driveController = new DriveController(this, clientId, new DriveController.ErrorListener() {
            @Override
            public void onDriveError(final String reason) {
                Log.e(TAG, "drive error: " + reason);
            }
        });
        driveController.start();
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
            webView.loadUrl("http://127.0.0.1:" + ModeApp.PORT + "/device-view");
        }
    }

    private void exitMode() {
        if (driveController != null) {
            driveController.release();
        }
        // Without this, onNewIntent()'s "reactivating in place" check
        // (driveController == null) never fires for this same singleTop instance
        // if it's reused after an exit — leaving it resumed with a stale,
        // already-released controller that never gets recreated.
        driveController = null;
        // This instance's own mic passthrough goes before presence clears, like
        // the app-wide mic below, so the launcher's wait implies a free mic.
        releaseCaptureBridge();
        ModeApp app = (ModeApp) getApplication();
        // Only clear the app-wide controller if a newer instance hasn't already
        // taken over (see ModeApp.bumpGeneration()'s javadoc) — confirmed live
        // that without this check, rapid repeated launches could have this
        // stale instance's exit wipe out a newer instance's already-active state.
        if (myGeneration == app.currentGeneration()) {
            app.setDriveController(null);
            app.stopCamera();
            app.stopSong();
            // Two modes now use the microphone: release it (and the operator
            // speaker) here, synchronously, so the next mode's capture doesn't
            // fail busy while this cached process still holds it. Presence goes
            // inactive only after, so the launcher's wait implies a free mic.
            app.stopMicAndSpeaker();
            app.deactivate(myGeneration);
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        if (driveController != null) {
            driveController.release();
        }
        releaseCaptureBridge();
        ModeApp app = (ModeApp) getApplication();
        if (myGeneration == app.currentGeneration()) {
            app.setDriveController(null);
            app.stopCamera();
            app.stopSong();
            app.stopMicAndSpeaker();
            app.deactivate(myGeneration);
        }
        super.onDestroy();
    }

    /** This instance's own on-device mic passthrough, if its page turned it on —
     * not generation-guarded, since the bridge belongs to this instance's WebView. */
    private void releaseCaptureBridge() {
        if (captureBridge != null) {
            captureBridge.toggleOperatorMic(false);
        }
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
        // ?localview=1: see ModeApp's "/" route comment — this is the robot's own
        // on-device view of the page, not a remote operator, and must not compete
        // for drive control just by virtue of loading.
        webView.loadUrl("http://127.0.0.1:" + ModeApp.PORT + "/device-view");
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
