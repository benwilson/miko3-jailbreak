package com.miko3.mode.explore;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;

/** Drives FrameRing in temp directories; one PASS/FAIL line per scenario. */
public final class FrameRingHarness {
    public static void main(String[] args) throws Exception {
        check("saves_the_exact_bytes_named_by_wall_time", savesExactBytes());
        check("keeps_only_the_newest_capacity_frames", keepsNewest());
        check("a_new_ring_adopts_frames_already_on_disk", adoptsExisting());
        check("ignores_other_files_in_the_directory", ignoresOthers());
        check("creates_the_directory", createsDir());
    }

    private static File tmp() throws IOException {
        return Files.createTempDirectory("frame_ring").toFile();
    }

    private static String[] jpgs(File dir) {
        String[] names = dir.list((d, n) -> n.startsWith("frame-") && n.endsWith(".jpg"));
        Arrays.sort(names);
        return names;
    }

    private static String savesExactBytes() throws Exception {
        File dir = tmp();
        byte[] jpeg = {(byte) 0xFF, (byte) 0xD8, 1, 2, 3, (byte) 0xFF, (byte) 0xD9};
        String name = new FrameRing(dir, 5).save(1790886847475L, jpeg);
        if (!"frame-1790886847475.jpg".equals(name)) {
            return "name " + name;
        }
        byte[] back = Files.readAllBytes(new File(dir, name).toPath());
        return Arrays.equals(back, jpeg) ? null : "bytes differ";
    }

    private static String keepsNewest() throws Exception {
        File dir = tmp();
        FrameRing ring = new FrameRing(dir, 3);
        for (long t = 1000; t < 1006; t++) {
            ring.save(t, new byte[]{(byte) t});
        }
        String got = Arrays.toString(jpgs(dir));
        return got.equals("[frame-1003.jpg, frame-1004.jpg, frame-1005.jpg]") ? null : got;
    }

    private static String adoptsExisting() throws Exception {
        File dir = tmp();
        FrameRing first = new FrameRing(dir, 3);
        for (long t = 1000; t < 1003; t++) {
            first.save(t, new byte[]{1});
        }
        FrameRing second = new FrameRing(dir, 3);
        second.save(2000, new byte[]{1});
        String got = Arrays.toString(jpgs(dir));
        return got.equals("[frame-1001.jpg, frame-1002.jpg, frame-2000.jpg]") ? null : got;
    }

    private static String ignoresOthers() throws Exception {
        File dir = tmp();
        Files.write(new File(dir, "last-nav.jpg").toPath(), new byte[]{1});
        FrameRing ring = new FrameRing(dir, 1);
        ring.save(1, new byte[]{1});
        ring.save(2, new byte[]{1});
        if (!new File(dir, "last-nav.jpg").exists()) {
            return "deleted another file";
        }
        String got = Arrays.toString(jpgs(dir));
        return got.equals("[frame-2.jpg]") ? null : got;
    }

    private static String createsDir() throws Exception {
        File dir = new File(tmp(), "frames");
        new FrameRing(dir, 2).save(7, new byte[]{1});
        return new File(dir, "frame-7.jpg").exists() ? null : "not written";
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }
}
