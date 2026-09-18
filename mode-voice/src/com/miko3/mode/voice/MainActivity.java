package com.miko3.mode.voice;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.miko3.shared.LauncherProtocol;

/**
 * Full-screen WebView shell for the voice mode, launched by the launcher via
 * explicit Intent (not HOME) and modeled on mode-remote-control's
 * MainActivity. Shows the mode's device view (the eyes) on the robot's own
 * screen; settings live on the LAN-facing page at the server's root, not
 * here. Activating starts the voice engine and relay client (ModeApp
 * .startVoice); the current generation's exit stops them synchronously
 * before finishing, so the speaker is silent at once (R17, AE12).
 *
 * Requests RECORD_AUDIO at runtime if it isn't already granted —
 * scripts/install-mode-voice.py grants it at install time, so the dialog
 * normally never appears. A denial is logged, not retried; the engine keeps
 * retrying the microphone with backoff, so a later grant takes effect.
 *
 * launchMode="singleTop" plus onNewIntent() is the launcher's force-exit
 * path (LauncherProtocol.EXTRA_FORCE_EXIT): the extra is honored in both
 * onCreate (a fresh instance) and onNewIntent (the running one), or a
 * relaunched instance would ignore the exit request.
 *
 * Never opens the camera, binds no AIDL service, takes no drive lease, and
 * touches no motor class (R18, Implementation Constraints).
 *
 * No lambdas/method references, matching the rest of this repo.
 */
public class MainActivity extends Activity {
    private static final String TAG = "VoiceMainActivity";
    private static final int REQ_PERMISSIONS = 3001;

    private WebView webView;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    // See ModeApp's generation comment: captured whenever this instance
    // (re)activates, passed back to ModeApp.deactivate() so a stale instance's
    // late teardown can't clear a newer instance's presence or state.
    private long myGeneration;
    // Whether this instance is the one it last activated as. Cleared on exit so a
    // plain launch Intent redelivered to this same singleTop instance reactivates
    // it (see onNewIntent) instead of leaving it resumed but inactive.
    private boolean activated;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);

        webView = new WebView(this);
        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setDomStorageEnabled(true);
        // Same as mode-remote-control: the http://127.0.0.1 load below is
        // redirected to our own HTTPS port, whose self-signed certificate the
        // WebView would otherwise treat as fatal and abort the load. Safe to
        // proceed: this WebView only ever talks to our own loopback server.
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                    android.net.http.SslError error) {
                handler.proceed();
            }
        });
        setContentView(webView);

        // Activate before honoring a force-exit, as the remote-control mode does:
        // exitMode() then deactivates as the current generation, so presence reads
        // inactive afterwards no matter what an older instance left behind.
        boolean forceExit = isForceExit(getIntent());
        activate(!forceExit);
        if (forceExit) {
            Log.i(TAG, "force-exit on a fresh instance");
            exitMode();
            return;
        }
        requestRuntimePermissions();
        loadDeviceView();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (isForceExit(intent)) {
            Log.i(TAG, "force-exit on the running instance");
            exitMode();
        } else if (!activated) {
            // A plain launch Intent redelivered to this singleTop instance after it
            // exited (see the remote-control mode's identical branch): reactivate in
            // place so it ends up where a fresh launch would have.
            Log.i(TAG, "reactivating in place (redelivered launch intent, not currently active)");
            activate(true);
            loadDeviceView();
        }
    }

    /** Marks this instance the active one (presence on), hooks up /exit, and
     * starts the voice engine unless this is a force-exit launch. */
    private void activate(boolean startVoice) {
        ModeApp app = (ModeApp) getApplication();
        myGeneration = app.activate();
        activated = true;
        if (startVoice) {
            app.startVoice();
        }
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

    /**
     * The one exit path: the launcher's force-exit Intent and the settings
     * page's Exit button both end here. Clears presence (only if this is still
     * the current generation) and finishes, which lands on the launcher's HOME
     * Activity underneath. The voice engine is released synchronously inside
     * the current-generation branch, before finish(), so exiting mid-reply stops
     * audio at once (R17) and a stale instance's exit can't stop a newer
     * instance's engine.
     */
    private void exitMode() {
        if (releaseIfCurrent()) {
            Log.i(TAG, "exited (generation " + myGeneration + ")");
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        // Destroyed without an exit (e.g. by the system): nothing may keep the
        // microphone once no Activity is active.
        releaseIfCurrent();
        super.onDestroy();
    }

    /**
     * Shared by exitMode() and onDestroy(): once per activation, clears presence
     * and stops the voice engine, but only while this instance is still the
     * current generation. Returns whether the engine was stopped.
     */
    private boolean releaseIfCurrent() {
        if (!activated) {
            return false;
        }
        activated = false;
        ModeApp app = (ModeApp) getApplication();
        if (!app.deactivate(myGeneration)) {
            return false;
        }
        app.stopVoice();
        return true;
    }

    private static boolean isForceExit(Intent intent) {
        return intent != null && intent.getBooleanExtra(LauncherProtocol.EXTRA_FORCE_EXIT, false);
    }

    private void loadDeviceView() {
        webView.loadUrl("http://127.0.0.1:" + ModeApp.PORT + "/device-view");
    }

    private void requestRuntimePermissions() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMISSIONS);
        }
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
