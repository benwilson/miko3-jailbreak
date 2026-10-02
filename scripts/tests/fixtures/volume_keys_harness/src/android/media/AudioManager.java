package android.media;

import java.util.ArrayList;
import java.util.List;

/** Host stub with the platform's constant values (checked against android.jar by the test). */
public class AudioManager {
    public static final int STREAM_MUSIC = 3;
    public static final int ADJUST_RAISE = 1;
    public static final int ADJUST_LOWER = -1;
    public static final int ADJUST_TOGGLE_MUTE = 101;
    public static final int FLAG_SHOW_UI = 1;

    /** "stream,direction,flags" per adjustStreamVolume call. */
    public final List<String> calls = new ArrayList<String>();
    public boolean muted;
    public int volume = 10;

    public void adjustStreamVolume(int streamType, int direction, int flags) {
        calls.add(streamType + "," + direction + "," + flags);
    }

    public boolean isStreamMute(int streamType) {
        return streamType == STREAM_MUSIC && muted;
    }

    public int getStreamVolume(int streamType) {
        return streamType == STREAM_MUSIC ? volume : 0;
    }
}
