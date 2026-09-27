package com.miko3.shared;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written Binder interface (Stub/Proxy, no .aidl compiler in this build)
 * for the launcher's people store (explore-on-claude plan U2; R13, R14, KTD1,
 * KTD3). Same shape as RobotSettings and RobotSpeech. Modes reach it through
 * RobotPeopleClient rather than binding directly.
 *
 * recent(n) answers the n most recently seen people (at most MAX_RECENT),
 * newest first, each as an id and a 224 px face JPEG, for Explore's face
 * match request. add() remembers a new person, seen now, and answers their
 * id. touch() marks someone seen now (false for an unknown id). nameOf()
 * answers a name, "" for an unnamed person, or null once the owner has
 * forgotten them. Names never leave the launcher except through nameOf(), so
 * the match request itself carries faces only (KTD3). notesOf(), mergeNotes()
 * and forget() (meeting plan U5, KTD10) carry a person's notes as PersonNotes
 * JSON and wipe a person by id, never by name.
 *
 * The launcher checks the caller on every call and throws SecurityException
 * to any app that isn't one of ours, and IllegalArgumentException with a
 * fixed reason for a face it won't store; Binder carries both back to the
 * Proxy, where reply.readException() rethrows them.
 */
public interface RobotPeople extends IInterface {
    /** The most faces one recent() answer carries (KTD3). With the store's
     * per-face cap, ten stay far below Binder's 1 MB transaction buffer. */
    int MAX_RECENT = 10;

    Face[] recent(int n) throws RemoteException;

    /** Remembers a new person: a face JPEG and their name, which is required
     * (R19: nobody is stored without one); answers their id. The launcher
     * throws IllegalArgumentException with the store's fixed reason for a
     * missing or blank name, as for a face it won't store. */
    String add(byte[] faceJpeg, String name) throws RemoteException;

    boolean touch(String id) throws RemoteException;

    String nameOf(String id) throws RemoteException;

    /** The person's notes as PersonNotes JSON (meeting plan U5, KTD10); the
     * empty document for an unknown id. Appended: its Proxy throws
     * UnsupportedOperationException (LauncherProtocol.LAUNCHER_TOO_OLD) when
     * the launcher predates it, as do mergeNotes() and forget(). */
    String notesOf(String id) throws RemoteException;

    /** Merges a delta (a PersonNotes JSON object) into the person's notes and
     * answers the merged document. IllegalArgumentException with the store's
     * or PersonNotes' fixed reason for an unknown id or a bad delta. */
    String mergeNotes(String id, String deltaJson) throws RemoteException;

    /** Wipes the person's face, name and notes (R18); false if unknown. */
    boolean forget(String id) throws RemoteException;

    /** One remembered person's id and face, as recent() answers them. */
    final class Face {
        public final String id;
        public final byte[] jpeg;

        public Face(String id, byte[] jpeg) {
            this.id = id;
            this.jpeg = jpeg;
        }
    }

    abstract class Stub extends Binder implements RobotPeople {
        private static final String DESCRIPTOR = "com.miko3.shared.RobotPeople";
        static final int TRANSACTION_recent = 1;
        static final int TRANSACTION_add = 2;
        static final int TRANSACTION_touch = 3;
        static final int TRANSACTION_nameOf = 4;
        static final int TRANSACTION_notesOf = 5;
        static final int TRANSACTION_mergeNotes = 6;
        static final int TRANSACTION_forget = 7;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        public static RobotPeople asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof RobotPeople) {
                return (RobotPeople) local;
            }
            return new Proxy(binder);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case TRANSACTION_recent: {
                    data.enforceInterface(DESCRIPTOR);
                    Face[] faces = recent(data.readInt());
                    reply.writeNoException();
                    int count = faces == null ? 0 : faces.length;
                    reply.writeInt(count);
                    for (int i = 0; i < count; i++) {
                        reply.writeString(faces[i].id);
                        reply.writeByteArray(faces[i].jpeg);
                    }
                    return true;
                }
                case TRANSACTION_add: {
                    data.enforceInterface(DESCRIPTOR);
                    byte[] jpeg = data.createByteArray();
                    String name = data.readString();
                    String id = add(jpeg, name);
                    reply.writeNoException();
                    reply.writeString(id);
                    return true;
                }
                case TRANSACTION_touch: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean known = touch(data.readString());
                    reply.writeNoException();
                    reply.writeInt(known ? 1 : 0);
                    return true;
                }
                case TRANSACTION_nameOf: {
                    data.enforceInterface(DESCRIPTOR);
                    String name = nameOf(data.readString());
                    reply.writeNoException();
                    reply.writeString(name);
                    return true;
                }
                case TRANSACTION_notesOf: {
                    data.enforceInterface(DESCRIPTOR);
                    String notes = notesOf(data.readString());
                    reply.writeNoException();
                    reply.writeString(notes);
                    return true;
                }
                case TRANSACTION_mergeNotes: {
                    data.enforceInterface(DESCRIPTOR);
                    String id = data.readString();
                    String delta = data.readString();
                    String merged = mergeNotes(id, delta);
                    reply.writeNoException();
                    reply.writeString(merged);
                    return true;
                }
                case TRANSACTION_forget: {
                    data.enforceInterface(DESCRIPTOR);
                    boolean known = forget(data.readString());
                    reply.writeNoException();
                    reply.writeInt(known ? 1 : 0);
                    return true;
                }
                case IBinder.INTERFACE_TRANSACTION: {
                    reply.writeString(DESCRIPTOR);
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }

        private static class Proxy implements RobotPeople {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public Face[] recent(int n) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeInt(n);
                    remote.transact(TRANSACTION_recent, data, reply, 0);
                    reply.readException();
                    int count = reply.readInt();
                    if (count < 0 || count > MAX_RECENT) {
                        throw new RemoteException("bad people reply");
                    }
                    Face[] faces = new Face[count];
                    for (int i = 0; i < count; i++) {
                        String id = reply.readString();
                        faces[i] = new Face(id, reply.createByteArray());
                    }
                    return faces;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String add(byte[] faceJpeg, String name) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeByteArray(faceJpeg);
                    data.writeString(name);
                    remote.transact(TRANSACTION_add, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean touch(String id) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    remote.transact(TRANSACTION_touch, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String nameOf(String id) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    remote.transact(TRANSACTION_nameOf, data, reply, 0);
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            // The three appended calls check the transaction result: a launcher
            // without the code answers false (Binder's default onTransact) and
            // an empty reply, which must be named, not read as empty notes.

            @Override
            public String notesOf(String id) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    if (!remote.transact(TRANSACTION_notesOf, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String mergeNotes(String id, String deltaJson) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    data.writeString(deltaJson);
                    if (!remote.transact(TRANSACTION_mergeNotes, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readString();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public boolean forget(String id) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(id);
                    if (!remote.transact(TRANSACTION_forget, data, reply, 0)) {
                        throw new UnsupportedOperationException(LauncherProtocol.LAUNCHER_TOO_OLD);
                    }
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
