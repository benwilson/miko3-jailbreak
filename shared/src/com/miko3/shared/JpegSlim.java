package com.miko3.shared;

import java.util.ArrayList;
import java.util.List;

/**
 * Lossless JPEG slimming for uploads: drops the metadata, keeps the image.
 *
 * The Miko 3 camera's hardware JPEG is ~445 KB, but ~300 KB of that is the
 * vendor's APPn blocks (EXIF with a thumbnail, MediaTek tuning dumps in
 * APP5-APP8) and the encoder zero-pads past EOI. This walks the marker
 * segments, drops APP1-APP15 and COM, and keeps SOI, APP0 (JFIF), DQT, SOF,
 * DHT, DRI, SOS, the entropy-coded data and EOI byte-for-byte. Nothing is
 * decoded or re-encoded, so the pixels are identical. Anything after EOI is
 * dropped.
 *
 * An EXIF Orientation other than 1 is kept as a minimal APP1 (that one tag) so
 * a viewer that honours it still shows the frame upright. The robot's frames
 * all carry Orientation 1, so in practice APP1 goes entirely.
 *
 * Anything it can't walk cleanly (not a JPEG, a segment length that overruns,
 * a scan with no EOI because the frame was cut short) comes back as the same
 * array, unchanged. A JPEG with nothing to drop also comes back as-is.
 */
public final class JpegSlim {
    private JpegSlim() {}

    private static final int SOI = 0xD8;
    private static final int EOI = 0xD9;
    private static final int SOS = 0xDA;
    private static final int APP0 = 0xE0;
    private static final int APP1 = 0xE1;
    private static final int APP15 = 0xEF;
    private static final int COM = 0xFE;
    private static final int TEM = 0x01;

    /** The slimmed JPEG, or {@code jpeg} itself if it can't be walked or has nothing to drop. */
    public static byte[] slim(byte[] jpeg) {
        try {
            byte[] out = walk(jpeg);
            return out == null ? jpeg : out;
        } catch (RuntimeException e) {
            return jpeg;
        }
    }

    /**
     * The EXIF Orientation (1-8) from the JPEG's APP1, or 0 when there is no
     * readable Exif APP1 or it has no Orientation tag.
     */
    public static int orientation(byte[] jpeg) {
        try {
            if (!startsWithSoi(jpeg)) {
                return 0;
            }
            int i = 2;
            while (i + 4 <= jpeg.length && u8(jpeg, i) == 0xFF) {
                int m = u8(jpeg, i + 1);
                if (m == SOS || m == EOI) {
                    return 0;
                }
                int len = u16(jpeg, i + 2, false);
                if (len < 2 || i + 2 + len > jpeg.length) {
                    return 0;
                }
                if (m == APP1) {
                    int o = exifOrientation(jpeg, i + 4, i + 2 + len);
                    if (o > 0) {
                        return o;
                    }
                }
                i += 2 + len;
            }
        } catch (RuntimeException e) {
            // fall through
        }
        return 0;
    }

    /** null means "return the original": malformed, truncated, or nothing to drop. */
    private static byte[] walk(byte[] jpeg) {
        if (!startsWithSoi(jpeg)) {
            return null;
        }
        int n = jpeg.length;
        List<int[]> keep = new ArrayList<int[]>(); // [from, to) ranges, in order
        keep.add(new int[] {0, 2});
        boolean dropped = false;
        int orientation = 0;
        int orientationAt = -1; // index into keep where the minimal APP1 goes
        int i = 2;
        while (true) {
            if (i + 2 > n || u8(jpeg, i) != 0xFF) {
                return null;
            }
            int m = u8(jpeg, i + 1);
            if (m == 0xFF) { // fill byte before a marker: meaningless, skip it
                dropped = true;
                i++;
                continue;
            }
            if (m == TEM) {
                keep.add(new int[] {i, i + 2});
                i += 2;
                continue;
            }
            if (m == 0x00 || m == SOI || m == EOI || (m >= 0xD0 && m <= 0xD7)) {
                return null; // no standalone marker belongs here before the first scan
            }
            if (i + 4 > n) {
                return null;
            }
            int len = u16(jpeg, i + 2, false);
            int next = i + 2 + len;
            if (len < 2 || next > n) {
                return null;
            }
            if (m == SOS) {
                int end = endOfImage(jpeg, next);
                if (end < 0) {
                    return null;
                }
                keep.add(new int[] {i, end});
                if (end < n) {
                    dropped = true;
                }
                break;
            }
            if ((m > APP0 && m <= APP15) || m == COM) {
                dropped = true;
                if (m == APP1 && orientation == 0) {
                    int o = exifOrientation(jpeg, i + 4, next);
                    if (o > 1) {
                        orientation = o;
                        orientationAt = keep.size();
                    }
                }
            } else {
                keep.add(new int[] {i, next});
            }
            i = next;
        }
        if (!dropped) {
            return null;
        }
        byte[] app1 = orientation > 1 ? minimalOrientationApp1(orientation) : new byte[0];
        int size = app1.length;
        for (int[] r : keep) {
            size += r[1] - r[0];
        }
        byte[] out = new byte[size];
        int at = 0;
        for (int k = 0; k < keep.size(); k++) {
            if (k == orientationAt) {
                System.arraycopy(app1, 0, out, at, app1.length);
                at += app1.length;
            }
            int[] r = keep.get(k);
            System.arraycopy(jpeg, r[0], out, at, r[1] - r[0]);
            at += r[1] - r[0];
        }
        if (orientationAt == keep.size()) {
            System.arraycopy(app1, 0, out, at, app1.length);
        }
        return out;
    }

    /**
     * The offset just past EOI, walking from the first scan's entropy data
     * through any later scans and their tables (progressive JPEGs), or -1 if
     * the data ends first.
     */
    private static int endOfImage(byte[] jpeg, int from) {
        int n = jpeg.length;
        int i = from;
        while (i + 1 < n) {
            if (u8(jpeg, i) != 0xFF) {
                i++;
                continue;
            }
            int m = u8(jpeg, i + 1);
            if (m == 0x00 || m == 0xFF || (m >= 0xD0 && m <= 0xD7)) {
                i += m == 0xFF ? 1 : 2; // stuffed byte, fill byte, or restart marker
                continue;
            }
            if (m == EOI) {
                return i + 2;
            }
            // A marker segment between scans (DHT, DQT, DRI, SOS, ...): step over it.
            if (i + 4 > n) {
                return -1;
            }
            int len = u16(jpeg, i + 2, false);
            if (len < 2 || i + 2 + len > n) {
                return -1;
            }
            i += 2 + len;
        }
        return -1;
    }

    /** Orientation from an APP1 payload [from, to) that starts "Exif\0\0", else 0. */
    private static int exifOrientation(byte[] b, int from, int to) {
        if (to - from < 14 || b[from] != 'E' || b[from + 1] != 'x' || b[from + 2] != 'i'
                || b[from + 3] != 'f' || b[from + 4] != 0 || b[from + 5] != 0) {
            return 0;
        }
        int tiff = from + 6;
        boolean little;
        if (b[tiff] == 'I' && b[tiff + 1] == 'I') {
            little = true;
        } else if (b[tiff] == 'M' && b[tiff + 1] == 'M') {
            little = false;
        } else {
            return 0;
        }
        long ifd = tiff + u32(b, tiff + 4, little);
        if (ifd < tiff || ifd + 2 > to) {
            return 0;
        }
        int count = u16(b, (int) ifd, little);
        for (int k = 0; k < count; k++) {
            int e = (int) ifd + 2 + 12 * k;
            if (e + 12 > to) {
                return 0;
            }
            if (u16(b, e, little) == 0x0112 && u16(b, e + 2, little) == 3) {
                int o = u16(b, e + 8, little);
                return o >= 1 && o <= 8 ? o : 0;
            }
        }
        return 0;
    }

    /** An APP1 segment holding an Exif IFD0 with only the Orientation tag (big-endian). */
    private static byte[] minimalOrientationApp1(int orientation) {
        byte[] s = {
            (byte) 0xFF, (byte) APP1, 0, 34,
            'E', 'x', 'i', 'f', 0, 0,
            'M', 'M', 0, 42, 0, 0, 0, 8, // TIFF header, IFD0 at 8
            0, 1, // one entry
            0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, (byte) orientation, 0, 0, // Orientation, SHORT, 1
            0, 0, 0, 0, // no next IFD
        };
        return s;
    }

    private static boolean startsWithSoi(byte[] b) {
        return b != null && b.length >= 4 && u8(b, 0) == 0xFF && u8(b, 1) == SOI;
    }

    private static int u8(byte[] b, int i) {
        return b[i] & 0xFF;
    }

    private static int u16(byte[] b, int i, boolean little) {
        return little ? u8(b, i) | (u8(b, i + 1) << 8) : (u8(b, i) << 8) | u8(b, i + 1);
    }

    private static long u32(byte[] b, int i, boolean little) {
        long hi = u16(b, little ? i + 2 : i, little);
        long lo = u16(b, little ? i : i + 2, little);
        return (hi << 16) | lo;
    }
}
