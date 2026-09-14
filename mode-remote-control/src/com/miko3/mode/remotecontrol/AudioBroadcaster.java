package com.miko3.mode.remotecontrol;

import java.io.IOException;
import java.io.OutputStream;

/**
 * Fans the robot's own microphone's raw PCM out to every connected
 * listener (KTD8), the same one-broadcaster/N-readers shape as
 * MjpegBroadcaster (U6) — structurally analogous, not multipart-framed
 * since this is a single continuous byte stream rather than discrete
 * frames.
 */
final class AudioBroadcaster extends SubscriberBroadcaster {
    AudioBroadcaster() {
        super("AudioBroadcaster");
    }

    void publishChunk(final byte[] pcm, final int len) {
        broadcast(new Writer() {
            @Override
            public void write(OutputStream out) throws IOException {
                out.write(pcm, 0, len);
                out.flush();
            }
        });
    }
}
