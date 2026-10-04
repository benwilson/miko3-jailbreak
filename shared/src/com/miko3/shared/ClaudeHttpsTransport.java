package com.miko3.shared;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * ClaudeApi's real transport (settings plan U3, KTD3): one HttpsURLConnection
 * per request, trusting the system CAs.
 *
 * Android's connect and read timeouts default to infinite, so both are set;
 * redirects are never followed, so a 3xx comes back to ClaudeApi as an error
 * and the key is never replayed to another host. Error bodies are read from
 * the error stream, which is where HttpURLConnection puts them.
 *
 * Logs nothing: the request carries the key. java.net and javax.net.ssl only,
 * so it also compiles on the host JVM.
 */
public final class ClaudeHttpsTransport implements ClaudeApi.StreamingTransport {
    private static final int CONNECT_TIMEOUT_MS = 10000;
    /** The default, for a one-token reply or a page of models; a Request may set its own
     * (readTimeoutMs). Anything slower counts as unreachable. */
    private static final int READ_TIMEOUT_MS = 30000;
    /** Far above any models page; a larger body is cut off here. */
    private static final int MAX_BODY_BYTES = 4 * 1024 * 1024;

    @Override
    public ClaudeApi.Response send(ClaudeApi.Request request) throws IOException {
        return exchange(request, null);
    }

    /**
     * Robot 2026-10-02: a 2xx event stream goes to sink line by line as it arrives,
     * under the same timeouts (the read timeout then bounds each wait for the next
     * line); any other answer comes back whole, as send() returns it.
     */
    @Override
    public ClaudeApi.Response stream(ClaudeApi.Request request, ClaudeApi.LineSink sink) throws IOException {
        return exchange(request, sink);
    }

    private ClaudeApi.Response exchange(ClaudeApi.Request request, ClaudeApi.LineSink sink) throws IOException {
        URLConnection opened = new URL(request.url).openConnection();
        if (!(opened instanceof HttpsURLConnection)) {
            throw new IOException("not an https URL"); // normalizeBaseUrl already ensures https
        }
        HttpsURLConnection conn = (HttpsURLConnection) opened;
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(request.readTimeoutMs > 0 ? request.readTimeoutMs : READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(false);
            conn.setUseCaches(false);
            conn.setRequestMethod(request.method);
            for (Map.Entry<String, String> h : request.headers.entrySet()) {
                conn.setRequestProperty(h.getKey(), h.getValue());
            }
            if (request.body != null) {
                byte[] body = request.body.getBytes(StandardCharsets.UTF_8);
                conn.setDoOutput(true);
                conn.setFixedLengthStreamingMode(body.length);
                OutputStream out = conn.getOutputStream();
                try {
                    out.write(body);
                } finally {
                    out.close();
                }
            }
            int status = conn.getResponseCode();
            InputStream in = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String type = conn.getContentType();
            if (sink != null && status >= 200 && status <= 299 && type != null
                    && type.toLowerCase(java.util.Locale.US).startsWith("text/event-stream")) {
                readLines(in, sink);
                return new ClaudeApi.Response(status, "", conn.getHeaderField("retry-after"));
            }
            return new ClaudeApi.Response(status, readBody(in), conn.getHeaderField("retry-after"));
        } catch (RuntimeException e) {
            // HttpURLConnection throws unchecked exceptions for some bad input and
            // broken connections; ClaudeApi only maps IOExceptions. Message dropped:
            // it could quote a header value.
            throw new IOException("request failed: " + e.getClass().getSimpleName());
        } finally {
            conn.disconnect();
        }
    }

    /** An event stream, line by line as it arrives, cut off at MAX_BODY_BYTES characters. */
    private static void readLines(InputStream in, ClaudeApi.LineSink sink) throws IOException {
        if (in == null) {
            return;
        }
        BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        try {
            long seen = 0;
            String line;
            while (seen < MAX_BODY_BYTES && (line = r.readLine()) != null) {
                seen += line.length() + 1;
                sink.line(line);
            }
        } finally {
            r.close();
        }
    }

    /** The body as UTF-8 text, "" when there is none. */
    private static String readBody(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int n;
            while (buf.size() < MAX_BODY_BYTES && (n = in.read(chunk)) != -1) {
                buf.write(chunk, 0, n);
            }
            return new String(buf.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            in.close();
        }
    }
}
