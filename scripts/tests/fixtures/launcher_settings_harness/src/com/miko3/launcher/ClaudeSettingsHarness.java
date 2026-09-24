package com.miko3.launcher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-JVM checks for the launcher's Claude settings (settings plan U2, R3-R7,
 * R15, R16), driven by scripts/tests/test_launcher_settings.py. Lives in the
 * launcher's package so it can reach the package-private class; compiles
 * against launcher/src and shared/src only (ClaudeSettings touches no android.*).
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario; the
 * Python side asserts on each by name.
 */
public final class ClaudeSettingsHarness {
    static final String KEY_A = "sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAAAAAA-wxyz";
    static final String KEY_B = "sk-ant-api03-BBBBBBBBBBBBBBBBBBBBBBBBBBBB-1234";

    /** In-memory stand-in for the SharedPreferences-backed store. */
    static final class MemStore implements ClaudeSettings.Store {
        final Map<String, String> values = new HashMap<String, String>();
        int commits;

        @Override
        public String getString(String key, String def) {
            String v = values.get(key);
            return v == null ? def : v;
        }

        @Override
        public void putStrings(Map<String, String> entries) {
            commits++;
            values.putAll(entries);
        }
    }

    static final class FakeClock implements ClaudeSettings.Clock {
        long now = 1_700_000_000_000L;

        @Override
        public long nowMillis() {
            return now;
        }
    }

    static final class Fixture {
        final MemStore store = new MemStore();
        final FakeClock clock = new FakeClock();
        final ClaudeSettings settings = new ClaudeSettings(store, clock);
    }

    private static int failures;

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            System.out.println("PASS " + name);
        } else {
            failures++;
            System.out.println("FAIL " + name + ": " + detail);
        }
    }

    interface Scenario {
        void run(String name) throws Exception;
    }

    private static void scenario(String name, Scenario s) {
        try {
            s.run(name);
        } catch (Throwable t) {
            failures++;
            System.out.println("FAIL " + name + ": threw " + t);
        }
    }

    /** The refusal message, or null when the save went through. */
    private static String refusal(ClaudeSettings s, String url, String key, String model) {
        try {
            s.save(url, key, model);
            return null;
        } catch (ClaudeSettings.InvalidException e) {
            return e.getMessage();
        }
    }

    private static String describe(ClaudeSettings s) {
        ClaudeSettings.Credentials c = s.credentialsForRequests();
        return "url=" + c.baseUrl + " keyLen=" + c.apiKey.length() + " model=" + c.model;
    }

    /** A Fixture with KEY_A saved for the default URL and model m1. */
    private static Fixture withKey() throws Exception {
        Fixture f = new Fixture();
        f.settings.save(ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "m1");
        return f;
    }

    public static void main(String[] args) {
        scenario("fresh_store_defaults", new Scenario() {
            public void run(String n) {
                Fixture f = new Fixture();
                ClaudeSettings.Status st = f.settings.status();
                ClaudeSettings.Credentials c = f.settings.credentialsForRequests();
                check(n, "https://teamclaude.rentaladvantage.rent".equals(ClaudeSettings.DEFAULT_BASE_URL)
                                && ClaudeSettings.DEFAULT_BASE_URL.equals(st.baseUrl)
                                && !st.keySet && "".equals(st.keyLastFour) && st.keySavedAtMillis == 0
                                && "".equals(st.model) && "".equals(c.apiKey) && !c.isSetUp()
                                && f.settings.models().isEmpty(),
                        "base=" + st.baseUrl + " keySet=" + st.keySet + " model=" + st.model);
            }
        });

        // AE6: a fresh robot can save its first key before any model is known.
        scenario("fresh_store_saves_only_a_key", new Scenario() {
            public void run(String n) {
                Fixture f = new Fixture();
                String why = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "");
                ClaudeSettings.Credentials c = f.settings.credentialsForRequests();
                check(n, why == null && KEY_A.equals(c.apiKey) && "".equals(c.model) && !c.isSetUp()
                                && f.settings.status().keySet,
                        "refusal=" + why + " " + describe(f.settings));
            }
        });

        // AE2: a blank key field keeps the stored key.
        scenario("blank_key_keeps_stored_key", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String why = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, "", "m2");
                ClaudeSettings.Credentials c = f.settings.credentialsForRequests();
                check(n, why == null && KEY_A.equals(c.apiKey) && "m2".equals(c.model) && c.isSetUp(),
                        "refusal=" + why + " " + describe(f.settings));
            }
        });

        scenario("whitespace_key_counts_as_blank", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String why = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, "  \t ", "m1");
                check(n, why == null && KEY_A.equals(f.settings.credentialsForRequests().apiKey),
                        "refusal=" + why + " " + describe(f.settings));
            }
        });

        scenario("pasted_key_is_trimmed", new Scenario() {
            public void run(String n) {
                Fixture f = new Fixture();
                String why = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, " " + KEY_A + "\r\n", "");
                check(n, why == null && KEY_A.equals(f.settings.credentialsForRequests().apiKey),
                        "refusal=" + why + " " + describe(f.settings));
            }
        });

        // AE1: the stored key is never redirected to another server.
        scenario("url_change_with_blank_key_rejected", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                int commits = f.store.commits;
                String why = refusal(f.settings, "https://evil.example", "", "m1");
                ClaudeSettings.Credentials c = f.settings.credentialsForRequests();
                check(n, why != null && why.toLowerCase().contains("key")
                                && ClaudeSettings.DEFAULT_BASE_URL.equals(c.baseUrl)
                                && KEY_A.equals(c.apiKey) && f.store.commits == commits,
                        "refusal=" + why + " " + describe(f.settings));
            }
        });

        scenario("url_change_with_blank_key_rejected_even_without_stored_key", new Scenario() {
            public void run(String n) {
                Fixture f = new Fixture();
                String why = refusal(f.settings, "https://other.example", "", "");
                check(n, why != null && ClaudeSettings.DEFAULT_BASE_URL.equals(f.settings.status().baseUrl)
                                && f.store.commits == 0,
                        "refusal=" + why + " " + describe(f.settings));
            }
        });

        scenario("url_change_with_new_key_succeeds", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String why = refusal(f.settings, "https://other.example/v1/", KEY_B, "m1");
                ClaudeSettings.Credentials c = f.settings.credentialsForRequests();
                check(n, why == null && "https://other.example".equals(c.baseUrl) && KEY_B.equals(c.apiKey),
                        "refusal=" + why + " " + describe(f.settings));
            }
        });

        // A model field holding the key would be stored, rendered and printed.
        scenario("model_containing_the_new_key_rejected", new Scenario() {
            public void run(String n) {
                Fixture f = new Fixture();
                String exact = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, KEY_A, KEY_A);
                String within = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "x/" + KEY_A + "-y");
                check(n, exact != null && within != null && !exact.contains(KEY_A) && !within.contains(KEY_A)
                                && !exact.contains("wxyz") && f.store.commits == 0,
                        "refusals=" + (exact == null ? "null" : "set") + "," + (within == null ? "null" : "set")
                                + " commits=" + f.store.commits);
            }
        });

        scenario("model_containing_the_stored_key_rejected", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                int commits = f.store.commits;
                String why = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, "", KEY_A);
                String whyNewKey = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, KEY_B, "m-" + KEY_A);
                ClaudeSettings.Credentials c = f.settings.credentialsForRequests();
                check(n, why != null && whyNewKey != null && !why.contains(KEY_A) && !whyNewKey.contains(KEY_A)
                                && "m1".equals(c.model) && KEY_A.equals(c.apiKey) && f.store.commits == commits,
                        "refusals=" + (why == null ? "null" : "set") + "," + (whyNewKey == null ? "null" : "set")
                                + " model=" + (c.model.contains(KEY_A) ? "<KEY>" : c.model));
            }
        });

        // AE2: https://host, https://host/ and https://host/v1/ are one URL.
        scenario("url_spellings_count_as_the_same_url", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.settings.save("https://host.example", KEY_A, "m1");
                List<String> refused = new ArrayList<String>();
                for (String spelling : Arrays.asList("https://host.example/", "https://host.example/v1/",
                        "https://host.example/v1", " https://host.example ")) {
                    if (refusal(f.settings, spelling, "", "m1") != null) {
                        refused.add(spelling);
                    }
                }
                ClaudeSettings.Credentials c = f.settings.credentialsForRequests();
                check(n, refused.isEmpty() && KEY_A.equals(c.apiKey) && "https://host.example".equals(c.baseUrl),
                        "refused=" + refused + " " + describe(f.settings));
            }
        });

        scenario("stored_url_is_normalized", new Scenario() {
            public void run(String n) {
                Fixture f = new Fixture();
                refusal(f.settings, "https://host.example/v1/", KEY_A, "");
                check(n, "https://host.example".equals(f.settings.status().baseUrl), describe(f.settings));
            }
        });

        // R7: a bad URL is never stored, and the reason is readable.
        for (final String[] bad : new String[][] {
                {"http_url_rejected", "http://host.example"},
                {"url_without_host_rejected", "https://"},
                {"url_with_spaces_rejected", "https://host .example"},
                {"empty_url_rejected", ""},
        }) {
            scenario(bad[0], new Scenario() {
                public void run(String n) throws Exception {
                    Fixture f = withKey();
                    int commits = f.store.commits;
                    String why = refusal(f.settings, bad[1], KEY_B, "m2");
                    ClaudeSettings.Credentials c = f.settings.credentialsForRequests();
                    check(n, why != null && why.contains("https://") && f.store.commits == commits
                                    && ClaudeSettings.DEFAULT_BASE_URL.equals(c.baseUrl)
                                    && KEY_A.equals(c.apiKey) && "m1".equals(c.model),
                            "refusal=" + why + " " + describe(f.settings));
                }
            });
        }

        scenario("refusals_never_echo_input", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                List<String> leaks = new ArrayList<String>();
                String[][] cases = {
                        {"http://leak-host.example", "sk-leak-key-0000000000", "m1"},
                        {"https://leak-host.example", "", "m1"},
                        {ClaudeSettings.DEFAULT_BASE_URL, "sk-leak\nkey-0000000000", "m1"},
                        {ClaudeSettings.DEFAULT_BASE_URL, "", "leak model<>"},
                };
                for (String[] c : cases) {
                    String why = refusal(f.settings, c[0], c[1], c[2]);
                    if (why == null || why.contains("leak")) {
                        leaks.add(Arrays.toString(c) + " -> " + why);
                    }
                }
                check(n, leaks.isEmpty(), leaks.toString());
            }
        });

        scenario("key_with_control_chars_rejected", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String why = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, "sk-abc\ndef-0123456789", "m1");
                String why2 = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, "sk-abc def-0123456789", "m1");
                check(n, why != null && why2 != null && KEY_A.equals(f.settings.credentialsForRequests().apiKey),
                        "refusal=" + why + " / " + why2);
            }
        });

        // AE7: Forget key leaves no key behind for the page or the modes.
        scenario("forget_key_clears_key", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                f.settings.forgetKey();
                ClaudeSettings.Status st = f.settings.status();
                ClaudeSettings.Credentials c = f.settings.credentialsForRequests();
                boolean leftover = f.store.values.containsValue(KEY_A);
                check(n, !st.keySet && "".equals(st.keyLastFour) && st.keySavedAtMillis == 0
                                && "".equals(c.apiKey) && !c.isSetUp() && !leftover
                                && "m1".equals(c.model) && ClaudeSettings.DEFAULT_BASE_URL.equals(c.baseUrl),
                        "keySet=" + st.keySet + " leftover=" + leftover + " " + describe(f.settings));
            }
        });

        scenario("status_shows_last_four_and_saved_at", new Scenario() {
            public void run(String n) {
                Fixture f = new Fixture();
                f.clock.now = 1_234_567L;
                refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "m1");
                ClaudeSettings.Status st = f.settings.status();
                check(n, st.keySet && "wxyz".equals(st.keyLastFour) && st.keySavedAtMillis == 1_234_567L
                                && "m1".equals(st.model),
                        "lastFour=" + st.keyLastFour + " savedAt=" + st.keySavedAtMillis);
            }
        });

        scenario("saved_at_kept_when_key_kept", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.clock.now = 1000L;
                f.settings.save(ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "m1");
                f.clock.now = 5000L;
                f.settings.save(ClaudeSettings.DEFAULT_BASE_URL, "", "m2");
                long kept = f.settings.status().keySavedAtMillis;
                f.clock.now = 9000L;
                f.settings.save(ClaudeSettings.DEFAULT_BASE_URL, KEY_B, "m2");
                ClaudeSettings.Status st = f.settings.status();
                check(n, kept == 1000L && st.keySavedAtMillis == 9000L && "1234".equals(st.keyLastFour),
                        "kept=" + kept + " after=" + st.keySavedAtMillis);
            }
        });

        scenario("short_key_shows_no_suffix", new Scenario() {
            public void run(String n) {
                Fixture f = new Fixture();
                refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, "abcdef", "");
                ClaudeSettings.Status st = f.settings.status();
                check(n, st.keySet && "".equals(st.keyLastFour), "lastFour=" + st.keyLastFour);
            }
        });

        // R4: nothing a page renders from can yield the full key.
        scenario("status_never_exposes_full_key", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                ClaudeSettings.Status st = f.settings.status();
                List<String> leaks = new ArrayList<String>();
                for (Field fld : ClaudeSettings.Status.class.getDeclaredFields()) {
                    fld.setAccessible(true);
                    if (String.valueOf(fld.get(st)).contains(KEY_A.substring(0, 20))) {
                        leaks.add("field " + fld.getName());
                    }
                }
                for (Method m : ClaudeSettings.Status.class.getDeclaredMethods()) {
                    if (m.getParameterTypes().length == 0 && !Modifier.isStatic(m.getModifiers())) {
                        m.setAccessible(true);
                        if (String.valueOf(m.invoke(st)).contains(KEY_A.substring(0, 20))) {
                            leaks.add("method " + m.getName());
                        }
                    }
                }
                if (st.toString().contains(KEY_A.substring(0, 20))) {
                    leaks.add("toString");
                }
                check(n, leaks.isEmpty(), leaks.toString());
            }
        });

        scenario("credentials_to_string_hides_key", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String s = f.settings.credentialsForRequests().toString();
                check(n, !s.contains(KEY_A.substring(0, 20)), s);
            }
        });

        scenario("save_is_one_commit", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = new Fixture();
                f.settings.save("https://host.example", KEY_A, "m1");
                int afterSave = f.store.commits;
                f.settings.forgetKey();
                check(n, afterSave == 1 && f.store.commits == 2, "commits=" + afterSave + "," + f.store.commits);
            }
        });

        scenario("values_survive_a_new_instance", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                f.settings.saveModels(Arrays.asList("m1", "m2"));
                ClaudeSettings again = new ClaudeSettings(f.store, f.clock);
                ClaudeSettings.Credentials c = again.credentialsForRequests();
                check(n, KEY_A.equals(c.apiKey) && "m1".equals(c.model)
                                && Arrays.asList("m1", "m2").equals(again.models()),
                        describe(again) + " models=" + again.models());
            }
        });

        // Model validation.
        scenario("empty_model_allowed", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String why = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, "", "  ");
                check(n, why == null && "".equals(f.settings.status().model), "refusal=" + why);
            }
        });

        scenario("model_ids_accepted", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                List<String> refused = new ArrayList<String>();
                for (String id : Arrays.asList("claude-sonnet-4-5", "claude-opus-4-1-20250805",
                        "us.anthropic.claude-3-7-sonnet-20250219-v1:0", "claude-3-5-sonnet@20240620",
                        "anthropic/claude-haiku_4.5")) {
                    String why = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, "", id);
                    if (why != null || !id.equals(f.settings.status().model)) {
                        refused.add(id + " -> " + why);
                    }
                }
                check(n, refused.isEmpty(), refused.toString());
            }
        });

        for (final String[] bad : new String[][] {
                {"model_with_control_chars_rejected", "claude\nsonnet"},
                {"model_with_space_rejected", "claude sonnet"},
                {"model_with_markup_rejected", "claude<script>"},
                {"model_with_quote_rejected", "claude\"x"},
                {"model_too_long_rejected", repeat('a', 129)},
        }) {
            scenario(bad[0], new Scenario() {
                public void run(String n) throws Exception {
                    Fixture f = withKey();
                    int commits = f.store.commits;
                    String why = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, "", bad[1]);
                    check(n, why != null && why.length() > 10 && f.store.commits == commits
                                    && "m1".equals(f.settings.status().model),
                            "refusal=" + why + " model=" + f.settings.status().model);
                }
            });
        }

        scenario("model_at_length_limit_accepted", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                String why = refusal(f.settings, ClaudeSettings.DEFAULT_BASE_URL, "", repeat('a', 128));
                check(n, why == null, "refusal=" + why);
            }
        });

        // The stored Refresh list (KTD2, KTD5).
        scenario("model_list_round_trips", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                f.settings.saveModels(Arrays.asList("claude-a", "claude-b", "claude-a"));
                List<String> got = f.settings.models();
                f.settings.saveModels(new ArrayList<String>());
                check(n, Arrays.asList("claude-a", "claude-b").equals(got) && f.settings.models().isEmpty(),
                        "got=" + got + " after clear=" + f.settings.models());
            }
        });

        scenario("model_list_drops_invalid_ids", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                f.settings.saveModels(Arrays.asList("ok-1", "bad id", "x\ny", "", null, "<b>", "ok-2"));
                check(n, Arrays.asList("ok-1", "ok-2").equals(f.settings.models()), "got=" + f.settings.models());
            }
        });

        scenario("url_change_clears_model_list", new Scenario() {
            public void run(String n) throws Exception {
                Fixture f = withKey();
                f.settings.saveModels(Arrays.asList("claude-a"));
                f.settings.save(ClaudeSettings.DEFAULT_BASE_URL + "/v1", "", "m1");
                List<String> sameUrl = f.settings.models();
                f.settings.save("https://other.example", KEY_B, "m1");
                check(n, Arrays.asList("claude-a").equals(sameUrl) && f.settings.models().isEmpty(),
                        "sameUrl=" + sameUrl + " after=" + f.settings.models());
            }
        });

        if (failures > 0) {
            System.exit(1);
        }
    }

    private static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int k = 0; k < n; k++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
