package android.os;

/** Host-JVM stand-in for android.os.Binder: a local object that answers
 * queryLocalInterface for its descriptor and runs transact() in-thread. */
public class Binder implements IBinder {
    private IInterface owner;
    private String descriptor;

    public void attachInterface(IInterface owner, String descriptor) {
        this.owner = owner;
        this.descriptor = descriptor;
    }

    @Override
    public IInterface queryLocalInterface(String descriptor) {
        return descriptor != null && descriptor.equals(this.descriptor) ? owner : null;
    }

    @Override
    public final boolean transact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        data.rewind();
        return onTransact(code, data, reply, flags);
    }

    protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
        return false;
    }
}
