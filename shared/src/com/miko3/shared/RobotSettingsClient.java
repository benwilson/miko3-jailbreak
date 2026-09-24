package com.miko3.shared;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * How a mode reads the robot's Claude API settings from the launcher
 * (settings plan U5, R13): bind RobotSettingsService, fetch once, unbind.
 * Call it before each Claude request rather than caching the answer, so a
 * change on the Settings page applies to the mode's next request (AE5).
 *
 * Blocks, so call it from a worker thread: onServiceConnected() arrives on
 * the main thread, and waiting for it there would deadlock.
 */
public final class RobotSettingsClient {
    public static final long DEFAULT_TIMEOUT_MS = 3000;

    private RobotSettingsClient() {
    }

    /**
     * The launcher's current answer: the base URL, key, and model, or
     * ClaudeAccess.notSetUp() (check isSetUp()). Throws IOException with a
     * fixed message when the launcher can't be reached in timeoutMs or turns
     * this app away.
     */
    public static ClaudeAccess fetch(Context context, long timeoutMs) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("RobotSettingsClient.fetch() blocks; call it off the main thread");
        }
        final CountDownLatch connected = new CountDownLatch(1);
        final IBinder[] binder = new IBinder[1];
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                synchronized (binder) {
                    binder[0] = service;
                }
                connected.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }
        };

        Intent intent = new Intent(LauncherProtocol.ROBOT_SETTINGS_ACTION);
        intent.setPackage(LauncherProtocol.LAUNCHER_PACKAGE);
        Context app = context.getApplicationContext();
        try {
            if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                throw new IOException("launcher settings service not found");
            }
            if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("launcher settings service timed out");
            }
            IBinder service;
            synchronized (binder) {
                service = binder[0];
            }
            return RobotSettings.Stub.asInterface(service).getClaudeAccess();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for launcher settings service");
        } catch (SecurityException e) {
            throw new IOException("launcher settings service refused this app");
        } catch (RemoteException e) {
            throw new IOException("launcher settings service died");
        } finally {
            // Android wants an unbind even when bindService() returned false;
            // it throws if nothing was registered, which is fine to ignore.
            try {
                app.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
            }
        }
    }

    public static ClaudeAccess fetch(Context context) throws IOException {
        return fetch(context, DEFAULT_TIMEOUT_MS);
    }
}
