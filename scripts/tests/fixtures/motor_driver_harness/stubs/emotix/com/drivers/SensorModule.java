package emotix.com.drivers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Host stub of the JNI UART: records every frame written and answers with a fixed reply. */
public class SensorModule {
    /** Every frame any instance wrote, in order. */
    public static final List<byte[]> WRITES = Collections.synchronizedList(new ArrayList<byte[]>());

    public boolean connectUart(String path) {
        return true;
    }

    public boolean write(byte[] data) {
        WRITES.add(data.clone());
        return true;
    }

    public byte[] read() {
        return "CPL=1".getBytes();
    }

    public void close() {
    }
}
