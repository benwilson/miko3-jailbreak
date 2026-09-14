package com.miko3.shared;

import java.io.IOException;
import java.io.InputStream;

/** Reads one CRLF- or LF-terminated line from a raw stream — shared by
 * RoutingHttpServer (request line/headers) and ChunkedInputStream (chunk-size
 * lines), which otherwise each reimplemented the identical loop. */
final class LineReader {
    private LineReader() {
    }

    /** Returns the line without its terminator, or null on immediate EOF. */
    static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        boolean any = false;
        while ((c = in.read()) != -1) {
            any = true;
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        return any ? sb.toString() : null;
    }
}
