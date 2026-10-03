package android.content;

/** Host stub: only what VolumeKeys touches. */
public class Context {
    public static final String AUDIO_SERVICE = "audio";

    public Object audio;

    public Object getSystemService(String name) {
        return AUDIO_SERVICE.equals(name) ? audio : null;
    }
}
