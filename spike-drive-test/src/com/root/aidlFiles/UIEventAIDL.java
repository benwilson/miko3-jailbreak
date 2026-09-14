package com.root.aidlFiles;

import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Parcel;
import android.os.RemoteException;

/* JADX INFO: loaded from: classes.dex */
public interface UIEventAIDL extends IInterface {
    void UIEvent(String str) throws RemoteException;

    void executeCommand(String str) throws RemoteException;

    void init(ExpressionEventAIDL expressionEventAIDL, TouchEventAIDL touchEventAIDL, UIDataAIDL uIDataAIDL) throws RemoteException;

    public static abstract class Stub extends Binder implements UIEventAIDL {
        private static final String DESCRIPTOR = "com.root.aidlFiles.UIEventAIDL";
        static final int TRANSACTION_UIEvent = 2;
        static final int TRANSACTION_executeCommand = 3;
        static final int TRANSACTION_init = 1;

        @Override // android.os.IInterface
        public IBinder asBinder() {
            return this;
        }

        public Stub() {
            attachInterface(this, DESCRIPTOR);
        }

        public static UIEventAIDL asInterface(IBinder iBinder) {
            if (iBinder == null) {
                return null;
            }
            IInterface iInterfaceQueryLocalInterface = iBinder.queryLocalInterface(DESCRIPTOR);
            if (iInterfaceQueryLocalInterface != null && (iInterfaceQueryLocalInterface instanceof UIEventAIDL)) {
                return (UIEventAIDL) iInterfaceQueryLocalInterface;
            }
            return new Proxy(iBinder);
        }

        @Override // android.os.Binder
        public boolean onTransact(int i, Parcel parcel, Parcel parcel2, int i2) throws RemoteException {
            if (i == TRANSACTION_init) {
                parcel.enforceInterface(DESCRIPTOR);
                init(ExpressionEventAIDL.Stub.asInterface(parcel.readStrongBinder()), TouchEventAIDL.Stub.asInterface(parcel.readStrongBinder()), UIDataAIDL.Stub.asInterface(parcel.readStrongBinder()));
                parcel2.writeNoException();
                return true;
            }
            if (i == TRANSACTION_UIEvent) {
                parcel.enforceInterface(DESCRIPTOR);
                UIEvent(parcel.readString());
                parcel2.writeNoException();
                return true;
            }
            if (i != TRANSACTION_executeCommand) {
                if (i == 1598968902) {
                    parcel2.writeString(DESCRIPTOR);
                    return true;
                }
                return super.onTransact(i, parcel, parcel2, i2);
            }
            parcel.enforceInterface(DESCRIPTOR);
            executeCommand(parcel.readString());
            parcel2.writeNoException();
            return true;
        }

        private static class Proxy implements UIEventAIDL {
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

            @Override // com.root.aidlFiles.UIEventAIDL
            public void init(ExpressionEventAIDL expressionEventAIDL, TouchEventAIDL touchEventAIDL, UIDataAIDL uIDataAIDL) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeStrongBinder(expressionEventAIDL != null ? expressionEventAIDL.asBinder() : null);
                    parcelObtain.writeStrongBinder(touchEventAIDL != null ? touchEventAIDL.asBinder() : null);
                    parcelObtain.writeStrongBinder(uIDataAIDL != null ? uIDataAIDL.asBinder() : null);
                    this.mRemote.transact(Stub.TRANSACTION_init, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.root.aidlFiles.UIEventAIDL
            public void UIEvent(String str) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    this.mRemote.transact(Stub.TRANSACTION_UIEvent, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }

            @Override // com.root.aidlFiles.UIEventAIDL
            public void executeCommand(String str) throws RemoteException {
                Parcel parcelObtain = Parcel.obtain();
                Parcel parcelObtain2 = Parcel.obtain();
                try {
                    parcelObtain.writeInterfaceToken(Stub.DESCRIPTOR);
                    parcelObtain.writeString(str);
                    this.mRemote.transact(Stub.TRANSACTION_executeCommand, parcelObtain, parcelObtain2, 0);
                    parcelObtain2.readException();
                } finally {
                    parcelObtain2.recycle();
                    parcelObtain.recycle();
                }
            }
        }
    }
}
