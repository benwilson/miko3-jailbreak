package com.miko3.launcher;

import com.miko3.shared.ClaudeAccess;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * Host-JVM checks for the launcher's settings service for modes (settings
 * plan U5, KTD1; R13, R16), driven by
 * scripts/tests/test_robot_settings_service.py. Lives in the launcher's
 * package so it can reach the package-private CallerCheck and ClaudeSettings;
 * compiles against launcher/src and shared/src only (neither class touches
 * android.*). The service's own glue is covered by the source-wiring tests.
 *
 * Prints one "PASS <name>" or "FAIL <name>: <detail>" line per scenario; the
 * Python side asserts on each by name.
 */
public final class RobotSettingsServiceHarness {
    static final String KEY_A = "sk-ant-api03-AAAAAAAAAAAAAAAAAAAAAAAAAAAA-wxyz";
    static final String WRONG = "0000000000000000000000000000000000000000000000000000000000000000";

    /** Fake package manager: package name to its signers' SHA-256 digests. */
    static final class FakeSigners implements CallerCheck.Signers {
        final Map<String, List<String>> byPackage = new HashMap<String, List<String>>();

        FakeSigners with(String pkg, String... digests) {
            byPackage.put(pkg, Arrays.asList(digests));
            return this;
        }

        @Override
        public List<String> digestsOf(String packageName) {
            return byPackage.get(packageName);
        }
    }

    /** In-memory stand-in for the SharedPreferences-backed store. */
    static final class MemStore implements ClaudeSettings.Store {
        final Map<String, String> values = new HashMap<String, String>();

        @Override
        public String getString(String key, String def) {
            String v = values.get(key);
            return v == null ? def : v;
        }

        @Override
        public void putStrings(Map<String, String> entries) {
            values.putAll(entries);
        }
    }

    static ClaudeSettings newSettings() {
        return new ClaudeSettings(new MemStore(), new ClaudeSettings.Clock() {
            @Override
            public long nowMillis() {
                return 1_700_000_000_000L;
            }
        });
    }

    /** The service's answer rule, as RobotSettingsService applies it after the check. */
    static ClaudeAccess answer(ClaudeSettings settings) {
        return CallerCheck.accessFor(settings.credentialsForRequests());
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

    public static void main(String[] args) {
        scenario("each_miko3_app_with_its_pinned_digest_is_allowed", new Scenario() {
            public void run(String n) {
                StringBuilder bad = new StringBuilder();
                for (Map.Entry<String, String> pin : CallerCheck.PINS.entrySet()) {
                    FakeSigners s = new FakeSigners().with(pin.getKey(), pin.getValue());
                    if (!CallerCheck.allows(new String[] {pin.getKey()}, s)) {
                        bad.append(pin.getKey()).append(' ');
                    }
                }
                check(n, bad.length() == 0, "denied: " + bad);
            }
        });

        scenario("miko3_name_with_wrong_digest_is_denied", new Scenario() {
            public void run(String n) {
                FakeSigners s = new FakeSigners().with("com.miko3.mode.voice", WRONG);
                check(n, !CallerCheck.allows(new String[] {"com.miko3.mode.voice"}, s), "allowed");
            }
        });

        scenario("miko3_name_with_another_apps_digest_is_denied", new Scenario() {
            public void run(String n) {
                FakeSigners s = new FakeSigners().with("com.miko3.mode.voice",
                        CallerCheck.PINS.get("com.miko3.launcher"));
                check(n, !CallerCheck.allows(new String[] {"com.miko3.mode.voice"}, s), "allowed");
            }
        });

        scenario("extra_unpinned_signer_is_denied", new Scenario() {
            public void run(String n) {
                FakeSigners s = new FakeSigners().with("com.miko3.mode.explore",
                        CallerCheck.PINS.get("com.miko3.mode.explore"), WRONG);
                check(n, !CallerCheck.allows(new String[] {"com.miko3.mode.explore"}, s), "allowed");
            }
        });

        scenario("package_with_no_signers_is_denied", new Scenario() {
            public void run(String n) {
                FakeSigners empty = new FakeSigners().with("com.miko3.launcher");
                FakeSigners missing = new FakeSigners();
                check(n, !CallerCheck.allows(new String[] {"com.miko3.launcher"}, empty)
                                && !CallerCheck.allows(new String[] {"com.miko3.launcher"}, missing),
                        "allowed");
            }
        });

        scenario("unknown_package_is_denied", new Scenario() {
            public void run(String n) {
                // Even when it is signed with one of our pinned keys.
                FakeSigners s = new FakeSigners().with("com.vendor.app", CallerCheck.PINS.get("com.miko3.launcher"));
                check(n, !CallerCheck.allows(new String[] {"com.vendor.app"}, s), "allowed");
            }
        });

        scenario("uid_with_no_packages_is_denied", new Scenario() {
            public void run(String n) {
                FakeSigners s = new FakeSigners();
                check(n, !CallerCheck.allows(null, s) && !CallerCheck.allows(new String[0], s)
                                && !CallerCheck.allows(new String[] {null}, s),
                        "allowed");
            }
        });

        scenario("shared_uid_with_one_pinned_package_is_allowed", new Scenario() {
            public void run(String n) {
                FakeSigners s = new FakeSigners().with("com.vendor.app", WRONG)
                        .with("com.miko3.mode.remotecontrol", CallerCheck.PINS.get("com.miko3.mode.remotecontrol"));
                check(n, CallerCheck.allows(new String[] {"com.vendor.app", "com.miko3.mode.remotecontrol"}, s),
                        "denied");
            }
        });

        scenario("digest_compare_ignores_hex_case", new Scenario() {
            public void run(String n) {
                FakeSigners s = new FakeSigners().with("com.miko3.mode.voice",
                        CallerCheck.PINS.get("com.miko3.mode.voice").toUpperCase());
                check(n, CallerCheck.allows(new String[] {"com.miko3.mode.voice"}, s), "denied");
            }
        });

        scenario("pins_cover_exactly_the_four_apps", new Scenario() {
            public void run(String n) {
                HashSet<String> expected = new HashSet<String>(Arrays.asList("com.miko3.launcher",
                        "com.miko3.mode.voice", "com.miko3.mode.explore", "com.miko3.mode.remotecontrol"));
                boolean ok = CallerCheck.PINS.keySet().equals(expected);
                for (String d : CallerCheck.PINS.values()) {
                    ok &= d.matches("[0-9a-f]{64}");
                }
                ok &= new HashSet<String>(CallerCheck.PINS.values()).size() == 4;
                boolean unmodifiable;
                try {
                    CallerCheck.PINS.put("x", WRONG);
                    unmodifiable = false;
                } catch (UnsupportedOperationException expectedEx) {
                    unmodifiable = true;
                }
                check(n, ok && unmodifiable, "pins=" + CallerCheck.PINS + " unmodifiable=" + unmodifiable);
            }
        });

        scenario("forget_key_answers_not_set_up", new Scenario() {
            public void run(String n) throws Exception {
                ClaudeSettings settings = newSettings();
                settings.save(ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "claude-opus-5-5");
                boolean before = answer(settings).isSetUp();
                settings.forgetKey();
                ClaudeAccess a = answer(settings);
                check(n, before && !a.isSetUp() && a.apiKey.isEmpty() && a.model.isEmpty()
                                && a.baseUrl.isEmpty(),
                        "before=" + before + " after=" + a);
            }
        });

        scenario("before_setup_answers_not_set_up", new Scenario() {
            public void run(String n) throws Exception {
                ClaudeSettings fresh = newSettings();
                ClaudeSettings noModel = newSettings();
                noModel.save(ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "");
                ClaudeAccess direct = ClaudeAccess.setUp("https://example.com", "", "m");
                check(n, !answer(fresh).isSetUp() && !answer(noModel).isSetUp() && !direct.isSetUp()
                                && !ClaudeAccess.notSetUp().isSetUp(),
                        "fresh=" + answer(fresh) + " noModel=" + answer(noModel) + " direct=" + direct);
            }
        });

        scenario("set_up_answer_carries_current_values", new Scenario() {
            public void run(String n) throws Exception {
                ClaudeSettings settings = newSettings();
                settings.save("https://example.com/v1/", KEY_A, "claude-opus-5-5");
                ClaudeAccess a = answer(settings);
                check(n, a.isSetUp() && "https://example.com".equals(a.baseUrl) && KEY_A.equals(a.apiKey)
                                && "claude-opus-5-5".equals(a.model),
                        "got " + a + " base=" + a.baseUrl);
            }
        });

        scenario("model_change_shows_on_next_call", new Scenario() {
            public void run(String n) throws Exception {
                // AE5/R13: no cached answer, so a Save shows on the mode's next fetch.
                ClaudeSettings settings = newSettings();
                settings.save(ClaudeSettings.DEFAULT_BASE_URL, KEY_A, "claude-opus-5-5");
                String first = answer(settings).model;
                settings.save(ClaudeSettings.DEFAULT_BASE_URL, "", "claude-sonnet-5");
                ClaudeAccess second = answer(settings);
                check(n, "claude-opus-5-5".equals(first) && "claude-sonnet-5".equals(second.model)
                                && KEY_A.equals(second.apiKey),
                        "first=" + first + " second=" + second);
            }
        });

        scenario("access_to_string_never_shows_key", new Scenario() {
            public void run(String n) {
                ClaudeAccess a = ClaudeAccess.setUp("https://example.com", KEY_A, "claude-opus-5-5");
                String s = a.toString();
                check(n, !s.contains(KEY_A) && !s.contains("wxyz") && !s.contains("sk-ant")
                                && !ClaudeAccess.notSetUp().toString().isEmpty(),
                        "toString=" + s);
            }
        });

        System.exit(failures == 0 ? 0 : 1);
    }

    private RobotSettingsServiceHarness() {
    }
}
