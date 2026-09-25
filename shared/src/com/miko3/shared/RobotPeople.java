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
 * the match request itself carries faces only (KTD3).
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

    String add(byte[] faceJpeg, String nameOrNull) throws RemoteException;

    boolean touch(String id) throws RemoteException;

    String nameOf(String id) throws RemoteException;

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
            public String add(byte[] faceJpeg, String nameOrNull) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeByteArray(faceJpeg);
                    data.writeString(nameOrNull);
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
        }
    }
}
