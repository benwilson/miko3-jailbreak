package android.os;

import java.util.ArrayList;
import java.util.List;

/** Host-JVM stand-in for android.os.Parcel: an in-memory list of values,
 * read back in the order written. Exceptions are not marshalled. */
public final class Parcel {
    private final List<Object> values = new ArrayList<Object>();
    private int pos;

    public static Parcel obtain() {
        return new Parcel();
    }

    public void recycle() {
    }

    void rewind() {
        pos = 0;
    }

    public void writeInterfaceToken(String descriptor) {
        values.add(descriptor);
    }

    public void enforceInterface(String descriptor) {
        Object v = values.get(pos++);
        if (!descriptor.equals(v)) {
            throw new SecurityException("interface token " + v + " is not " + descriptor);
        }
    }

    public void writeString(String s) {
        values.add(s);
    }

    public String readString() {
        return (String) values.get(pos++);
    }

    public void writeStrongBinder(IBinder b) {
        values.add(b);
    }

    public IBinder readStrongBinder() {
        return (IBinder) values.get(pos++);
    }

    public void writeNoException() {
    }

    public void readException() {
    }
}
