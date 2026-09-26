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
 * How a mode reads the robot's settings from the launcher (settings plan U5,
 * R13; meeting plan U4, KTD11): bind RobotSettingsService, fetch once,
 * unbind. Call it before each Claude request (or each conversation, or each
 * cue) rather than caching the answer, so a change on the Settings page
 * applies to the mode's next request (AE5).
 *
 * Blocks, so call it from a worker thread: onServiceConnected() arrives on
 * the main thread, and waiting for it there would deadlock.
 */
public final class RobotSettingsClient {
    public static final long DEFAULT_TIMEOUT_MS = 3000;

    private RobotSettingsClient() {
    }

    private interface Call<T> {
        T run(RobotSettings settings) throws RemoteException;
    }

    /**
     * The launcher's current answer: the base URL, key, and model, or
     * ClaudeAccess.notSetUp() (check isSetUp()). Throws IOException with a
     * fixed message when the launcher can't be reached in timeoutMs or turns
     * this app away.
     */
    public static ClaudeAccess fetch(Context context, long timeoutMs) throws IOException {
        return call(context, timeoutMs, new Call<ClaudeAccess>() {
            @Override
            public ClaudeAccess run(RobotSettings settings) throws RemoteException {
                return settings.getClaudeAccess();
            }
        });
    }

    public static ClaudeAccess fetch(Context context) throws IOException {
        return fetch(context, DEFAULT_TIMEOUT_MS);
    }

    /**
     * The persona text (the owner's or the built-in default) and the "answers
     * when spoken to" switch. Throws IOException with a fixed message when the
     * launcher can't be reached, turns this app away, or is too old to answer
     * (LauncherProtocol.LAUNCHER_TOO_OLD).
     */
    public static ConversationSettings fetchConversation(Context context, long timeoutMs) throws IOException {
        return call(context, timeoutMs, new Call<ConversationSettings>() {
            @Override
            public ConversationSettings run(RobotSettings settings) throws RemoteException {
                return settings.getConversationSettings();
            }
        });
    }

    public static ConversationSettings fetchConversation(Context context) throws IOException {
        return fetchConversation(context, DEFAULT_TIMEOUT_MS);
    }

    private static <T> T call(Context context, long timeoutMs, Call<T> call) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("RobotSettingsClient blocks; call it off the main thread");
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
            return call.run(RobotSettings.Stub.asInterface(service));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for launcher settings service");
        } catch (SecurityException e) {
            throw new IOException("launcher settings service refused this app");
        } catch (UnsupportedOperationException e) {
            // The Proxy's word for a transaction the launcher did not answer.
            throw new IOException(LauncherProtocol.LAUNCHER_TOO_OLD);
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
}
