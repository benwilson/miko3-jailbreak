package com.miko3.shared;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written Binder interface (Stub/Proxy, no .aidl compiler in this build)
 * for the launcher's speech service (voice plan U5; R8, R11, KTD4, KTD5).
 * Same shape as RobotSettings. Modes reach it through RobotSpeechClient rather
 * than binding directly.
 *
 * speak() queues a line behind whatever is playing, from any mode, and
 * returns at once; the line's Callback is told once, finished or cancelled.
 * cancel() drops every line this app queued, and stops its playing line at
 * the next sentence boundary; other apps' lines are untouched. The launcher
 * checks the caller on every call and throws SecurityException to any app
 * that isn't one of ours, and IllegalArgumentException with a fixed reason
 * for a line it won't say (empty, too long, no voice); Binder carries both
 * back to the Proxy, where reply.readException() rethrows them.
 */
public interface RobotSpeech extends IInterface {
    void speak(String text, Callback callback) throws RemoteException;

    void cancel() throws RemoteException;

    /** How one line ended. Called one-way from the launcher, on a Binder
     * thread of the calling app; exactly one of the two, once. */
    interface Callback extends IInterface {
        void finished() throws RemoteException;

        void cancelled() throws RemoteException;

        abstract class Stub extends Binder implements Callback {
            private static final String DESCRIPTOR = "com.miko3.shared.RobotSpeech.Callback";
            static final int TRANSACTION_finished = 1;
            static final int TRANSACTION_cancelled = 2;

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
                    case TRANSACTION_finished:
                        data.enforceInterface(DESCRIPTOR);
                        finished();
                        return true;
                    case TRANSACTION_cancelled:
                        data.enforceInterface(DESCRIPTOR);
                        cancelled();
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
                public void finished() throws RemoteException {
                    send(TRANSACTION_finished);
                }

                @Override
                public void cancelled() throws RemoteException {
                    send(TRANSACTION_cancelled);
                }

                /** One-way, so the launcher's audio thread never waits on a mode. */
                private void send(int code) throws RemoteException {
                    Parcel data = Parcel.obtain();
                    try {
                        data.writeInterfaceToken(DESCRIPTOR);
                        remote.transact(code, data, null, IBinder.FLAG_ONEWAY);
                    } finally {
                        data.recycle();
                    }
                }
            }
        }
    }

    abstract class Stub extends Binder implements RobotSpeech {
        private static final String DESCRIPTOR = "com.miko3.shared.RobotSpeech";
        static final int TRANSACTION_speak = 1;
        static final int TRANSACTION_cancel = 2;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        public static RobotSpeech asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof RobotSpeech) {
                return (RobotSpeech) local;
            }
            return new Proxy(binder);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case TRANSACTION_speak: {
                    data.enforceInterface(DESCRIPTOR);
                    String text = data.readString();
                    Callback callback = Callback.Stub.asInterface(data.readStrongBinder());
                    speak(text, callback);
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_cancel: {
                    data.enforceInterface(DESCRIPTOR);
                    cancel();
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

        private static class Proxy implements RobotSpeech {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public void speak(String text, Callback callback) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(text);
                    data.writeStrongBinder(callback == null ? null : callback.asBinder());
                    remote.transact(TRANSACTION_speak, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void cancel() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(TRANSACTION_cancel, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
