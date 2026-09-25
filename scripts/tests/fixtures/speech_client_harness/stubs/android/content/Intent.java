package android.content;

/** Host-JVM stand-in for android.content.Intent: an action and a package. */
public class Intent {
    private final String action;
    private String pkg;

    public Intent(String action) {
        this.action = action;
    }

    public Intent setPackage(String pkg) {
        this.pkg = pkg;
        return this;
    }

    public String getAction() {
        return action;
    }

    public String getPackage() {
        return pkg;
    }
}
