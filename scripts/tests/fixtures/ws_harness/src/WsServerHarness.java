import android.content.Context;

import com.miko3.shared.HttpRequest;
import com.miko3.shared.RoutingHttpServer;
import com.miko3.shared.WebSocketConnection;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Serves a "/drive-ws" route on the host JVM with the same read-loop shape as
 * mode-remote-control's ModeApp (loop on readText() until null, stop in a
 * finally), so scripts/tests/test_websocket_conformance.py can prove the
 * server side of WebSocketConnection behaves as before U2. Only uses API
 * that existed before U2, so it compiles and passes against both versions.
 *
 * Echoes each text message back as "got:MSG" (the real route sends nothing;
 * the echo is how the test observes what readText() returned) and prints
 * READTEXT_NULL then HANDLER_FINALLY on stdout when the loop ends.
 */
public final class WsServerHarness {
    public static void main(String[] args) throws Exception {
        ServerSocket probe = new ServerSocket(0);
        int port = probe.getLocalPort();
        probe.close();
        RoutingHttpServer server = new RoutingHttpServer(new Context(), port);
        server.websocketRoute("/drive-ws", new RoutingHttpServer.WebSocketHandler() {
            @Override
            public void handle(HttpRequest req, WebSocketConnection ws) throws IOException {
                String msg;
                try {
                    while ((msg = ws.readText()) != null) {
                        ws.sendText("got:" + msg);
                    }
                    say("READTEXT_NULL");
                } catch (IOException e) {
                    say("READTEXT_IOEXCEPTION " + e.getMessage());
                    throw e;
                } finally {
                    say("HANDLER_FINALLY");
                }
            }
        });
        Thread t = new Thread(server, "harness-server");
        t.setDaemon(true);
        t.start();
        for (int i = 0; i < 100; i++) {
            try {
                new Socket("127.0.0.1", port).close();
                break;
            } catch (IOException notYet) {
                Thread.sleep(50);
            }
        }
        say("LISTENING " + port);
        // Runs until the test kills the process.
        t.join();
    }

    static synchronized void say(String line) {
        System.out.println(line);
        System.out.flush();
    }
}
