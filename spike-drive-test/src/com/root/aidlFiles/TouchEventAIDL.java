package com.root.aidlFiles;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/* JADX INFO: loaded from: classes.dex */
public interface TouchEventAIDL extends IInterface {
    void init(AnalyticsAIDL analyticsAIDL, GameControllerAIDL gameControllerAIDL) throws RemoteException;

    void touchEvent(String str) throws RemoteException;

    public static abstract class Stub extends Binder implements TouchEventAIDL {
        private static final String DESCRIPTOR = "com.root.aidlFiles.TouchEventAIDL";
        static final int TRANSACTION_init = 2;
        static final int TRANSACTION_touchEvent = 1;

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static TouchEventAIDL asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface iInterfaceQueryLocalInterface = iBinder.queryLocalInterface(DESCRIPTOR);
            if (iInterfaceQueryLocalInterface != null && (iInterfaceQueryLocalInterface instanceof TouchEventAIDL)) {
                return (TouchEventAIDL) iInterfaceQueryLocalInterface;
            }
            return new Proxy(iBinder);
        }

        @Override // android.os.Binder
        public boolean onTransact(int i, Parcel parcel, Parcel parcel2, int i2) throws RemoteException {
            if (i == TRANSACTION_touchEvent) {
                parcel.enforceInterface(DESCRIPTOR);
                touchEvent(parcel.readString());
                parcel2.writeNoException();
                return true;
            }
            if (i != TRANSACTION_init) {
                if (i == 1598968902) {
                    parcel2.writeString(DESCRIPTOR);
                    return true;
                }
                return super.onTransact(i, parcel, parcel2, i2);
            }
            parcel.enforceInterface(DESCRIPTOR);
            init(AnalyticsAIDL.Stub.asInterface(parcel.readStrongBinder()), GameControllerAIDL.Stub.asInterface(parcel.readStrongBinder()));
            parcel2.writeNoException();
            return true;
        }

        private static class Proxy implements TouchEventAIDL {
            private IBinder mRemote;

            public String getInterfaceDescriptor() {
                return Stub.DESCRIPTOR;
            }

            Proxy(IBinder iBinder) {
                this.mRemote = iBinder;
            }

            @Override // android.os.IInterface
            public IBinder asBinder() {
                return this.mRemote;
            }

            @Override // com.root.aidlFiles.TouchEventAIDL
            public void touchEvent(String str) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    this.mRemote.transact(Stub.TRANSACTION_touchEvent, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.root.aidlFiles.TouchEventAIDL
            public void init(AnalyticsAIDL analyticsAIDL, GameControllerAIDL gameControllerAIDL) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(analyticsAIDL != null ? analyticsAIDL.asBinder() : null);
                    parcelObtain.writeStrongBinder(gameControllerAIDL != null ? gameControllerAIDL.asBinder() : null);
                    this.mRemote.transact(Stub.TRANSACTION_init, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }
        }
    }
}
