#!/usr/bin/env python3
"""Tests for the explore mode's wander brain and hazard classifier (U3).

The brain is the safety-critical core of the mode: it decides when the robot
may hop, turn or back off, and it must stop on an edge, an obstacle, a lost
sensor feed or a lost drive lease (KTD3, KTD4, KTD5, KTD8, KTD9, KTD10).

MainActivity and the drive adapter are Android-only and the repo has no
Android test harness, so every decision lives in HazardClassifier and
ExploreBrain, plain Java with no android.* imports and everything injected.
This compiles them for the host JVM and runs fixtures/explore_brain_harness,
which scripts sensor readings against a mock clock and prints one PASS/FAIL
line per scenario.
"""
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

TESTS = Path(__file__).resolve().parent
if str(TESTS) not in sys.path:
    sys.path.insert(0, str(TESTS))
import jvm_harness  # noqa: E402

REPO = Path(__file__).resolve().parents[2]
EXPLORE_SRC = REPO / "mode-explore" / "src"
EXPLORE_PKG = EXPLORE_SRC / "com" / "miko3" / "mode" / "explore"
HARNESS = TESTS / "fixtures" / "explore_brain_harness" / "src"
HARNESS_MAIN = HARNESS / "com" / "miko3" / "mode" / "explore" / "ExploreBrainHarness.java"
PLAIN_JAVA = ("SensorReading.java", "HazardClassifier.java", "ExploreBrain.java", "ExploreTuning.java",
              "Sighting.java", "Detection.java", "CuriosityPort.java", "FaceCrop.java", "ClaudeReplies.java",
              "ExplorePrompts.java", "Openness.java", "Brightness.java", "Heading.java", "ExploreCalibration.java")


class BrainIsPlainJavaTest(unittest.TestCase):
    """It only runs on the host JVM while it stays clear of the Android SDK."""

    def test_no_android_imports(self):
        offenders = []
        for name in PLAIN_JAVA:
            for line in (EXPLORE_PKG / name).read_text().splitlines():
                if line.startswith("import android") or (line.startswith("import ") and ".android." in line):
                    offenders.append(f"{name}: {line}")
        self.assertEqual(offenders, [])

    def test_no_shared_driver_dependency(self):
        """The brain takes its own SensorReading, not the shared driver's snapshot."""
        offenders = [name for name in PLAIN_JAVA
                     if "com.miko3.shared" in (EXPLORE_PKG / name).read_text()]
        self.assertEqual(offenders, [])


class ExploreBrainHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        # HazardClassifier (KTD3, KTD9)
        "classifier_uncalibrated_is_unavailable",
        "classifier_incomplete_calibration_is_unavailable",
        "classifier_needs_the_recovery_streak_at_start",
        "classifier_goes_unavailable_after_300ms_without_a_reading",
        "classifier_tof_fault_value_is_unavailable",
        "classifier_uart_fault_is_unavailable",
        "classifier_frozen_tof_is_unavailable",
        "classifier_constant_ir_is_not_frozen",
        "classifier_flapping_needs_a_new_streak",
        "classifier_thresholds_report_edge_and_obstacle_sides",
        "classifier_cpl2_is_a_hazard",
        "classifier_absent_ir_is_never_an_edge",
        "classifier_one_ir_channel_gives_no_side",
        "classifier_edge_held_past_frozen_window_stays_a_hazard",
        "classifier_fault_tof_with_ir_edge_flag_is_an_edge",
        # HazardClassifier: approach mode (U4, KTD4)
        "approach_low_tof_with_ir2_is_close",
        "approach_fault_tof_with_ir2_is_edge",
        "approach_cpl2_below_band_is_close_by_refusal",
        "approach_cpl2_with_ir2_above_band_is_edge",
        "approach_ir2_inside_band_is_edge",
        "approach_no_flag_inside_band_is_clear",
        "approach_tof_above_edge_rule_is_edge",
        "approach_unavailable_like_wander_mode",
        "wander_low_tof_with_ir2_is_still_a_hazard",
        # ExploreBrain: acceptance examples
        "ae1_edge_mid_hop_startles_backs_off_and_turns_away",
        "ae2_eyes_lead_the_turn_then_idle_on_the_hop",
        "ae3_no_readings_never_moves",
        "ae4_readings_stop_mid_hop",
        "ae5_lease_loss_mid_back_off",
        "ae6_cpl2_never_retries_forward",
        # ExploreBrain: edges and errors
        "hazard_during_a_turn_stops_the_turn",
        "hazard_at_hop_start_turns_away_instead",
        "escape_turn_steers_away_from_the_hazard_side",
        "cornered_only_after_three_failed_escapes",
        "wall_clearing_mid_escape_ends_it_without_resting",
        "escapes_keep_one_direction_until_he_drives_off",
        "stalled_wheels_mid_hop_stop_startle_and_escape",
        "repeated_stalls_back_off_further_and_turn_more_each_time",
        "turning_wheels_never_read_as_stalled",
        "no_wheel_data_never_reads_as_stalled",
        "flapping_readings_do_not_resume_driving",
        "uncalibrated_brain_never_moves",
        "frozen_tof_mid_run_stops_the_hop",
        "no_lease_never_moves",
        # ExploreBrain: happy path and integration with the drive adapter
        "continuous_legs_vary_in_length_within_range",
        "wander_cycle_hops_and_turns_only_on_fresh_clear_readings",
        "lease_loss_reported_inside_a_motor_call",
        "shutdown_stops_and_goes_inert",
        # Camera curiosity (camera curiosity plan U5, AE1-AE6)
        "ae1_new_thing_faced_approached_inspected_then_disappointed",
        "ae2_frame_fill_arrives_without_the_sensor",
        "ae3_edge_during_approach_stops_startles_and_abandons",
        "ae4_person_greeted_then_ignored_during_cooldown",
        "ae5_camera_unavailable_wanders_as_before",
        "ae6_camera_open_only_while_curious",
        "target_lost_during_approach_gives_up",
        "renamed_target_is_kept_by_overlap",
        "different_thing_elsewhere_is_not_the_target",
        "unsure_sighting_is_puzzled_and_stays",
        "sensor_arrival_ignored_in_leg_grace",
        "sensor_arrival_after_grace_arrives",
        "cpl2_in_leg_grace_stops_the_leg_and_looks_again",
        "edge_at_leg_start_refuses_without_driving",
        "close_at_leg_start_arrives_without_driving",
        "hazard_at_curiosity_turn_start_refuses",
        "close_but_off_centre_arrives_instead_of_turning",
        "curiosity_requested_now_starts_at_the_next_pause_end",
        "stale_looks_end_the_stop_without_turning_curiosity_off",
        "lease_loss_mid_approach_stops_and_closes_the_camera",
        "camera_without_looks_turns_curiosity_off",
        # Claude picks and speaks (explore on Claude plan U4, AE1, AE4)
        "claude_ae1_turns_to_the_picked_frame_and_offset_and_speaks_without_driving",
        "claude_pick_on_a_detector_box_of_the_same_kind_is_approached_before_speaking",
        "claude_nothing_interesting_resumes_without_speaking",
        "claude_ae4_unreachable_thinks_tries_twice_then_turns_back_to_the_detector_pick",
        "claude_camera_closed_while_asking_and_speaking_reopened_only_for_face_and_approach",
        "claude_look_request_carries_recent_picks_and_the_people_cool_down_holds",
        "claude_say_that_never_finishes_ends_at_the_backstop",
        "claude_scan_keeps_every_look_even_after_a_sighting",
        "claude_failed_first_try_is_retried_then_spoken",
        "claude_reopen_gap_counts_in_the_first_look_budget",
        # Meeting and remembering people (explore on Claude plan U5, AE2, AE3, AE5)
        "meet_ae3_known_person_is_greeted_by_name_and_touched",
        "meet_ae2_new_person_who_gives_a_name_is_stored_and_remembered",
        "meet_ae5_no_reply_says_the_friendly_line_and_stores_nothing",
        "meet_ae5_reply_without_a_name_is_stored_unnamed",
        "meet_reply_without_a_pattern_waits_for_claude_to_find_the_name",
        "meet_unsure_match_runs_the_new_person_flow",
        "meet_refused_match_asks_text_only_lines_then_the_name",
        "meet_refused_match_and_failed_lines_play_the_name_clip_without_asking",
        "meet_known_person_without_a_name_is_greeted_with_the_unnamed_line",
        "meet_match_reference_beyond_the_gallery_is_a_new_person",
        "meet_listen_failure_resumes_within_the_budget",
        "meet_listen_that_never_answers_ends_at_its_deadline",
        "meet_remember_failure_resumes_within_the_budget",
        "meet_match_that_never_answers_resumes_within_the_budget",
        "meet_match_request_never_carries_names",
        "meet_camera_stays_closed_and_eyes_think_while_matching",
        # FaceCrop geometry (KTD5) and Claude's replies (U6)
        "face_crop_expands_the_face_hit_1_6x",
        "face_crop_has_no_top_of_person_fallback",
        "face_crop_square_shrinks_and_stays_inside_a_small_frame",
        "face_crop_region_is_the_person_box_in_pixels_clamped_with_an_even_width",
        "face_crop_reads_pixel_and_normalized_boxes_alike",
        "replies_look_accepts_a_normalized_box",
        # The face crop from a fresh look (owner report: a stored face showed the wall)
        "meet_face_is_cut_from_a_fresh_look_after_turning_with_the_detectors_box",
        "meet_without_a_person_box_in_the_fresh_look_uses_claudes_box_in_its_own_frame",
        "meet_the_person_box_is_the_one_matching_the_pick",
        "meet_faceless_new_person_is_asked_but_never_stored_or_promised",
        "meet_faceless_without_a_hello_line_says_the_friendly_line",
        "replies_look_reads_the_frame_box_kind_and_line",
        "replies_name_keeps_one_or_two_words_and_fills_the_placeholder",
        # The fixes from Explore on Claude's first live test
        "tuning_defaults_roam_45_to_90_s_and_two_10_s_claude_tries",
        "claude_other_pick_on_a_differently_named_detector_box_faces_without_driving",
        "claude_other_pick_with_a_synonym_or_shared_word_is_approached",
        "claude_pick_needs_iou_0_3_not_a_centre_inside_its_box",
        "claude_repeat_of_a_recent_thing_is_nothing", "claude_living_pick_during_cool_down_is_nothing",
        "prompt_cool_down_firmly_rules_out_people_and_animals", "prompt_recent_picks_are_ruled_out",
        "claude_camera_closed_at_speak_entry_on_every_path",
        "claude_speech_waits_for_an_in_flight_detector_run",
        "claude_speech_goes_ahead_when_the_camera_never_goes_quiet",
        "claude_stops_are_45_to_90_s_apart_and_he_roams_between",
        # A hazard on the way to Claude's pick: safety first, then the line from where he is
        "claude_obstacle_during_orient_says_the_line_after_the_escape",
        "claude_edge_during_approach_says_the_line_after_the_escape",
        "meet_hazard_on_the_way_to_a_person_drops_the_meet",
        "claude_line_older_than_the_freshness_window_is_not_spoken",
        "claude_hazard_mid_speak_neither_cuts_nor_repeats_the_line",
        # Heading, measured turns and the leg log (explore nav plan U2, KTD1)
        "heading_turn_takes_the_shorter_way_across_0_360",
        "heading_bias_drift_is_re_estimated_at_each_stop",
        "heading_motion_during_a_stop_leaves_the_bias_alone",
        "measured_90_degree_turn_without_bias_error_ends_within_tolerance",
        "measured_turn_overshoot_is_learned_and_the_next_turn_is_closer",
        "stalled_leg_logs_no_distance_for_the_stalled_time",
        "clean_drive_off_restarts_the_leg_log",
        "uncalibrated_turns_stay_timed_even_with_gyro_readings",
        "calibrated_without_gyro_in_the_readings_turns_stay_timed",
        "measured_escape_turn_turns_its_angle_not_its_time",
        "measured_orient_turns_to_the_picked_looks_heading_plus_its_offset",
    )

    @classmethod
    def setUpClass(cls):
        jdk = jvm_harness.find_jdk()
        if jdk is None:
            raise unittest.SkipTest("no JDK (javac + java) found")
        cls._td = tempfile.TemporaryDirectory(prefix="explore_brain_harness_")
        out = cls._td.name
        # No shared/src on the sourcepath: the brain must compile on its own.
        c = subprocess.run(jvm_harness.javac_cmd(jdk[0], out, [HARNESS_MAIN], [HARNESS, EXPLORE_SRC]),
                           capture_output=True, text=True)
        cls.compiled = c.returncode == 0
        cls.compile_output = (c.stdout + c.stderr)[-3000:]
        cls.results = {}
        cls.run_output = ""
        if c.returncode == 0:
            r = subprocess.run([jdk[1], "-cp", out, "com.miko3.mode.explore.ExploreBrainHarness"],
                               capture_output=True, text=True, timeout=60)
            cls.run_output = (r.stdout + r.stderr)[-6000:]
            cls.results = jvm_harness.parse_verdicts(r.stdout)

    @classmethod
    def tearDownClass(cls):
        cls._td.cleanup()

    def setUp(self):
        self.assertTrue(self.compiled, f"harness failed to compile:\n{self.compile_output}")

    def _assert_pass(self, name):
        self.assertIn(name, self.results, f"scenario {name} never reported:\n{self.run_output}")
        verdict, detail = self.results[name]
        self.assertEqual(verdict, "PASS", f"{name}: {detail}")

    def test_harness_reports_exactly_the_expected_scenarios(self):
        self.assertEqual(sorted(self.results), sorted(self.SCENARIOS), self.run_output)


jvm_harness.add_scenario_tests(ExploreBrainHarnessTest)


if __name__ == "__main__":
    unittest.main()
