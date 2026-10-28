package android.view;

/** Host stub with the platform's constant values (checked against android.jar by the test). */
public class KeyEvent {
    public static final int ACTION_DOWN = 0;
    public static final int ACTION_UP = 1;
    public static final int KEYCODE_VOLUME_UP = 24;
    public static final int KEYCODE_VOLUME_DOWN = 25;
    public static final int KEYCODE_MUTE = 91;
    public static final int KEYCODE_VOLUME_MUTE = 164;
    public static final int KEYCODE_BACK = 4;
    public static final int KEYCODE_POWER = 26;

    private final int action;
    private final int code;
    private final int repeat;

    public KeyEvent(int action, int code) {
        this(action, code, 0);
    }

    public KeyEvent(int action, int code, int repeat) {
        this.action = action;
        this.code = code;
        this.repeat = repeat;
    }

    public final int getAction() {
        return action;
    }

    public final int getKeyCode() {
        return code;
    }

    public final int getRepeatCount() {
        return repeat;
    }
}
