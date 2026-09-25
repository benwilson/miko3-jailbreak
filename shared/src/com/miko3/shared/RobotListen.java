package com.miko3.shared;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written Binder interface (Stub/Proxy, no .aidl compiler in this build)
 * for the launcher's listening service (explore-on-claude plan U3; R11, R12,
 * KTD1, KTD4, KTD8). Same shape as RobotSpeech. Modes reach it through
 * RobotListenClient rather than binding directly.
 *
 * listen() returns at once. The launcher waits until the robot has finished
 * speaking, records one short reply (up to maxMs, ending early once the
 * speaker stops), and tells the Callback once: heard(transcript), noSpeech(),
 * or failed(reason). The launcher checks the caller on every call and throws
 * SecurityException to any app that isn't one of ours, and
 * IllegalStateException with a fixed reason when it won't listen now
 * (another listen holds the microphone, or the model hasn't loaded); Binder
 * carries both back to the Proxy, where reply.readException() rethrows them.
 */
public interface RobotListen extends IInterface {
    void listen(long maxMs, Callback callback) throws RemoteException;

    /** How one listen ended. Called one-way from the launcher, on a Binder
     * thread of the calling app; exactly one of the three, once. */
    interface Callback extends IInterface {
        /** transcript is the recognizer's, trimmed and never empty. */
        void heard(String transcript) throws RemoteException;

        void noSpeech() throws RemoteException;

        void failed(String reason) throws RemoteException;

        abstract class Stub extends Binder implements Callback {
            private static final String DESCRIPTOR = "com.miko3.shared.RobotListen.Callback";
            static final int TRANSACTION_heard = 1;
            static final int TRANSACTION_noSpeech = 2;
            static final int TRANSACTION_failed = 3;

            public Stub() {
                attachInterface(this, DESCRIPTOR);
            }

            @Override
            public IBinder asBinder() {
                return this;
            }

            public static Callback asInterface(IBinder binder) {
                if (binder == null) {
                    return null;
                }
                IInterface local = binder.queryLocalInterface(DESCRIPTOR);
                if (local instanceof Callback) {
                    return (Callback) local;
                }
                return new Proxy(binder);
            }

            @Override
            public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                switch (code) {
                    case TRANSACTION_heard:
                        data.enforceInterface(DESCRIPTOR);
                        heard(data.readString());
                        return true;
                    case TRANSACTION_noSpeech:
                        data.enforceInterface(DESCRIPTOR);
                        noSpeech();
                        return true;
                    case TRANSACTION_failed:
                        data.enforceInterface(DESCRIPTOR);
                        failed(data.readString());
                        return true;
                    case IBinder.INTERFACE_TRANSACTION:
                        reply.writeString(DESCRIPTOR);
                        return true;
                    default:
                        return super.onTransact(code, data, reply, flags);
                }
            }

            private static class Proxy implements Callback {
                private final IBinder remote;

                Proxy(IBinder remote) {
                    this.remote = remote;
                }

                @Override
                public IBinder asBinder() {
                    return remote;
                }

                @Override
                public void heard(String transcript) throws RemoteException {
                    send(TRANSACTION_heard, transcript);
                }

                @Override
                public void noSpeech() throws RemoteException {
                    send(TRANSACTION_noSpeech, null);
                }

                @Override
                public void failed(String reason) throws RemoteException {
                    send(TRANSACTION_failed, reason);
                }

                /** One-way, so the launcher's listen thread never waits on a mode. */
                private void send(int code, String text) throws RemoteException {
                    Parcel data = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(DESCRIPTOR);
                        if (code != TRANSACTION_noSpeech) {
                            data.writeString(text);
                        }
                        remote.transact(code, data, null, IBinder.FLAG_ONEWAY);
                    } finally {
                        data.recycle();
                    }
                }
            }
        }
    }

    abstract class Stub extends Binder implements RobotListen {
        private static final String DESCRIPTOR = "com.miko3.shared.RobotListen";
        static final int TRANSACTION_listen = 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        public static RobotListen asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof RobotListen) {
                return (RobotListen) local;
            }
            return new Proxy(binder);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case TRANSACTION_listen: {
                    data.enforceInterface(DESCRIPTOR);
                    long maxMs = data.readLong();
                    Callback callback = Callback.Stub.asInterface(data.readStrongBinder());
                    listen(maxMs, callback);
                    reply.writeNoException();
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

        private static class Proxy implements RobotListen {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public void listen(long maxMs, Callback callback) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeLong(maxMs);
                    data.writeStrongBinder(callback == null ? null : callback.asBinder());
                    remote.transact(TRANSACTION_listen, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
