package com.root.aidlFiles;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/* JADX INFO: loaded from: classes.dex */
public interface GameControllerAIDL extends IInterface {
    void GameEvent(String str) throws RemoteException;

    boolean getConnectivityInitialisationStatus(String str) throws RemoteException;

    String getData(String str) throws RemoteException;

    public static abstract class Stub extends Binder implements GameControllerAIDL {
        private static final String DESCRIPTOR = "com.root.aidlFiles.GameControllerAIDL";
        static final int TRANSACTION_GameEvent = 1;
        static final int TRANSACTION_getConnectivityInitialisationStatus = 2;
        static final int TRANSACTION_getData = 3;

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static GameControllerAIDL asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface iInterfaceQueryLocalInterface = iBinder.queryLocalInterface(DESCRIPTOR);
            if (iInterfaceQueryLocalInterface != null && (iInterfaceQueryLocalInterface instanceof GameControllerAIDL)) {
                return (GameControllerAIDL) iInterfaceQueryLocalInterface;
            }
            return new Proxy(iBinder);
        }

        @Override // android.os.Binder
        public boolean onTransact(int i, Parcel parcel, Parcel parcel2, int i2) throws RemoteException {
            if (i == TRANSACTION_GameEvent) {
                parcel.enforceInterface(DESCRIPTOR);
                GameEvent(parcel.readString());
                parcel2.writeNoException();
                return true;
            }
            if (i == TRANSACTION_getConnectivityInitialisationStatus) {
                parcel.enforceInterface(DESCRIPTOR);
                boolean connectivityInitialisationStatus = getConnectivityInitialisationStatus(parcel.readString());
                parcel2.writeNoException();
                parcel2.writeInt(connectivityInitialisationStatus ? 1 : 0);
                return true;
            }
            if (i != TRANSACTION_getData) {
                if (i == 1598968902) {
                    parcel2.writeString(DESCRIPTOR);
                    return true;
                }
                return super.onTransact(i, parcel, parcel2, i2);
            }
            parcel.enforceInterface(DESCRIPTOR);
            String data = getData(parcel.readString());
            parcel2.writeNoException();
            parcel2.writeString(data);
            return true;
        }

        private static class Proxy implements GameControllerAIDL {
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

            @Override // com.root.aidlFiles.GameControllerAIDL
            public void GameEvent(String str) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    this.mRemote.transact(Stub.TRANSACTION_GameEvent, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.root.aidlFiles.GameControllerAIDL
            public boolean getConnectivityInitialisationStatus(String str) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    this.mRemote.transact(Stub.TRANSACTION_getConnectivityInitialisationStatus, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readInt() != 0;
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.root.aidlFiles.GameControllerAIDL
            public String getData(String str) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    this.mRemote.transact(Stub.TRANSACTION_getData, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                    return parcelObtain2.readString();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }
        }
    }
}
