package android.os;

/** Host-JVM stand-in for android.os.RemoteException. */
public class RemoteException extends Exception {
    public RemoteException() {
    }

    public RemoteException(String message) {
        super(message);
    }
}
