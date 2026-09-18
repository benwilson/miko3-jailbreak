package android.content;

/** Host-JVM stand-in for android.content.Context: RoutingHttpServer's
 * constructor only calls getApplicationContext(). */
public class Context {
    public Context getApplicationContext() {
        return this;
    }
}
