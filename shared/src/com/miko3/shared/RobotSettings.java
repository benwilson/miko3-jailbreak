package com.miko3.shared;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written Binder interface (Stub/Proxy, no .aidl compiler in this
 * build) for the robot settings service hosted by the launcher (see
 * RobotSettingsService; settings plan U5, KTD1). Same shape as DriveLease.
 * Modes reach it through RobotSettingsClient rather than binding directly.
 *
 * The launcher checks the caller on every call and throws SecurityException
 * to any app that isn't one of ours; Binder carries that back to the Proxy,
 * where reply.readException() rethrows it.
 */
public interface RobotSettings extends IInterface {
    /** The current Claude API base URL, key, and model, read from the
     * launcher's store on this call (R13), or ClaudeAccess.notSetUp(). */
    ClaudeAccess getClaudeAccess() throws RemoteException;

    abstract class Stub extends Binder implements RobotSettings {
        private static final String DESCRIPTOR = "com.miko3.shared.RobotSettings";
        static final int TRANSACTION_getClaudeAccess = 1;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        public static RobotSettings asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof RobotSettings) {
                return (RobotSettings) local;
            }
            return new Proxy(binder);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case TRANSACTION_getClaudeAccess: {
                    data.enforceInterface(DESCRIPTOR);
                    ClaudeAccess access = getClaudeAccess();
                    reply.writeNoException();
                    // Flag, then the three strings only when set up.
                    reply.writeInt(access.isSetUp() ? 1 : 0);
                    if (access.isSetUp()) {
                        reply.writeString(access.baseUrl);
                        reply.writeString(access.apiKey);
                        reply.writeString(access.model);
                    }
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

        private static class Proxy implements RobotSettings {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public ClaudeAccess getClaudeAccess() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(TRANSACTION_getClaudeAccess, data, reply, 0);
                    reply.readException();
                    if (reply.readInt() == 0) {
                        return ClaudeAccess.notSetUp();
                    }
                    String baseUrl = reply.readString();
                    String apiKey = reply.readString();
                    String model = reply.readString();
                    return ClaudeAccess.setUp(baseUrl, apiKey, model);
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }
        }
    }
}
