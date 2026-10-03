package android.app;

import android.content.Context;

/** Host stub: records the volume control stream. */
public class Activity extends Context {
    public int volumeControlStream = Integer.MIN_VALUE;

    public final void setVolumeControlStream(int streamType) {
        volumeControlStream = streamType;
    }
}
