package com.miko3.mode.voice;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Host-JVM checks for the voice mode's eyes state hook (U7, KTD9), driven by
 * scripts/tests/test_eyes_page_golden.py. Lives in the mode's own package so it
 * can reach the package-private VoiceState; compiles against mode-voice/src and
 * shared/src only (VoiceState and EyesPage never touch android.*).
 *
 * With "page <path>" it writes VoiceState.DEVICE_VIEW_HTML as UTF-8 bytes to
 * path for the Python side's page checks; with no arguments it prints one
 * "PASS <name>" or "FAIL <name>: <detail>" line per scenario.
 */
public final class VoiceEyesHarness {
    private static final String[] WIRE_NAMES = {
            "listening", "connecting", "conversing", "speaking", "closing", "unreachable"};

    private VoiceEyesHarness() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 2 && "page".equals(args[0])) {
            OutputStream out = new FileOutputStream(args[1]);
            try {
                out.write(VoiceState.DEVICE_VIEW_HTML.getBytes(StandardCharsets.UTF_8));
            } finally {
                out.close();
            }
            return;
        }
        check("states_are_exactly_the_ktd9_six", statesAreTheSix());
        check("wire_names_round_trip", wireNamesRoundTrip());
        check("unknown_wire_name_is_null", unknownWireName());
        check("slow_poll_only_listening_and_unreachable", slowPollStates());
        check("holder_defaults_to_listening_text", holderDefault());
        check("holder_text_follows_each_set", holderFollowsSet());
        check("holder_refuses_null", holderRefusesNull());
        check("settings_line_placeholder_before_first_set", settingsPlaceholder());
        check("settings_line_label_then_detail", settingsLabelAndDetail());
        check("holder_reads_never_torn_under_concurrent_sets", concurrentReads());
        check("page_names_every_state", pageNamesEveryState());
    }

    private static void check(String name, String failure) {
        System.out.println(failure == null ? "PASS " + name : "FAIL " + name + ": " + failure);
    }

    private static String statesAreTheSix() {
        VoiceState[] all = VoiceState.values();
        if (all.length != WIRE_NAMES.length) {
            return "expected " + WIRE_NAMES.length + " states, got " + all.length;
        }
        for (int i = 0; i < all.length; i++) {
            if (!WIRE_NAMES[i].equals(all[i].wireName())) {
                return "state " + i + " is " + all[i].wireName() + ", expected " + WIRE_NAMES[i];
            }
        }
        return null;
    }

    private static String wireNamesRoundTrip() {
        for (VoiceState s : VoiceState.values()) {
            if (VoiceState.fromWireName(s.wireName()) != s) {
                return s.wireName() + " did not round-trip";
            }
        }
        return null;
    }

    private static String unknownWireName() {
        String[] bad = {null, "", "LISTENING", "idle", " listening"};
        for (String b : bad) {
            if (VoiceState.fromWireName(b) != null) {
                return "\"" + b + "\" parsed as a state";
            }
        }
        return null;
    }

    private static String slowPollStates() {
        for (VoiceState s : VoiceState.values()) {
            boolean expected = s == VoiceState.LISTENING || s == VoiceState.UNREACHABLE;
            if (s.slowPoll() != expected) {
                return s.wireName() + ".slowPoll() is " + s.slowPoll();
            }
        }
        return null;
    }

    private static String holderDefault() {
        VoiceState.Holder h = new VoiceState.Holder();
        if (h.get() != VoiceState.LISTENING) {
            return "get() is " + h.get();
        }
        return "listening".equals(h.text()) ? null : "text() is \"" + h.text() + "\"";
    }

    private static String holderFollowsSet() {
        VoiceState.Holder h = new VoiceState.Holder();
        for (VoiceState s : VoiceState.values()) {
            h.set(s);
            if (h.get() != s || !s.wireName().equals(h.text())) {
                return "after set(" + s + ") text() is \"" + h.text() + "\"";
            }
        }
        return null;
    }

    private static String holderRefusesNull() {
        VoiceState.Holder h = new VoiceState.Holder();
        h.set(VoiceState.SPEAKING);
        try {
            h.set(null);
            return "set(null) accepted";
        } catch (NullPointerException expected) {
            return h.get() == VoiceState.SPEAKING ? null : "state changed to " + h.get();
        }
    }

    private static String settingsPlaceholder() {
        VoiceState.Holder h = new VoiceState.Holder();
        String line = h.settingsLine(null);
        if (!SettingsPage.STATE_PLACEHOLDER.equals(line)) {
            return "line is \"" + line + "\"";
        }
        line = h.settingsLine("starting");
        return (SettingsPage.STATE_PLACEHOLDER + ": starting").equals(line) ? null : "with detail: \"" + line + "\"";
    }

    private static String settingsLabelAndDetail() {
        VoiceState.Holder h = new VoiceState.Holder();
        h.set(VoiceState.UNREACHABLE);
        String bare = h.settingsLine("");
        if (!VoiceState.UNREACHABLE.label().equals(bare)) {
            return "blank detail gave \"" + bare + "\"";
        }
        String withDetail = h.settingsLine("connection refused");
        String expected = VoiceState.UNREACHABLE.label() + ": connection refused";
        if (!expected.equals(withDetail)) {
            return "got \"" + withDetail + "\", expected \"" + expected + "\"";
        }
        for (VoiceState s : VoiceState.values()) {
            if (s.label() == null || s.label().isEmpty() || s.label().equals(s.wireName())) {
                return s.wireName() + " has no human label";
            }
        }
        return null;
    }

    private static String concurrentReads() throws InterruptedException {
        final VoiceState.Holder h = new VoiceState.Holder();
        final AtomicReference<String> bad = new AtomicReference<String>();
        final long until = System.currentTimeMillis() + 300;
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                VoiceState[] all = VoiceState.values();
                for (int i = 0; System.currentTimeMillis() < until; i++) {
                    h.set(all[i % all.length]);
                }
            }
        });
        writer.start();
        int reads = 0;
        while (writer.isAlive()) {
            String t = h.text();
            if (VoiceState.fromWireName(t) == null) {
                bad.compareAndSet(null, t);
            }
            reads++;
        }
        writer.join();
        if (bad.get() != null) {
            return "read \"" + bad.get() + "\"";
        }
        return reads > 0 ? null : "no reads happened";
    }

    private static String pageNamesEveryState() {
        String page = VoiceState.DEVICE_VIEW_HTML;
        for (String name : WIRE_NAMES) {
            if (!page.contains("s-" + name) && !page.contains("'" + name + "'")) {
                return "page never mentions " + name;
            }
        }
        return null;
    }
}
