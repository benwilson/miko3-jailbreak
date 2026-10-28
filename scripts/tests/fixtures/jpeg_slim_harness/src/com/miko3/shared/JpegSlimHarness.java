package com.miko3.shared;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * Drives JpegSlim on real camera frames; one PASS/FAIL line per scenario.
 *
 * Args: [--truncated <file>] <frame.jpg>... (the real frames to slim). With
 * --measure as the first arg it prints size and timing stats instead.
 */
public final class JpegSlimHarness {
    public static void main(String[] args) throws Exception {
        List<byte[]> frames = new ArrayList<byte[]>();
        byte[] truncated = null;
        boolean measure = false;
        for (int i = 0; i < args.length; i++) {
            if ("--measure".equals(args[i])) {
                measure = true;
            } else if ("--truncated".equals(args[i])) {
                truncated = Files.readAllBytes(new File(args[++i]).toPath());
            } else {
                frames.add(Files.readAllBytes(new File(args[i]).toPath()));
            }
        }
        if (frames.isEmpty()) {
            System.out.println("FAIL setup: no frames given");
            return;
        }
        if (measure) {
            measure(frames);
            return;
        }
        check("a_real_frame_slims_smaller_and_decodes_to_identical_pixels", identicalPixels(frames));
        check("drops_app1_to_app15_and_com_keeps_app0", dropsAppAndCom(frames.get(0)));
        check("keeps_tables_frame_scan_and_eoi_byte_for_byte", keepsTheRest(frames));
        check("drops_the_hardware_zero_padding_after_eoi", dropsPadding(frames));
        check("malformed_or_truncated_input_is_returned_unchanged", malformed(frames.get(0), truncated));
        check("the_sample_frames_carry_orientation_1_so_app1_goes", orientationOne(frames));
        check("a_non_1_orientation_survives_as_a_minimal_app1", keepsOrientation(frames.get(0)));
        check("an_already_slim_jpeg_comes_back_as_is", idempotent(frames.get(0)));
        check("a_request_image_block_carries_the_slimmed_bytes", requestBlock(frames.get(0)));
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }

    // ---- scenarios ----

    private static String identicalPixels(List<byte[]> frames) throws Exception {
        for (int k = 0; k < Math.min(5, frames.size()); k++) {
            byte[] in = frames.get(k);
            byte[] out = JpegSlim.slim(in);
            if (out.length >= in.length) {
                return "frame " + k + " not smaller: " + in.length + " -> " + out.length;
            }
            BufferedImage a = ImageIO.read(new ByteArrayInputStream(in));
            BufferedImage b = ImageIO.read(new ByteArrayInputStream(out));
            if (a == null || b == null) {
                return "frame " + k + " did not decode (orig " + (a != null) + ", slim " + (b != null) + ")";
            }
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
                return "frame " + k + " size changed";
            }
            int w = a.getWidth(), h = a.getHeight();
            if (!Arrays.equals(a.getRGB(0, 0, w, h, null, 0, w), b.getRGB(0, 0, w, h, null, 0, w))) {
                return "frame " + k + " pixels differ";
            }
        }
        return null;
    }

    private static String dropsAppAndCom(byte[] frame) throws Exception {
        // A real frame with an APP0/JFIF, an APP2, an APP15 and a COM spliced in front.
        byte[] app0 = segment(0xE0, "JFIF\0\1\1\0\0\1\0\1\0\0".getBytes("ISO-8859-1"));
        byte[] app2 = segment(0xE2, "ICC_PROFILE\0junk".getBytes("ISO-8859-1"));
        byte[] app15 = segment(0xEF, "vendor".getBytes("ISO-8859-1"));
        byte[] com = segment(0xFE, "a comment".getBytes("ISO-8859-1"));
        byte[] in = cat(soi(), app0, app2, app15, com, Arrays.copyOfRange(frame, 2, frame.length));
        List<Integer> markers = headerMarkers(JpegSlim.slim(in));
        if (!markers.contains(0xE0)) {
            return "APP0 dropped: " + hex(markers);
        }
        for (int m : markers) {
            if ((m >= 0xE1 && m <= 0xEF) || m == 0xFE) {
                return "kept " + Integer.toHexString(m) + ": " + hex(markers);
            }
        }
        if (!markers.containsAll(Arrays.asList(0xDB, 0xC0, 0xC4, 0xDA))) {
            return "lost a table/frame/scan: " + hex(markers);
        }
        return null;
    }

    /** Oracle: SOI + every non-APPn/COM header segment verbatim + SOS..EOI verbatim. */
    private static String keepsTheRest(List<byte[]> frames) {
        for (int k = 0; k < frames.size(); k++) {
            byte[] in = frames.get(k);
            ByteArrayOutputStream want = new ByteArrayOutputStream();
            want.write(in, 0, 2);
            int i = 2;
            while (true) {
                int m = in[i + 1] & 0xFF;
                int len = ((in[i + 2] & 0xFF) << 8) | (in[i + 3] & 0xFF);
                if (m == 0xDA) {
                    int eoi = indexOf(in, i, (byte) 0xFF, (byte) 0xD9);
                    want.write(in, i, eoi + 2 - i);
                    break;
                }
                boolean drop = (m >= 0xE1 && m <= 0xEF) || m == 0xFE;
                if (!drop) {
                    want.write(in, i, 2 + len);
                }
                i += 2 + len;
            }
            if (!Arrays.equals(want.toByteArray(), JpegSlim.slim(in))) {
                return "frame " + k + " differs from the segment-by-segment oracle";
            }
        }
        return null;
    }

    private static String dropsPadding(List<byte[]> frames) {
        for (byte[] in : frames) {
            byte[] out = JpegSlim.slim(in);
            if ((out[out.length - 2] & 0xFF) != 0xFF || (out[out.length - 1] & 0xFF) != 0xD9) {
                return "slimmed frame does not end at EOI";
            }
        }
        return null;
    }

    private static String malformed(byte[] frame, byte[] realTruncated) {
        List<byte[]> bad = new ArrayList<byte[]>();
        bad.add(new byte[0]);
        bad.add(new byte[] {(byte) 0xFF});
        bad.add(new byte[] {(byte) 0xFF, (byte) 0xD8});
        bad.add(new byte[] {(byte) 0xFF, (byte) 0xD8, 1, 2, 3});
        bad.add(new byte[4000]);
        bad.add("not a jpeg at all".getBytes());
        // A segment length that runs past the end.
        bad.add(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x7F, 0x00, 1, 2});
        // A length below 2.
        bad.add(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00, 0x01, (byte) 0xFF, (byte) 0xD9});
        // EOI before any scan.
        bad.add(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00, 0x02, (byte) 0xFF, (byte) 0xD9});
        // Real frame cut inside the vendor APP blocks, and cut inside the scan (no EOI).
        bad.add(Arrays.copyOf(frame, 100000));
        int sos = sosOffset(frame);
        bad.add(Arrays.copyOf(frame, sos + (indexOf(frame, sos, (byte) 0xFF, (byte) 0xD9) - sos) / 2));
        if (realTruncated != null) {
            bad.add(realTruncated);
        }
        for (int k = 0; k < bad.size(); k++) {
            byte[] in = bad.get(k);
            byte[] copy = in.clone();
            byte[] out = JpegSlim.slim(in);
            if (out != in) {
                return "case " + k + " (" + in.length + " bytes) was not returned as the same array";
            }
            if (!Arrays.equals(copy, in)) {
                return "case " + k + " input was modified";
            }
        }
        if (JpegSlim.slim(null) != null) {
            return "null in, non-null out";
        }
        return null;
    }

    private static String orientationOne(List<byte[]> frames) {
        for (int k = 0; k < frames.size(); k++) {
            int o = JpegSlim.orientation(frames.get(k));
            if (o != 1) {
                return "frame " + k + " orientation " + o;
            }
            if (headerMarkers(JpegSlim.slim(frames.get(k))).contains(0xE1)) {
                return "frame " + k + " kept its APP1";
            }
        }
        return null;
    }

    private static String keepsOrientation(byte[] frame) throws Exception {
        byte[] body = Arrays.copyOfRange(JpegSlim.slim(frame), 2, JpegSlim.slim(frame).length);
        for (boolean little : new boolean[] {true, false}) {
            byte[] in = cat(soi(), segment(0xE1, exif(little, 6)), segment(0xE5, new byte[3000]), body);
            if (JpegSlim.orientation(in) != 6) {
                return "fixture orientation not read (little=" + little + ")";
            }
            byte[] out = JpegSlim.slim(in);
            if (JpegSlim.orientation(out) != 6) {
                return "orientation lost (little=" + little + "): " + JpegSlim.orientation(out);
            }
            int app1 = 0;
            for (int m : headerMarkers(out)) {
                if (m == 0xE1) {
                    app1++;
                }
                if (m == 0xE5) {
                    return "APP5 kept";
                }
            }
            if (app1 != 1) {
                return app1 + " APP1 segments";
            }
            if (out.length > body.length + 2 + 64) {
                return "APP1 not minimal: " + (out.length - body.length - 2) + " header bytes";
            }
            if (ImageIO.read(new ByteArrayInputStream(out)) == null) {
                return "does not decode";
            }
        }
        return null;
    }

    private static String idempotent(byte[] frame) {
        byte[] once = JpegSlim.slim(frame);
        byte[] twice = JpegSlim.slim(once);
        return twice == once ? null : "second slim returned a new array (" + twice.length + " vs " + once.length + ")";
    }

    @SuppressWarnings("unchecked")
    private static String requestBlock(byte[] frame) {
        Map<String, Object> block = ClaudeApi.jpegBlock(frame);
        Map<String, Object> source = (Map<String, Object>) block.get("source");
        if (!"image/jpeg".equals(source.get("media_type"))) {
            return "media_type " + source.get("media_type");
        }
        byte[] sent = Base64.getDecoder().decode((String) source.get("data"));
        if (!Arrays.equals(sent, JpegSlim.slim(frame))) {
            return "sent " + sent.length + " bytes, slimmed is " + JpegSlim.slim(frame).length
                    + ", original " + frame.length;
        }
        return sent.length < frame.length ? null : "not smaller";
    }

    // ---- measurement ----

    private static void measure(List<byte[]> frames) {
        int n = frames.size();
        long[] before = new long[n], after = new long[n];
        for (int r = 0; r < 20; r++) { // warm the JIT
            for (byte[] f : frames) {
                JpegSlim.slim(f);
            }
        }
        long t0 = System.nanoTime();
        int reps = 50;
        for (int r = 0; r < reps; r++) {
            for (int k = 0; k < n; k++) {
                after[k] = JpegSlim.slim(frames.get(k)).length;
            }
        }
        double usPer = (System.nanoTime() - t0) / 1000.0 / (reps * (double) n);
        for (int k = 0; k < n; k++) {
            before[k] = frames.get(k).length;
        }
        System.out.println(String.format("frames %d", n));
        System.out.println(String.format("before mean %.1f KB p95 %.1f KB", mean(before) / 1024, p95(before) / 1024));
        System.out.println(String.format("after  mean %.1f KB p95 %.1f KB", mean(after) / 1024, p95(after) / 1024));
        System.out.println(String.format("slim time %.1f us/frame (host, warm)", usPer));
    }

    private static double mean(long[] v) {
        double s = 0;
        for (long x : v) {
            s += x;
        }
        return s / v.length;
    }

    private static double p95(long[] v) {
        long[] s = v.clone();
        Arrays.sort(s);
        return s[(int) Math.ceil(0.95 * s.length) - 1];
    }

    // ---- helpers ----

    private static byte[] soi() {
        return new byte[] {(byte) 0xFF, (byte) 0xD8};
    }

    private static byte[] segment(int marker, byte[] payload) {
        byte[] s = new byte[4 + payload.length];
        s[0] = (byte) 0xFF;
        s[1] = (byte) marker;
        s[2] = (byte) ((payload.length + 2) >> 8);
        s[3] = (byte) (payload.length + 2);
        System.arraycopy(payload, 0, s, 4, payload.length);
        return s;
    }

    /** An Exif APP1 payload whose IFD0 has a few tags, Orientation among them. */
    private static byte[] exif(boolean little, int orientation) {
        int[][] tags = {{0x010F, 2, 4, 0x6F6B694D}, {0x0112, 3, 1, orientation}, {0x011A, 4, 1, 72}};
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write('E'); b.write('x'); b.write('i'); b.write('f'); b.write(0); b.write(0);
        b.write(little ? 'I' : 'M'); b.write(little ? 'I' : 'M');
        put(b, little, 42, 2);
        put(b, little, 8, 4);
        put(b, little, tags.length, 2);
        for (int[] t : tags) {
            put(b, little, t[0], 2);
            put(b, little, t[1], 2);
            put(b, little, t[2], 4);
            if (t[1] == 3) {
                put(b, little, t[3], 2);
                put(b, little, 0, 2);
            } else {
                put(b, little, t[3], 4);
            }
        }
        put(b, little, 0, 4);
        return b.toByteArray();
    }

    private static void put(ByteArrayOutputStream b, boolean little, int v, int n) {
        for (int i = 0; i < n; i++) {
            int shift = little ? 8 * i : 8 * (n - 1 - i);
            b.write((v >> shift) & 0xFF);
        }
    }

    private static byte[] cat(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            b.write(p, 0, p.length);
        }
        return b.toByteArray();
    }

    /** The markers of the header segments, SOI excluded, up to and including SOS. */
    private static List<Integer> headerMarkers(byte[] jpeg) {
        List<Integer> out = new ArrayList<Integer>();
        int i = 2;
        while (i + 4 <= jpeg.length) {
            int m = jpeg[i + 1] & 0xFF;
            out.add(m);
            if (m == 0xDA) {
                break;
            }
            i += 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
        }
        return out;
    }

    private static int sosOffset(byte[] jpeg) {
        int i = 2;
        while ((jpeg[i + 1] & 0xFF) != 0xDA) {
            i += 2 + (((jpeg[i + 2] & 0xFF) << 8) | (jpeg[i + 3] & 0xFF));
        }
        return i;
    }

    private static int indexOf(byte[] a, int from, byte x, byte y) {
        for (int i = from; i + 1 < a.length; i++) {
            if (a[i] == x && a[i + 1] == y) {
                return i;
            }
        }
        return -1;
    }

    private static String hex(List<Integer> ms) {
        StringBuilder sb = new StringBuilder();
        for (int m : ms) {
            sb.append(Integer.toHexString(m)).append(' ');
        }
        return sb.toString().trim();
    }
}
