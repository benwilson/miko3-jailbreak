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
              "ExplorePrompts.java", "Openness.java", "Brightness.java", "Heading.java", "ExploreCalibration.java",
              "RoamSteer.java", "EscapePlanner.java", "Coverage.java")


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
        "ae6_camera_follows_the_camera_rule_through_stops_and_lease_loss",
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
        # The camera while roaming, steering by openness, look-then-go (explore nav plan U4, KTD2, KTD7, KTD9)
        "roam_camera_open_in_every_roaming_state",
        "roam_speak_stops_closes_the_camera_then_reopens_after_the_gap_and_roams",
        "roam_camera_closed_through_meet_ask_name_listen_name_remember_and_name_clip",
        "roam_steer_blocked_left_open_right_bends_right",
        "roam_steer_bend_is_a_measured_turn_when_the_heading_is_usable",
        "roam_steer_all_blocked_gives_a_short_leg_or_a_turn_never_a_full_leg",
        "roam_floor_hazard_mid_leg_aborts_even_when_the_camera_reads_open",
        "roam_no_looks_backs_off_roams_on_the_floor_sensor_and_retries",
        "roam_look_then_go_opens_the_camera_only_at_leg_decisions",
        "roam_lease_loss_closes_the_camera_and_goes_eyes_only",
        "roam_invariant_flags_a_camera_open_while_talking_or_without_the_lease",
        "roam_steer_low_confidence_keeps_todays_legs",
        "roam_blocked_look_mid_leg_ends_the_leg_at_the_next_tick",
        "roam_floor_is_taught_after_driving_over_it_and_motion_is_reported",
        # Wedged escapes: retrace, measured circle, Claude's way out, rest (explore nav plan U5, AE1, AE7)
        "escape_ae1_wall_and_plant_retraces_the_way_in_and_roams_again",
        "escape_retrace_hazard_partway_moves_on_to_the_circle",
        "escape_three_short_legs_retrace_newest_first_up_to_the_retrace_distance",
        "escape_claude_frame_5_centre_turns_to_that_frames_heading_and_drives_off",
        "escape_ae7_offline_uses_the_most_open_heading_on_the_robot",
        "escape_on_robot_heading_penalises_headings_already_tried",
        "escape_late_claude_answer_is_dropped_and_the_robot_heading_used",
        "escape_frame_7_of_6_is_rejected_and_the_robot_heading_used",
        "replies_way_out_reads_frame_and_x_and_rejects_bad_answers",
        "escape_full_budgets_drive_off_within_30_s_of_wedged",
        "escape_second_ask_is_sent_once_per_escape",
        "escape_all_steps_fail_rests_cornered_then_roams",
        "escape_lease_loss_during_the_circle_goes_eyes_only_stopped",
        "escape_way_out_carries_only_the_circles_frames_and_notes_carry_counts",
        "escape_uncalibrated_keeps_todays_escape",
        # A measured turn the gyro says isn't turning is blocked (live: wedged under a desk)
        "turn_flat_yaw_in_the_circle_is_blocked_within_1_5_s_and_the_escape_advances",
        "turn_flat_yaw_while_roaming_is_blocked_and_counts_as_wedged",
        "turn_slow_but_moving_is_not_blocked",
        # Turns blocked: back out straight along the last leg first (live: under a desk)
        "escape_blocked_turns_back_out_along_the_last_leg_then_turn_and_drive_off",
        "escape_back_out_stops_at_the_logged_distance_and_the_retrace_distance",
        "escape_back_out_needs_a_logged_leg",
        "escape_stall_during_back_out_stops_it",
        # Pinned: one ladder, then longer rests while still pinned, reset by a clean drive-off
        "pinned_runs_the_ladder_once_with_two_asks_then_rests",
        "pinned_after_the_rest_waits_longer_before_the_next_ladder",
        "pinned_backoff_resets_after_a_clean_drive_off",
        # A step's budget covers its turns (live 2026-09-25: ~40 deg/s on carpet, drive-off cut mid-turn)
        "budget_drive_off_turn_at_40_deg_s_completes_and_drives_off",
        "budget_slow_but_progressing_turn_is_never_cut_by_the_step_budget",
        "budget_blocked_drive_off_turn_still_fails_the_step_within_1_5_s",
        "budget_turn_rate_defaults_35_deg_s_floor_15_and_the_rate_is_learned",
        # Forward first: after a rest, and when a step ends, facing clear floor (live 2026-09-25)
        "forward_first_after_a_rest_facing_open_floor_drives_forward_instead_of_turning",
        "forward_first_after_a_rest_facing_a_blocked_way_still_turns_and_rests_longer",
        "forward_first_when_an_escape_step_runs_out_of_time_facing_clear_floor",
        # The avoided side, at the motor (live 2026-09-25: "he only tries turning left")
        "side_left_blocked_roaming_retry_commands_right_every_time",
        "side_left_blocked_escape_ladder_commands_no_left_turn_until_free",
        "side_left_blocked_the_turn_after_a_rest_and_the_next_ladder_go_right",
        "side_both_blocked_alternates_instead_of_one_side_for_ever",
        # Wedged: a straight back-up first, then the free way round (live 2026-09-25: nose to a wall)
        "wedged_nose_to_wall_backs_up_first_then_turns_the_free_way_and_drives_off",
        "wedged_back_up_blocked_behind_goes_on_with_the_ladder",
        "wedged_retrace_long_way_round_past_a_blocked_side_is_skipped",
        # The learned turn rate: completed turns only (live 2026-09-25: collapsed to the floor)
        "turn_rate_learns_only_from_completed_turns",
        # A blocked side: the retry and later turns go the other way (live 2026-09-25)
        "blocked_side_backs_up_then_turns_the_other_way_and_drives_off",
        "blocked_side_escape_turns_go_the_unblocked_way_even_the_long_way_round",
        "blocked_turn_back_up_default_is_about_2_s",
        # Signed wheel counters (live 2026-09-25: reverse counts down, from 0 at power-up)
        "wheels_negative_counts_back_out_reports_the_real_distance",
        "wheels_stall_is_detected_below_and_across_zero",
        # A blocked turn backs up a little first, then tries again once (live: pinned after a CPL stop)
        "blocked_turn_backs_up_a_little_then_the_retried_turn_succeeds",
        "blocked_turn_after_a_cpl_stop_still_backs_up_a_little_before_the_retry",
        "blocked_turn_backs_up_at_most_once_per_turn_and_stops_on_a_stall",
        # Open doorways through Claude (explore nav plan U6, AE2, AE3, AE7)
        "doorway_right_third_sets_a_heading_20_deg_right_and_legs_bend_that_way",
        "doorway_none_leaves_the_steering_unchanged",
        "doorway_second_ask_waits_the_60_s_interval",
        "doorway_never_asked_during_a_stop_an_approach_a_meeting_or_an_escape",
        "doorway_ae7_offline_ask_fails_quietly_and_roaming_continues",
        "doorway_ae3_floor_edge_at_the_doorway_stops_and_escapes_as_today",
        "doorway_heading_expires_by_time_or_distance_and_steering_returns_to_openness",
        "doorway_closed_since_reads_blocked_when_faced_and_is_dropped",
        "doorway_passed_through_after_a_leg_toward_it_is_forgotten",
        "doorway_ask_carries_one_roaming_frame_and_notes_carry_numbers_only",
        "roam_steer_doorway_weights_open_bands_and_turns_to_face_one_out_of_view",
        "replies_doorway_reads_x_and_rejects_bad_answers",
        # People while roaming, with a per-person leave-alone (explore nav plan U7, R9, R10, AE4)
        "people_roaming_person_is_approached_to_the_polite_distance_and_greeted_by_name",
        "people_ae4_same_person_5_min_later_is_checked_and_left_alone",
        "people_different_person_5_min_later_is_approached",
        "people_check_timeout_or_offline_never_approaches",
        "people_after_10_min_no_check_and_approached",
        "people_a_then_b_then_a_check_covers_both_and_a_is_left_alone",
        "people_curiosity_pick_of_the_owner_5_min_later_is_a_remark",
        "people_owner_in_view_3_min_gets_at_most_3_checks",
        "people_hazard_during_a_roaming_approach_drops_the_meeting",
        "people_camera_closed_through_a_roaming_meetings_talking_states",
        "people_trace_notes_never_carry_a_name",
        "replies_recently_met_reads_same_none_unsure_and_rejects_bad_answers",
        "people_tuning_defaults_10_min_leave_alone_one_check_a_minute",
        # Going somewhere new (explore nav plan U10, R18)
        "coverage_open_room_covers_more_cells_than_with_novelty_off",
        "coverage_two_equally_open_ways_picks_the_unvisited_one",
        "coverage_open_floor_gives_a_longer_leg_and_a_blocked_view_still_shortens",
        "coverage_floor_sensor_still_ends_a_long_leg",
        "coverage_visited_cells_fade_so_an_old_area_is_eligible_again",
        "coverage_no_look_turns_less_while_the_way_ahead_is_new",
        "coverage_uncalibrated_roams_exactly_as_before",
        "coverage_trace_notes_carry_counts_only_and_are_forgotten_at_shutdown",
        # The leg decision waits for a look to steer by (explore nav plan KTD9, live 2026-09-25)
        "steer_waits_for_a_look_after_a_turn_and_uses_the_legs_own_looks",
        "steer_wait_times_out_to_todays_leg_and_never_waits_with_the_camera_backed_off",
        # CPL hiccups on plain floor (owner-approved 2026-09-25)
        "cpl_on_plain_floor_is_retried_once_and_the_leg_drives_on",
        "cpl_again_at_the_same_spot_after_the_retry_is_a_hazard",
        "cpl_with_our_sensor_at_an_edge_is_a_hazard_at_once",
        "cpl_hiccups_spread_over_a_leg_do_not_make_him_wedged",
        # Mid-leg re-aim (owner-approved 2026-09-25, KTD9)
        "reaim_open_space_drifting_right_mid_leg_turns_a_little_toward_it_and_drives_on",
        "reaim_never_with_the_open_space_straight_ahead",
        "reaim_is_rate_limited",
        "reaim_never_toward_a_blocked_side",
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
