package com.miko3.mode.explore;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;

import com.miko3.shared.LauncherProtocol;

/**
 * Full-screen WebView shell for the explore mode, launched by the launcher
 * via explicit Intent (not HOME) and modeled on the voice mode's
 * MainActivity. Shows the mode's device view (the eyes) on the robot's own
 * screen. Activating starts the wander (ModeApp.startExplore); the current
 * generation's exit stops it synchronously before finishing, so the robot is
 * stopped by the time the launcher sees presence go inactive (R5).
 *
 * launchMode="singleTop" plus onNewIntent() is the launcher's force-exit
 * path (LauncherProtocol.EXTRA_FORCE_EXIT): the extra is honored in both
 * onCreate (a fresh instance) and onNewIntent (the running one), or a
 * relaunched instance would ignore the exit request.
 *
 * No lambdas/method references, matching the rest of this repo.
 */
public class MainActivity extends Activity {
    private static final String TAG = "ExploreMainActivity";

    private WebView webView;
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
        // Same as the other modes: the http://127.0.0.1 load below is redirected
        // to our own HTTPS port, whose self-signed certificate the WebView would
        // otherwise treat as fatal and abort the load. Safe to proceed: this
        // WebView only ever talks to our own loopback server.
        webView.setWebViewClient(new android.webkit.WebViewClient() {
            @Override
            public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                    android.net.http.SslError error) {
                handler.proceed();
            }
        });
        setContentView(webView);

        // Activate before honoring a force-exit, as the other modes do: exitMode()
        // then deactivates as the current generation, so presence reads inactive
        // afterwards no matter what an older instance left behind.
        boolean forceExit = isForceExit(getIntent());
        activate(!forceExit);
        if (forceExit) {
            Log.i(TAG, "force-exit on a fresh instance");
            exitMode();
            return;
        }
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
            // exited: reactivate in place so it ends up where a fresh launch would have.
            Log.i(TAG, "reactivating in place (redelivered launch intent, not currently active)");
            activate(true);
            loadDeviceView();
        }
    }

    /** Marks this instance the active one (presence on) and starts the wander
     * unless this is a force-exit launch. */
    private void activate(boolean startExplore) {
        ModeApp app = (ModeApp) getApplication();
        myGeneration = app.activate();
        activated = true;
        if (startExplore) {
            app.startExplore();
        }
    }

    /**
     * The one exit path: the launcher's force-exit Intent ends here. Stops the
     * wander and clears presence (only if this is still the current generation),
     * then finishes, which lands on the launcher's HOME Activity underneath.
     */
    private void exitMode() {
        if (releaseIfCurrent()) {
            Log.i(TAG, "exited (generation " + myGeneration + ")");
        }
        finish();
    }

    @Override
    protected void onDestroy() {
        // Destroyed without an exit (e.g. by the system): nothing may keep driving
        // once no Activity is active.
        releaseIfCurrent();
        super.onDestroy();
    }

    /**
     * Shared by exitMode() and onDestroy(): once per activation, stops the
     * wander and then clears presence, but only while this instance is still the
     * current generation. The wander stops first so the launcher, which waits
     * for presence to go inactive before starting another mode, never finds the
     * motors still driven or the lease still held. Returns whether it stopped.
     */
    private boolean releaseIfCurrent() {
        if (!activated) {
            return false;
        }
        activated = false;
        ModeApp app = (ModeApp) getApplication();
        // Activity callbacks all run on the main thread, so no newer instance can
        // activate between this check and deactivate() below.
        if (!app.isCurrent(myGeneration)) {
            return false;
        }
        app.stopExplore();
        app.deactivate(myGeneration);
        return true;
    }

    private static boolean isForceExit(Intent intent) {
        return intent != null && intent.getBooleanExtra(LauncherProtocol.EXTRA_FORCE_EXIT, false);
    }

    private void loadDeviceView() {
        webView.loadUrl("http://127.0.0.1:" + ModeApp.PORT + "/device-view");
    }
}
