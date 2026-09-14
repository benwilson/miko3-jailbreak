package com.miko3.shared;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/**
 * Hand-written Binder interface (Stub/Proxy, no .aidl compiler in this
 * build) for the drive-lease arbitration coordinator hosted by the
 * launcher (see DriveLeaseService). Matches the shape of the vendored
 * ServiceExam AIDL stubs (shared/src/com/root/aidlFiles) so the DESCRIPTOR
 * contract lines up the same way. Governs R3/R13-R15 per KTD3/KTD3b.
 */
public interface DriveLease extends IInterface {
    /**
     * Requests the lease for clientId. deathToken is the caller's own
     * IBinder, kept alive only for as long as the caller's process is —
     * the coordinator calls linkToDeath() on it so a crashed holder's
     * lease is released the instant the process dies, not just when the
     * TTL next expires. Returns true if the lease was granted (unheld, or
     * already held by this same clientId), false if held by someone else.
     */
    boolean acquire(IBinder deathToken, String clientId) throws RemoteException;

    /** Keepalive for a held lease; a no-op if clientId does not hold it. */
    void renew(String clientId) throws RemoteException;

    /** Releases the lease if held by clientId; a no-op otherwise. */
    void release(String clientId) throws RemoteException;

    /** The current holder's clientId, or null if unheld. */
    String getHolder() throws RemoteException;

    abstract class Stub extends Binder implements DriveLease {
        private static final String DESCRIPTOR = "com.miko3.shared.DriveLease";
        static final int TRANSACTION_acquire = 1;
        static final int TRANSACTION_renew = 2;
        static final int TRANSACTION_release = 3;
        static final int TRANSACTION_getHolder = 4;

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        @Override
        public IBinder asBinder() {
            return this;
        }

        public static DriveLease asInterface(IBinder binder) {
            if (binder == null) {
                return null;
            }
            IInterface local = binder.queryLocalInterface(DESCRIPTOR);
            if (local instanceof DriveLease) {
                return (DriveLease) local;
            }
            return new Proxy(binder);
        }

        @Override
        public boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case TRANSACTION_acquire: {
                    data.enforceInterface(DESCRIPTOR);
                    IBinder deathToken = data.readStrongBinder();
                    String clientId = data.readString();
                    boolean granted = acquire(deathToken, clientId);
                    reply.writeNoException();
                    reply.writeInt(granted ? 1 : 0);
                    return true;
                }
                case TRANSACTION_renew: {
                    data.enforceInterface(DESCRIPTOR);
                    renew(data.readString());
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_release: {
                    data.enforceInterface(DESCRIPTOR);
                    release(data.readString());
                    reply.writeNoException();
                    return true;
                }
                case TRANSACTION_getHolder: {
                    data.enforceInterface(DESCRIPTOR);
                    String holder = getHolder();
                    reply.writeNoException();
                    reply.writeString(holder);
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

        private static class Proxy implements DriveLease {
            private final IBinder remote;

            Proxy(IBinder remote) {
                this.remote = remote;
            }

            @Override
            public IBinder asBinder() {
                return remote;
            }

            @Override
            public boolean acquire(IBinder deathToken, String clientId) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeStrongBinder(deathToken);
                    data.writeString(clientId);
                    remote.transact(TRANSACTION_acquire, data, reply, 0);
                    reply.readException();
                    return reply.readInt() != 0;
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void renew(String clientId) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(clientId);
                    remote.transact(TRANSACTION_renew, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public void release(String clientId) throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    data.writeString(clientId);
                    remote.transact(TRANSACTION_release, data, reply, 0);
                    reply.readException();
                } finally {
                    reply.recycle();
                    data.recycle();
                }
            }

            @Override
            public String getHolder() throws RemoteException {
                Parcel data = Parcel.obtain();
                Parcel reply = Parcel.obtain();
                try {
                    data.writeInterfaceToken(DESCRIPTOR);
                    remote.transact(TRANSACTION_getHolder, data, reply, 0);
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
