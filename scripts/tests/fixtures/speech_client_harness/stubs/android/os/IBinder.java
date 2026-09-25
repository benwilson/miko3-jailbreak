package android.os;

/** Host-JVM stand-in for android.os.IBinder: in-process transactions only. */
public interface IBinder {
    int INTERFACE_TRANSACTION = ('_' << 24) | ('N' << 16) | ('T' << 8) | 'F';
    int FLAG_ONEWAY = 0x00000001;

    IInterface queryLocalInterface(String descriptor);

    boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException;
}
