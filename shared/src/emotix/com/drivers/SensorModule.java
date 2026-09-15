package emotix.com.drivers;

/**
 * Minimal re-declaration of ServiceExam's own emotix.com.drivers.SensorModule
 * (tools/serviceexam_jadx/sources/emotix/com/drivers/SensorModule.java), loading
 * the SAME system-installed /system/lib64/libmiko_drivers.so ServiceExam itself
 * uses instead of reimplementing its UART protocol in pure Java.
 *
 * JNI binds native methods to the exact fully-qualified class name
 * ("Java_emotix_com_drivers_SensorModule_xxx"), so this class MUST live at
 * emotix.com.drivers.SensorModule for the existing symbols in libmiko_drivers.so
 * to resolve against it -- confirmed via nm -D that this class name is exactly
 * what the .so exports, and that /system/lib64/libmiko_drivers.so is
 * world-readable (confirmed live: `run-as com.miko3.mode.remotecontrol cat
 * /system/lib64/libmiko_drivers.so` succeeds).
 *
 * Only the UART-relevant natives are declared here; SensorModule.init()'s real
 * sequence (createUART(500, path) -> initUART(handle)) is replicated in
 * connectUart() below, skipping initGPIO()'s `chmod 777 /dev/ttyS2` via su
 * (already unnecessary -- confirmed elsewhere this device node is already
 * world-writable).
 */
public class SensorModule {
    public native long createUART(int bufSize, String path);

    public native boolean initUART(long handle);

    public native boolean writeUART(long handle, byte[] data);

    public native byte[] readUART(long handle);

    public native void resetUART(long handle);

    private long handle;

    static {
        // System.load() with the absolute /system/lib64 path is blocked by Android's
        // linker namespace isolation even though the file is world-readable
        // (confirmed live: UnsatisfiedLinkError, "not accessible for the namespace
        // classloader-namespace") -- loadLibrary() against our own bundled copy
        // (see build.py) resolves within this app's own classloader namespace instead.
        System.loadLibrary("miko_drivers");
    }

    /** Mirrors the real SensorModule.init()'s UART-only portion. */
    public boolean connectUart(String path) {
        handle = createUART(500, path);
        return initUART(handle);
    }

    public boolean write(byte[] data) {
        return writeUART(handle, data);
    }

    public byte[] read() {
        return readUART(handle);
    }

    public void close() {
        resetUART(handle);
    }
}
