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
 * How a mode reaches the robot's people store in the launcher
 * (explore-on-claude plan U2; R13, R14, KTD1): bind PeopleService, make one
 * call, unbind, the same way RobotSettingsClient fetches the settings.
 *
 * <pre>
 *   RobotPeople.Face[] faces = RobotPeopleClient.recent(context, RobotPeople.MAX_RECENT);
 *   String id = RobotPeopleClient.add(context, faceJpeg, "Sarah");
 * </pre>
 *
 * Each call blocks, so call it from a worker thread: onServiceConnected()
 * arrives on the main thread, and waiting for it there would deadlock. Each
 * throws IOException with a fixed message when the launcher can't be reached
 * in time or turns this app away, and with the store's fixed reason when it
 * refuses a face. See RobotPeople for what each call answers.
 */
public final class RobotPeopleClient {
    public static final long DEFAULT_TIMEOUT_MS = 3000;

    private RobotPeopleClient() {
    }

    private interface Call<T> {
        T run(RobotPeople people) throws RemoteException;
    }

    /** The n most recently seen people, newest first (at most RobotPeople.MAX_RECENT). */
    public static RobotPeople.Face[] recent(Context context, final int n) throws IOException {
        return call(context, DEFAULT_TIMEOUT_MS, new Call<RobotPeople.Face[]>() {
            @Override
            public RobotPeople.Face[] run(RobotPeople people) throws RemoteException {
                return people.recent(n);
            }
        });
    }

    /** Remembers a new person (a 224 px face JPEG and their name, required: R19); answers
     * their id. IOException with the store's fixed reason when the name is missing. */
    public static String add(Context context, final byte[] faceJpeg, final String name) throws IOException {
        return call(context, DEFAULT_TIMEOUT_MS, new Call<String>() {
            @Override
            public String run(RobotPeople people) throws RemoteException {
                return people.add(faceJpeg, name);
            }
        });
    }

    /** Marks a person seen now; false if the owner has forgotten them. */
    public static boolean touch(Context context, final String id) throws IOException {
        return call(context, DEFAULT_TIMEOUT_MS, new Call<Boolean>() {
            @Override
            public Boolean run(RobotPeople people) throws RemoteException {
                return people.touch(id);
            }
        });
    }

    /** The name, "" when unnamed, or null when there is no such person. */
    public static String nameOf(Context context, final String id) throws IOException {
        return call(context, DEFAULT_TIMEOUT_MS, new Call<String>() {
            @Override
            public String run(RobotPeople people) throws RemoteException {
                return people.nameOf(id);
            }
        });
    }

    /** The person's notes (meeting plan U5, KTD10); PersonNotes.EMPTY for an
     * unknown id. Throws LauncherProtocol.LAUNCHER_TOO_OLD as an IOException
     * when the launcher predates notes. */
    public static PersonNotes notesOf(Context context, final String id) throws IOException {
        return parseNotes(call(context, DEFAULT_TIMEOUT_MS, new Call<String>() {
            @Override
            public String run(RobotPeople people) throws RemoteException {
                return people.notesOf(id);
            }
        }));
    }

    /** Merges a delta (a PersonNotes JSON object) into the person's notes and
     * answers the merged document. IOException with the store's or PersonNotes'
     * fixed reason for an unknown id or a bad delta. */
    public static PersonNotes mergeNotes(Context context, final String id, final String deltaJson) throws IOException {
        return parseNotes(call(context, DEFAULT_TIMEOUT_MS, new Call<String>() {
            @Override
            public String run(RobotPeople people) throws RemoteException {
                return people.mergeNotes(id, deltaJson);
            }
        }));
    }

    /** Wipes the person's face, name and notes (R18); false if unknown. */
    public static boolean forget(Context context, final String id) throws IOException {
        return call(context, DEFAULT_TIMEOUT_MS, new Call<Boolean>() {
            @Override
            public Boolean run(RobotPeople people) throws RemoteException {
                return people.forget(id);
            }
        });
    }

    private static PersonNotes parseNotes(String json) {
        try {
            return PersonNotes.parse(json);
        } catch (IllegalArgumentException e) {
            // The launcher writes these itself; anything else reads as no notes.
            return PersonNotes.EMPTY;
        }
    }

    private static <T> T call(Context context, long timeoutMs, Call<T> call) throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new IllegalStateException("RobotPeopleClient blocks; call it off the main thread");
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

        Intent intent = new Intent(LauncherProtocol.ROBOT_PEOPLE_ACTION);
        intent.setPackage(LauncherProtocol.LAUNCHER_PACKAGE);
        Context app = context.getApplicationContext();
        try {
            if (!app.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                throw new IOException("launcher people service not found");
            }
            if (!connected.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IOException("launcher people service timed out");
            }
            IBinder service;
            synchronized (binder) {
                service = binder[0];
            }
            return call.run(RobotPeople.Stub.asInterface(service));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted waiting for launcher people service");
        } catch (SecurityException e) {
            throw new IOException("launcher people service refused this app");
        } catch (IllegalArgumentException e) {
            // PeopleStore's or PersonNotes' fixed REFUSE_* reason.
            throw new IOException(e.getMessage());
        } catch (UnsupportedOperationException e) {
            // The Proxy's word for a transaction the launcher did not answer.
            throw new IOException(LauncherProtocol.LAUNCHER_TOO_OLD);
        } catch (RemoteException e) {
            throw new IOException("launcher people service died");
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
