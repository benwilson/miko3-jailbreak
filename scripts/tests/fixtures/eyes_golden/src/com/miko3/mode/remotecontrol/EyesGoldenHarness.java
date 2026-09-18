package com.miko3.mode.remotecontrol;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Host-JVM dump of the remote-control mode's device-view page (U7), driven by
 * scripts/tests/test_eyes_page_golden.py. Lives in the mode's package so it can
 * reach the package-private DeviceViewPage; compiles against
 * mode-remote-control/src and shared/src only (neither class touches android.*).
 *
 * Writes DeviceViewPage.HTML as UTF-8 bytes — exactly what ModeApp's
 * sendText() puts on the wire — to the path given as the only argument.
 */
public final class EyesGoldenHarness {
    private EyesGoldenHarness() {
    }

    public static void main(String[] args) throws Exception {
        OutputStream out = new FileOutputStream(args[0]);
        try {
            out.write(DeviceViewPage.HTML.getBytes(StandardCharsets.UTF_8));
        } finally {
            out.close();
        }
    }
}
