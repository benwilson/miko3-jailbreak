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
import re
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
              "RoamSteer.java", "EscapePlanner.java", "Coverage.java", "PlaceMemory.java", "Ears.java", "ChatSession.java",
              "FaceMigration.java", "FaceMatcher.java", "AnswerParser.java", "NameResolver.java", "ChatTools.java",
              "LearnLog.java", "ChatActions.java")


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


class NamelessReplySourceTest(unittest.TestCase):
    """R19: a reply without a name is welcomed and nothing is stored."""

    def test_name_step_welcomes_a_nameless_reply_instead_of_remembering(self):
        # Face plan U7 (KTD6): a name no longer goes straight to remember(); it goes to the
        # resolver, which may join, ask the last name, or store through remember() later.
        src = (EXPLORE_PKG / "ExploreBrain.java").read_text()
        m = re.search(r"private void nameStep\(long now\) \{(.*?)\n    \}", src, re.S)
        self.assertIsNotNone(m)
        body = m.group(1)
        self.assertIn("name == null", body)
        self.assertLess(body.index("name == null"), body.index("resolve(now, name)"))
        self.assertLess(body.index("port.welcome("), body.index("resolve(now, name)"))
        self.assertNotIn("port.remember(", body)
        self.assertNotIn("remembering them unnamed", body)
        self.assertNotIn("(or unnamed)", src)


class ExploreBrainHarnessTest(unittest.TestCase):
    """One harness run, one assertion per scenario."""

    SCENARIOS = (
        "claude_rate_limited_ask_is_not_retried_and_stops_skip_claude_until_the_pause_ends",
        "claude_look_budget_caps_asks_per_minute_and_the_excess_stops_use_the_detector",
        "chat_turn_in_a_short_claude_pause_says_one_sec_waits_and_carries_on",
        "chat_turn_in_a_long_claude_pause_signs_off_without_another_request",
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
        "classifier_charger_flag_is_motion_refused",
        "classifier_cpl2_is_forward_refused_never_charging",
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
        # Dark-floor mode (owner 2026-10-02: a black floor the ToF never sees; TOFDS)
        "dark_classifier_no_return_tof_is_clear_past_the_frozen_window",
        "dark_classifier_off_no_return_tof_stays_unavailable",
        "dark_classifier_ignores_the_ir_edge_flag",
        "dark_classifier_valid_low_tof_is_still_an_obstacle",
        "dark_classifier_cpl2_is_still_a_refusal",
        "dark_classifier_tilt_needs_two_readings_and_is_a_tilt_hazard",
        "dark_classifier_lifted_is_unavailable_until_flat_again",
        "dark_classifier_off_ignores_the_accelerometer",
        "dark_approach_no_return_tof_is_clear_not_edge",
        "dark_classifier_switching_off_needs_floor_readings_again",
        "dark_floor_off_no_return_tof_stays_eyes_only",
        "dark_floor_on_leaves_eyes_only_and_roams_on_no_return_tof",
        "dark_floor_switched_on_live_leaves_eyes_only",
        "dark_floor_tilt_stops_backs_off_and_logs_it",
        "dark_floor_lifted_stops_and_waits_until_flat",
        "dark_floor_caps_the_leg_length",
        "dark_floor_off_legs_are_not_capped",
        "dark_floor_leg_writes_a_floor_record",
        "dark_floor_off_writes_no_floor_record",
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
        "meet_ae5_reply_without_a_name_is_welcomed_and_nothing_is_stored",
        "meet_ae5_reply_without_a_name_and_no_hello_line_says_the_friendly_line",
        "meet_ae5_name_request_that_never_answers_is_welcomed_at_the_deadline",
        "meet_reply_without_a_pattern_waits_for_claude_to_find_the_name",
        "meet_refused_match_asks_text_only_lines_then_the_name",
        "meet_refused_match_and_failed_lines_play_the_name_clip_without_asking",
        "meet_known_person_without_a_name_is_greeted_with_the_unnamed_line",
        "meet_listen_failure_resumes_within_the_budget",
        "meet_listen_that_never_answers_ends_at_its_deadline",
        "meet_a_name_answer_started_in_time_is_heard_past_the_listen_deadline",
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
        "tuning_defaults_roam_25_to_40_s_and_two_10_s_claude_tries",
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
        # Fully jammed (robot 2026-10-01, stuck under a chair): stop pushing and ask for help
        "jammed_nothing_moves_one_help_line_one_probe_per_rest_no_ladder_loop",
        "jammed_help_line_at_most_every_five_minutes",
        "jammed_probe_that_moves_resumes_roaming",
        "jammed_moved_from_outside_probes_at_once",
        "jammed_partial_block_one_way_free_runs_the_escape",
        # The long wriggle (robot 2026-10-01: an 11 s spin freed him under the chair)
        "wriggle_slow_wheels_then_the_heading_turns_after_6s_frees_him_where_the_short_turns_gave_up",
        "wriggle_nothing_moves_each_way_stops_within_1500ms_then_the_help_line",
        "wriggle_wheels_spin_heading_never_moves_full_time_both_ways_then_help_one_per_two_minutes",
        "wriggle_two_blocked_escape_turns_in_a_row_wriggles_before_the_ladder_grinds",
        "wriggle_the_1408_chair_episode_wedge_turn_stalled_back_up_blocked_retrace_is_caught",
        # The post-stall recovery wait (robot 2026-10-01: the motor board refused all motion for 9-29 s)
        "recover_a_first_zero_turn_with_no_stall_before_it_waits_for_the_board_and_backs_out",
        "recover_a_12s_cutout_probes_find_nothing_until_20s_then_the_normal_escape",
        "recover_motors_that_never_come_back_three_recoveries_then_wriggle_then_the_help_line",
        "recover_spell_resets_after_clean_driving_so_a_fourth_stall_two_minutes_on_still_waits",
        "recover_probe_schedules_alternate_when_an_alternate_is_set",
        "replies_turn_reads_addressed_action_and_target",
        "intent_go_away_ends_after_the_line_turns_away_and_leaves_them_alone_10_min",
        "intent_go_elsewhere_seeks_at_once_and_avoids_this_spot_15_min",
        "intent_find_person_named_meets_only_that_face",
        "intent_find_person_unnamed_takes_anyone_new_and_nobody_drops_it_after_5_min",
        "intent_come_here_searches_toward_their_side_and_approaches_with_no_answer_clip",
        "intent_be_quiet_is_do_not_disturb_for_10_min_then_expires",
        "intent_a_call_interrupts_it_and_the_intent_is_dropped",
        "intent_an_unable_request_is_action_none_with_an_honest_line_and_the_talk_goes_on",
        # Owner 2026-10-03: the action tools and run_task's workflows.
        "act_move_turn_around_turns_and_is_done_once_he_is_still_with_no_leg",
        "act_move_forward_drives_one_capped_leg_and_back_backs_up_with_no_escape_turn",
        "act_stay_holds_him_still_and_a_call_is_answered_where_he_stands",
        "act_find_thing_ends_on_sight_and_goes_over_to_it",
        "act_go_to_place_is_done_when_a_look_shows_what_marks_it",
        "act_an_aimed_turn_never_flips_from_a_blocked_side_and_drives_only_facing_its_aim",
        "act_a_move_into_what_just_stopped_him_or_the_same_failed_move_is_refused",
        "task_a_driving_step_never_starts_while_he_waits_for_the_motor_board",
        "act_go_away_when_he_cant_drive_leaves_them_alone_with_no_leg_later",
        "act_find_thing_close_already_drives_nothing_and_a_block_in_the_approach_ends_it_done",
        "task_a_look_and_its_consult_never_name_a_bathroom_thing",
        "task_three_steps_run_in_order_and_end_done_with_a_record",
        "task_a_failed_step_consults_claude_and_the_revised_plan_succeeds",
        "task_out_of_consults_ends_it_with_a_spoken_line",
        "task_a_hey_miko_call_drops_it_at_once",
        "task_bathroom_privacy_ends_it_with_no_consult_and_no_frame",
        "task_docked_ends_it_and_a_drive_is_refused_while_docked",
        "act_stop_in_a_conversation_ends_the_task_at_once_and_the_talk_goes_on",
        "chat_tool_look_fast_path_sends_only_a_fresh_frame_the_detector_checked_with_its_labels",
        # Robot 2026-10-03: backed up against a wall, he drives forward out.
        "stuck_wall_behind_a_turn_on_the_spot_stalls_and_he_drives_forward_out_no_jam_no_help",
        "stuck_a_back_up_that_goes_nowhere_makes_the_next_probe_forward",
        "chat_three_turns_not_said_to_him_end_the_conversation",
        "chat_a_turn_said_to_him_resets_the_count_and_the_talk_goes_on",
        "chat_no_reply_said_to_him_for_45_s_ends_it_politely",
        "chat_a_turn_not_said_to_him_speaks_nothing",
        "recover_robot_16_42_a_blocked_turn_after_the_back_out_recovers_again_and_he_gets_free",
        "recover_no_cutout_the_first_probe_moves_and_the_escape_goes_on",
        "recover_a_call_while_waiting_is_answered_where_he_stands",
        "recover_a_shove_while_waiting_probes_at_once",
        "recover_a_turn_that_reads_nothing_after_a_bump_waits_and_its_attempts_never_count",
        "recover_desk_rig_backs_out_the_way_he_came_within_15_s_no_jam_no_help_line",
        "recover_behind_blocked_probes_turn_after_two_still_back_ups_and_a_slipping_turn_is_that_way_blocked",
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
        "wedged_back_up_blocked_behind_tries_forward_first_and_drives_off",
        "wedged_retrace_long_way_round_past_a_blocked_side_is_skipped",
        # The learned turn rate: completed turns only (live 2026-09-25: collapsed to the floor)
        "turn_rate_learns_only_from_completed_turns",
        # A blocked side: the retry and later turns go the other way (live 2026-09-25)
        "blocked_side_backs_up_then_turns_the_other_way_and_drives_off",
        "blocked_side_waits_3_s_after_the_back_up_before_trying_the_other_way",
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
        "doorway_reading_blocked_when_faced_takes_a_short_leg_toward_it_and_an_obstacle_there_drops_it",
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
        # A visual place memory (owner 2026-10-01: somewhere else than the last 30 minutes)
        "place_a_familiar_view_is_noted_and_its_novelty_lowered",
        "place_the_steer_spends_more_time_facing_the_unfamiliar_half",
        "place_an_unusable_heading_still_lowers_the_view_it_has_seen",
        "place_plain_frames_roam_exactly_as_with_no_prints",
        "place_memory_is_forgotten_at_shutdown",
        # Seeking the unfamiliar (owner 2026-10-01: "find something that's unfamiliar and drive towards it")
        "seek_bearing_is_exact_at_the_centre_the_edge_and_a_quarter_of_the_width",
        "seek_tuning_defaults_familiar_two_scans_every_three_minutes",
        "seek_familiar_room_triggers_and_claudes_frame_and_x_give_the_heading",
        "seek_legs_follow_the_heading_and_a_relook_recentres_it",
        "seek_without_claude_falls_back_to_the_least_familiar_frame",
        "seek_a_blocked_leg_ends_the_seek_cleanly",
        "seek_after_arrival_the_next_seek_chooses_a_different_place",
        "seek_never_in_a_room_that_looks_new",
        "seek_a_person_box_mid_seek_is_passed_by_and_the_seek_reaches_its_target",
        "seek_a_call_during_a_seek_is_answered",
        # Seeking somewhere new by time and area (owner 2026-10-01: "if he's been somewhere in the
        # last 30 minutes, he should try and find somewhere else to go")
        "seek_tuning_defaults_every_five_minutes_or_a_small_area_over_three",
        "seek_claude_always_has_a_remark_still_seeks_within_five_minutes",
        "seek_circling_in_a_one_metre_area_seeks_early",
        "seek_just_sought_waits_seek_gap_before_seeking_again",
        "seek_a_call_during_a_timed_seek_is_answered",
        "replies_seek_reads_frame_and_x_or_none_and_the_prompt_carries_numbers_and_labels_only",
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
        # The harness surface for cues and conversations (meeting plan U6, KTD3, KTD4, KTD7, KTD8)
        "ears_cue_at_t_is_drained_on_the_next_step_with_its_kind_tier_side_and_angle",
        "ears_trend_and_shove_spikes_are_step_input",
        "listen_script_answers_turns_1_to_3_then_silence_on_turn_4_each_after_its_own_delay",
        "listen_script_never_answers_and_a_line_meanwhile_is_a_say_while_the_mic_is_open",
        "listen_while_a_line_or_a_clip_plays_is_a_violation",
        "turn_script_returns_the_per_turn_fields_and_cancel_drops_a_late_answer",
        "notes_delta_forget_ears_and_clip_window_are_recorded_by_the_fake",
        "vision_person_box_dropped_at_t_reports_no_facing_face_afterwards",
        "vision_profile_shaped_box_from_t_reports_no_facing_face_afterwards",
        "chat_tuning_defaults_follow_the_plans_assumptions",
        "chat_session_starts_thinking_with_the_four_chat_states_and_no_behaviour",
        # Cues, the lean-in and the turn to the voice (meeting plan U7; R1-R3, R6-R9, R15; KTD3-KTD6, KTD8)
        "cue_strong_from_the_left_mid_hop_stops_within_one_step_and_turns_left",
        "cue_weak_with_no_face_after_the_look_and_its_opposite_resumes_quietly_within_the_budget",
        "cue_weak_that_finds_two_profile_faces_resumes_quietly",
        "cue_strong_from_behind_is_found_on_the_third_look",
        "cue_miko_miko_800_ms_apart_is_one_search_and_the_second_is_the_caller_talking",
        "cue_strong_from_the_opposite_side_during_the_turn_retargets_once_not_twice",
        "cue_during_ask_cancels_the_ask",
        "cue_during_a_playing_line_is_held_and_taken_when_it_ends",
        "cue_before_the_line_starts_drops_the_remark_and_turns",
        "cue_during_orient_is_held_until_the_remark_is_said",
        "weak_cue_before_the_line_starts_still_says_the_remark",
        "call_during_orient_drops_the_pick_and_is_answered",
        "cue_during_startle_is_held_until_pause",
        "cue_during_cornered_rest_is_taken",
        "cue_same_side_during_approach_continues_it",
        "cue_opposite_side_during_approach_abandons_it_and_turns",
        "cue_shove_while_stopped_arms_a_weak_cue_and_looks_ahead_then_behind",
        "cue_shove_300_ms_after_a_motor_command_does_not_arm",
        "cue_forward_stall_stamps_a_bump_and_sorry_within_2_s_is_strong_held_until_the_escape_ends",
        "cue_sorry_4_s_after_the_bump_is_weak",
        "cue_charger_keeps_the_ears_open_and_a_call_there_is_answered_in_place",
        "ears_lost_reopens_with_backoff",
        "ears_flapping_session_keeps_backing_off",
        "ears_lost_on_the_charger_reopens_on_the_same_backoff",
        "cue_eyes_only_a_name_call_is_answered_meets_without_moving_and_a_second_call_waits_for_the_meeting",
        "cue_held_strong_older_than_10_s_becomes_a_lean_in",
        "cue_facing_face_plays_the_acknowledgement_in_a_clip_window_meets_and_stamps_the_stages",
        "cue_trend_under_the_stop_band_ends_the_turn_early",
        "cue_trend_growing_means_the_voice_is_behind_and_ends_the_turn",
        "chat_known_person_opener_carries_the_notes_and_persona_after_the_acknowledgement",
        "chat_three_turns_then_catch_you_later_signs_off_persists_once_and_resumes_away_from_them",
        "chat_silence_twice_with_the_face_gone_at_the_first_look_ends_without_a_sign_off",
        "chat_silence_twice_with_the_face_still_there_signs_off_once",
        # Robot 2026-10-01: an answer that has started holds the listen past its 4 s.
        "chat_an_answer_started_at_2_5_s_and_ended_at_7_3_s_is_heard_with_no_unanswered_listen",
        "chat_a_provisional_answer_starts_the_same_turn_early_and_only_the_final_answer_lets_it_speak",
        "chat_a_changed_answer_asks_with_its_final_words_and_a_provisional_goodbye_starts_nothing",
        "chat_a_streamed_turns_late_notes_join_this_conversations_notes_and_stale_ones_never_do",
        "turn_flight_a_speculation_is_used_only_by_a_turn_with_the_same_request",
        "turn_flight_the_early_line_goes_first_and_its_notes_follow_as_late_notes",
        "turn_flight_a_re_request_drops_the_rejected_replys_late_notes",
        "turn_flight_a_turn_no_longer_asked_takes_no_line_and_no_late_notes",
        "chat_a_complaint_is_passed_on_once_after_its_streamed_line",
        "chat_a_whole_turns_feedback_is_passed_on_with_it",
        "chat_feedback_in_a_call_on_the_charger_says_so_in_its_context",
        "chat_small_talk_passes_on_no_feedback",
        "chat_feedback_from_someone_unnamed_carries_no_id",
        "chat_late_feedback_left_from_another_conversation_is_dropped",
        "turn_flight_a_streamed_turns_feedback_follows_as_late_feedback",
        "turn_flight_a_dead_or_replaced_turn_brings_no_late_feedback",
        "replies_turn_reads_feedback_and_ignores_a_malformed_one",
        "chat_a_silent_listen_is_still_the_first_unanswered_listen_at_4_s",
        "chat_an_answer_whose_words_never_come_ends_as_unanswered_at_the_fallback",
        "chat_an_older_launcher_that_never_says_answering_ends_the_listen_at_4_s_as_today",
        "chat_a_three_sentence_line_is_cut_to_two_before_speaking",
        "chat_a_repeated_question_is_re_requested_once_and_a_second_repeat_is_stripped_and_counted",
        "chat_ends_conversation_true_is_spoken_as_a_normal_line_and_the_conversation_goes_on",
        "chat_ten_conversations_accumulate_notes_and_the_tenth_never_repeats_a_recorded_question",
        "chat_a_newcomer_mid_reply_gets_the_glance_and_one_sec_only_after_the_listen_ends",
        "chat_forget_me_from_a_named_person_confirms_by_name_and_yes_forgets_by_id_and_clears_the_notes",
        "chat_forget_me_not_confirmed_keeps_them_and_dont_forget_me_is_just_a_reply",
        "chat_forget_me_from_an_unnamed_person_plays_nothing_kept_and_calls_no_store",
        "chat_a_name_given_on_turn_four_keeps_the_crop_at_once_and_x9_lol_is_no_name",
        "chat_a_known_conversation_whose_name_given_differs_makes_a_new_record_and_never_writes_the_old_id",
        "chat_newcomer_wake_word_above_the_angle_is_held_and_greeted_after_and_inside_the_angle_is_a_reply",
        "chat_a_retried_turn_whose_first_reply_arrives_late_does_not_merge_its_delta_twice",
        "chat_unreachable_twice_ends_with_the_local_sign_off_within_the_budget_and_merges_the_notes_once",
        "chat_a_refusal_plays_the_deflection_and_the_conversation_continues",
        "chat_a_persona_edit_between_turns_is_heard_only_in_the_next_conversation",
        "chat_docking_mid_conversation_keeps_it_going_then_docks_quietly_with_no_resume_leg",
        "chat_tools_texts_share_no_one_elses_notes_and_no_bathroom",
        "chat_tool_round_says_the_preamble_then_looks_with_the_detector_and_waits_for_the_line",
        "chat_tool_look_in_do_not_disturb_or_bathroom_privacy_is_refused_without_a_frame",
        "chat_lease_lost_mid_conversation_continues_without_the_look_and_a_6_s_sensor_stall_ends_it",
        "chat_eyes_only_wake_word_opens_a_stranger_conversation_without_a_turn_or_a_match_and_stores_nothing",
        "chat_the_owners_note_is_looked_up_by_a_face_matched_stored_name_or_the_spoken_name_only",
        "chat_the_transcript_never_appears_in_the_trace",
        # The call: slot, verdict and answer (hey-miko plan U5; R1-R4, R11; KTD1-KTD5; AE1-AE3, AE6)
        "call_ae1_met_two_minutes_ago_a_wake_word_is_still_answered_and_searched",
        "call_ae2_a_wake_word_during_the_back_off_waits_for_it_then_answers_and_searches",
        "call_two_calls_before_the_answer_merge_into_one_and_the_newer_angle_wins",
        "call_docked_cycling_through_startle_and_back_off_answers_in_place_without_moving",
        "call_ae3_no_angle_in_chat_listen_is_the_partner_speaking_and_makes_no_call",
        "call_a_second_caller_during_the_first_calls_meeting_waits_for_the_conversation_then_is_answered",
        "call_a_second_caller_during_the_first_calls_chat_listen_waits_for_the_conversation_then_is_answered",
        "call_a_second_call_with_no_angle_in_a_charger_meeting_is_answered_after_it_without_moving",
        "call_from_the_partners_angle_during_the_calls_meeting_or_conversation_makes_no_extra_call",
        "call_docked_in_a_chat_waits_until_it_ends_then_answers_and_meets_without_moving",
        "call_with_claude_unavailable_plays_the_answer_once_and_clears_the_slot",
        "call_in_approach_with_claude_unavailable_still_stops_for_the_answer",
    "call_lease_lost_during_its_search_is_retaken_in_eyes_only_without_a_second_answer",
        "call_ae6_on_the_charger_answers_and_holds_the_conversation_without_leaving_the_dock",
        "call_held_60_s_behind_a_long_conversation_is_still_answered_as_a_call_not_a_lean_in",
        "call_during_an_escape_is_answered_at_once",
        "call_in_approach_with_no_angle_answers_and_carries_on_the_same_approach",
        "call_in_approach_to_a_thing_answers_and_searches_instead_of_carrying_on",
    "call_an_already_called_end_of_utterance_makes_no_second_call",
        # Finding and reaching the caller (hey-miko plan U6; R5-R7, R9, R10; KTD6-KTD9; AE4, AE5)
        "call_ae4_no_angle_a_person_directly_behind_is_found_on_the_fifth_look_and_faced",
        "call_no_angle_and_nobody_makes_eight_45_deg_looks_one_full_circle_then_where",
        "call_ae5_after_where_a_new_wake_word_restarts_the_search_every_time_without_a_second_answer",
        "call_after_where_a_reply_opens_the_meeting_and_silence_resumes_roaming",
        "call_angle_plus_90_with_no_turn_since_the_sample_turns_90_right",
        "call_angle_plus_90_after_40_deg_of_right_turning_since_it_was_heard_turns_50",
        "call_angle_plus_90_after_40_deg_of_right_turning_without_a_usable_gyro_turns_50",
        "heading_history_without_the_gyro_counts_a_commanded_right_turn_at_the_nominal_rate",
        "call_angle_with_nobody_at_the_bearing_looks_at_both_45_deg_neighbours_then_where",
        "call_two_person_boxes_the_one_nearest_the_bearing_is_chosen",
        "call_far_box_faces_and_approaches_to_polite_then_meets_without_the_leave_alone_checks",
        "call_near_box_goes_straight_to_the_meeting_with_no_approach_legs",
        "call_a_tall_narrow_standing_person_counts_as_found",
        "call_hazard_during_its_approach_hands_the_call_back_and_it_is_retaken_after_the_escape",
        "call_during_its_search_a_call_from_the_other_side_retargets_and_one_with_no_angle_opens_the_meeting",
        "call_during_its_approach_a_far_side_angle_retargets_and_no_angle_merges",
        "call_one_s_after_a_camera_close_turns_at_once_and_the_first_look_waits_for_the_reopen_gap",
        "call_search_time_for_a_person_directly_behind_is_reported_and_found_at_the_robots_look_rate",
        # A call with a side but no angle, the call's look budget and person floor (R7; robot QA 2026-09-30)
        "call_left_side_no_angle_turns_about_90_left_first_and_finds_a_person_there_on_look_1",
        "call_left_side_no_angle_finds_a_person_at_135_left_on_look_2_or_3",
        "call_right_side_no_angle_mirrors_the_left_side_first_search",
        "call_side_no_angle_and_nobody_looks_side_neighbours_then_the_rest_of_one_circle",
        "call_with_no_side_and_no_angle_still_starts_straight_ahead",
        "call_a_person_box_scoring_0_28_counts_during_a_call_search",
        "call_score_floor_does_not_loosen_a_roaming_person_pick",
        "call_full_height_box_on_the_first_look_goes_straight_to_the_meeting",
        "call_looks_every_2000_ms_a_person_at_90_left_is_found",
        # An empty call look ends at its first fresh frame; callLookMs caps a stop with none
        "call_empty_circle_with_looks_every_2000_ms_ends_each_empty_stop_at_its_first_fresh_frame",
        "call_a_stop_with_no_fresh_frame_still_waits_out_call_look_ms",
        # A roaming person pick with no face in its box is a phantom (robot 2026-09-30)
        "roaming_person_pick_with_no_face_drops_the_meeting_quietly_and_roams_on",
        "roaming_blur_seen_again_within_the_phantom_cooldown_is_ignored",
        "roaming_person_pick_with_a_face_still_meets_and_converses",
        "call_started_faceless_meeting_still_converses",
        # Robot 2026-10-01: only a call may open a faceless meeting; a cue's needs a usable face.
        "cue_weak_then_a_person_box_with_no_face_is_not_met_and_nothing_is_said",
        "cue_weak_then_a_usable_face_meets_and_converses",
        # The lean-in cooldown (robot 2026-10-01 15:12-15:17: 15 lean-ins in 5 min under a desk)
        "leanin_weak_cues_every_20_s_for_5_min_nobody_found_at_most_5_lean_ins_and_roams_between",
        "leanin_a_call_during_the_cooldown_is_answered_at_once",
        "leanin_at_most_one_per_30_s_even_after_a_meeting",
        "leanin_never_interrupts_a_seek",
        "leanin_never_interrupts_the_recover_wait",
        "leanin_never_interrupts_an_escape_or_the_ladder",
        # Boxed in: leave the way he came (robot 2026-10-01: minutes under a desk)
        "boxed_in_three_cpl_refusals_in_60_s_leaves_the_way_he_came",
        "boxed_in_a_refused_retrace_falls_back_to_the_escape_ladder",
        "boxed_in_a_full_look_around_finding_nothing_open_leaves_the_way_he_came",
        # Look around when everything in view reads closed (robot 2026-10-01 15:55)
        "look_around_walls_across_the_front_half_turns_to_face_the_open_side_and_drives_there",
        "look_around_stops_early_on_the_first_step_that_reads_open",
        "look_around_nothing_open_with_a_doorway_reported_faces_it_and_takes_a_short_leg",
        "look_around_a_floor_hazard_mid_turn_drops_it_and_the_hazard_rules",
        # The first real seek on the robot (2026-10-01 15:59-16:00)
        "seek_a_doorway_reported_during_the_seek_becomes_its_target_and_he_drives_toward_it",
        "seek_a_none_answer_logs_the_fallback_and_no_second_seek_within_seek_gap_ms",
        "seek_claudes_target_blocked_with_an_open_band_30_deg_left_still_drives_the_target",
        # Openness never vetoes Claude's pick (robot 2026-10-01 16:35: 0.00 on open hallway carpet)
        "seek_claudes_doorway_reading_open_zero_with_clear_floor_drives_to_it_and_arrives",
        "seek_claudes_doorway_reading_blocked_with_a_tof_obstacle_at_1_m_stops_on_the_hazard_then_the_seek_blocked_handling_runs",
        "seek_the_least_familiar_fallback_target_reading_blocked_still_gives_up",
        "call_wake_word_with_no_face_still_opens_with_the_crouch_opener",
        # The caller talks while he looks for them (owner 2026-09-30): the conversation opens at once
        "call_caller_talking_during_the_search_opens_the_conversation_before_the_search_ends",
        "call_a_repeated_wake_word_during_the_search_opens_the_conversation",
        "call_a_reply_after_where_opens_the_conversation_not_another_search",
        "call_wake_word_in_chat_listen_of_a_call_opened_conversation_keeps_it_going",
        "call_a_silent_caller_still_gets_the_full_search_and_where",
        # A slow detector during the call's search (robot 2026-09-30: 1.8-4.7 s a frame)
        "call_detect_2500_ms_a_frame_taken_200_ms_after_the_turn_ends_is_fresh_and_found_with_no_more_looks",
        "call_a_person_in_a_stale_look_20_deg_from_the_stop_is_found",
        "call_detect_4500_ms_a_caller_at_90_left_is_found_on_look_1",
        "call_detect_4500_ms_an_empty_circle_still_reaches_where",
        # A caller seen in a stale look is a target (robot 2026-10-01)
        "call_a_person_in_look_1_captured_37_deg_right_is_faced_and_met_with_no_circle",
        "call_a_person_seen_61_deg_away_during_look_4_is_retargeted_faced_and_met",
        "call_a_seen_caller_gone_from_the_retarget_bearing_resumes_the_planned_looks_and_asks_where",
        "call_the_seen_caller_retarget_cap_holds_against_phantom_boxes",
        "call_caller_talking_while_he_turns_to_a_seen_caller_meets_them_facing",
        # A roaming person is met only with a usable face (owner 2026-10-01)
        "roaming_faceless_person_box_in_3_stopped_looks_is_still_not_met",
        "roaming_person_with_no_face_is_not_met_and_nothing_is_said",
        "roaming_person_with_a_too_small_face_is_not_met_and_nothing_is_said",
        "roaming_person_with_the_face_models_not_ready_is_not_met",
        "roaming_person_whose_face_check_failed_is_not_met",
        "roaming_person_with_a_usable_new_face_is_met_and_the_opener_asks_the_name",
        # A faceless call: the crouch opener, face retries, the name only with a face (robot 2026-10-01)
        "call_faceless_meeting_opener_invites_them_down_and_does_not_ask_the_name",
        "call_faceless_usable_face_on_a_retry_asks_the_name_then_stores_name_and_face",
        "call_faceless_name_given_is_held_and_stored_when_a_face_arrives_on_a_retry",
        "call_faceless_with_no_usable_face_on_any_retry_chats_unnamed_and_discards_the_notes",
        # Conversation first, the caller found during it (owner 2026-10-02)
        "callchat_wake_with_words_opens_with_their_words_as_the_first_message_within_2_s",
        "callchat_bare_wake_opens_at_once_with_a_greeting_question_and_searches_during_it",
        "callchat_the_caller_found_at_look_3_gets_the_face_path_and_the_conversation_goes_on",
        "callchat_never_found_goes_on_while_they_answer_and_ends_after_3_unanswered",
        "callchat_an_answer_with_no_words_gets_one_didnt_catch_that_and_does_not_count",
        "callchat_no_turn_is_commanded_while_he_speaks_or_an_answer_is_in_progress",
        "callchat_an_older_launcher_with_no_words_gives_the_bare_wake_opener",
        "callchat_a_call_that_waits_out_a_back_off_keeps_its_words_and_answers_as_the_back_off_ends",
        "callchat_on_the_charger_talks_without_turning",
        # A call while his turns do nothing (robot 2026-10-01: the motor board latched)
        "call_with_turns_that_never_turn_searches_at_most_twice_then_meets_where_he_is",
        "call_search_waits_for_the_escape_back_up_before_it_restarts",
        # On-device matching in Explore (face plan U6; KTD7, KTD11, R11, R18)
        "replies_lines_carry_the_named_greeting_with_its_placeholder",
        "face_confident_with_a_conversation_enters_chat_known_without_a_lines_request",
        "face_rejected_crop_takes_the_faceless_path_and_keeps_nothing",
        "face_not_ready_is_faceless_and_a_later_name_is_not_stored",
        "face_close_or_weak_band_meets_a_stranger_who_is_stored_under_their_name",
        "face_degraded_known_greeting_comes_from_the_lines_request",
        "face_degraded_known_with_failing_lines_plays_the_local_greeting",
        "face_chat_known_turn_one_failure_plays_the_local_greeting_before_the_sign_off",
        "face_chat_known_turn_one_unreachable_twice_also_greets_before_the_sign_off",
        "face_first_roam_waits_for_migration_or_30_s",
        "face_work_is_allowed_only_while_the_detector_is_quiet_or_parked",
        "face_migration_marks_a_faceless_photo_unusable_and_becomes_ready",
        "face_migration_runs_only_while_the_gate_is_open_and_resumes",
        "face_migration_leaves_a_photo_waiting_when_the_models_fail",
        "face_migration_retries_a_waiting_photo_after_a_backoff",
        "face_migration_settles_without_readiness_when_the_launcher_is_too_old",
        # Confirming a close match and resolving names (face plan U7; KTD6, KTD9, KTD10, KTD12; AE2-AE4, AE7-AE9)
        "confirm_chat_ae2_yes_adds_the_photo_and_starts_known_with_their_notes",
        "confirm_chat_ae3_no_im_sarah_close_to_sarah_joins_her_and_starts_known_as_sarah",
        "confirm_chat_ae7_silence_starts_the_stranger_opener_with_no_photo_and_outcome_no_reply",
        "confirm_chat_ae8_near_tie_asks_the_full_name_and_a_bare_first_name_is_a_no",
        "confirm_chat_no_im_priya_unstored_stores_priya_and_starts_known_without_asking_again",
        "confirm_chat_yes_with_the_photo_refused_starts_as_a_stranger_and_recreates_nobody",
        "confirm_meeting_ending_mid_confirm_closes_the_check_without_an_answer",
        "confirm_ladder_runs_the_confirm_and_last_name_branches_without_a_conversation",
        "confirm_ladder_answer_started_in_time_is_heard_past_the_listen_deadline",
        "confirm_ladder_last_name_unanswered_welcomes_them_and_stores_nobody",
        "resolve_chat_ae4_weak_ben_asks_the_last_name_smith_stores_ben_smith_and_wilson_joins_ben_wilson",
        "resolve_chat_after_the_last_name_the_next_turn_carries_both_replies_and_an_equal_name_given_stores_nothing",
        "resolve_chat_last_name_unanswered_stores_nobody_and_the_conversation_runs_unnamed",
        "resolve_chat_stored_ben_without_a_last_name_and_smith_stores_a_new_ben_smith",
        "resolve_chat_ae9_name_on_turn_three_close_to_sarah_joins_her_and_moves_the_notes",
        "resolve_chat_a_known_conversation_whose_name_given_is_close_to_another_stored_person_joins_them",
        "resolve_chat_a_join_whose_photo_is_refused_continues_and_recreates_nobody",
        # The remark rate (owner 2026-10-01): an observation about every 30 s with nobody about
        "one_slow_look_retries_the_stop_soon_and_curiosity_stays_on",
        "two_slow_looks_in_a_row_turn_curiosity_off_for_30_s",
        "remark_rate_default_tuning_empty_room_at_least_12_remarks_in_10_min",
        "remark_rate_familiar_room_at_least_12_remarks_in_10_min_none_repeated",
        "claude_familiar_pick_with_a_fresh_line_is_said",
        "claude_familiar_pick_with_no_line_or_a_repeated_line_is_as_good_as_nothing",
        "claude_look_request_carries_what_he_reacted_to_and_said_but_no_person_or_name",
        "prompt_lists_reacted_things_and_said_lines_and_asks_for_a_fresh_line",
        # The 2026-10-01 review's findings (P2-1..P2-6 and the P3s)
        "review_lease_lost_in_the_back_up_wait_the_next_short_back_up_still_stops_on_a_stall_and_waits",
        "review_chat_an_answer_that_ends_without_words_is_unanswered_as_it_ends_not_at_the_fallback",
        "review_meet_a_wordless_answer_ends_the_listen_when_the_port_says_silence_not_at_29_s",
        "review_one_cpl_hiccup_episode_counts_once_toward_boxed_in_and_its_retry_still_runs",
        "review_a_hiccup_that_would_be_the_third_refusal_still_gets_its_retry_first",
        "review_a_doorway_forgotten_after_a_hazard_is_not_the_next_seeks_trusted_target",
        "review_clutter_reading_0_25_everywhere_looks_around_at_most_three_times_in_3_min_and_still_drives",
        "review_jammed_a_lease_drop_and_regain_rests_on_and_never_drives_into_the_jam",
        "review_jammed_a_call_met_in_place_goes_back_to_the_jammed_rest_not_roaming",
        "review_a_leg_that_went_nowhere_keeps_the_stuck_spells_recovery_count",
        "review_a_trusted_leg_cut_off_by_a_cue_or_a_lease_drop_leaves_no_trust_behind",
        "review_a_zero_turn_long_after_a_bump_waits_the_full_recovery_from_the_turn",
        "review_a_lean_in_cut_off_by_a_lease_drop_does_not_start_a_cooldown_at_a_later_stop",
        # Docked: quiet on the charger, a remark only for something new (owner 2026-10-02)
        "dock_five_minutes_docked_no_songs_no_startles_no_wheels",
        "dock_a_new_thing_appearing_is_remarked_once",
        "dock_the_same_thing_still_there_is_not_remarked_again",
        "dock_detector_only_a_new_thing_says_its_name_once",
        "dock_a_call_is_answered_and_the_conversation_runs_without_turning",
        "dock_driving_onto_the_charger_is_no_startle_and_he_settles",
        "dock_taken_off_the_charger_he_roams_again",
        "dock_power_with_a_faulted_tof_docks_without_moving_and_remarks_only_on_something_new",
        "dock_power_off_the_dock_never_docks",
        "dock_power_off_for_two_readings_roams_again_and_one_is_not_enough",
        "dock_power_off_the_dock_with_a_faulted_tof_goes_back_to_eyes_only",
        "dock_power_missing_or_malformed_is_ignored",
        "dock_a_fresh_start_on_the_charger_looks_without_having_said_anything",
        "dock_camera_closed_between_looks_and_opened_for_each",
        "dock_reopen_never_sooner_than_the_gap",
        "dock_a_conversation_opens_the_camera_and_it_closes_once_docked_again",
        "dock_camera_closing_off_keeps_it_open_between_looks",
        "muted_no_curiosity_stop_or_remark_and_he_roams_on_then_unmuting_restores_them",
        "muted_a_call_gets_only_a_glance_toward_the_voice_and_unmuting_answers_the_next",
        "muted_a_strong_voice_is_no_lean_in_and_no_turn_toward_it",
        "muted_mid_conversation_it_ends_at_once_with_no_sign_off",
        # Bathroom privacy (owner 2026-10-02)
        "bathroom_a_toilet_while_roaming_privacy_on_beeps_every_5_s_retraces_out_then_privacy_off",
        "bathroom_the_1451_office_chair_read_as_a_toilet_never_triggers",
        "bathroom_a_real_toilet_needs_two_of_three_looks",
        "bathroom_a_single_mirror_or_weak_labels_below_threshold_never_trigger",
        "bathroom_toilet_paper_alone_or_a_tiny_box_never_triggers",
        "bathroom_toilet_paper_and_a_sink_together_still_trigger",
        "bathroom_a_sink_and_soap_within_three_looks_trigger_and_five_looks_apart_do_not",
        "bathroom_privacy_a_due_curiosity_stop_asks_claude_nothing_and_stores_no_place_print",
        "bathroom_privacy_a_call_gets_a_glance_and_no_conversation",
        "bathroom_muted_privacy_stays_on_and_the_beep_stays_silent",
        "bathroom_look_then_go_the_deciding_look_starts_the_escape_not_a_leg_toward_it",
        "bathroom_after_leaving_the_steer_never_heads_back_toward_it",
        "bathroom_jammed_inside_keeps_beeping_and_privacy_and_the_help_path_runs",
        # The navigation log (2026-10-02): leg lines, escape and seek ids, turn why, mode notes
        "navlog_every_hop_ends_with_one_leg_line_of_its_decision_ticks_counts_heading_and_frame",
        "navlog_a_leg_whose_wheels_never_moved_ends_nowhere_and_a_long_one_ends_stall",
        "navlog_a_cpl_hiccup_ends_its_leg_cpl_and_the_retry_leg_ends_cpl_retry_ok_or_cpl",
        "navlog_an_escape_has_an_id_from_start_to_end_and_its_turns_say_why",
        "navlog_a_seek_has_an_id_from_start_to_end_and_its_legs_and_turns_say_seek",
        "navlog_bathroom_privacy_legs_are_logged_without_their_frame",
        "navlog_mode_notes_only_on_a_change_eyes_only_docked_and_roam",
        # The learning log (2026-10-03): trig: and turn: records, the app-private learn.log
        "learn_each_conversation_turn_is_one_turn_record_with_latencies_and_no_words",
        "learn_a_failed_turn_records_its_retry_and_the_failure_end",
        "learn_a_bathroom_trigger_is_one_trig_record_strong_or_two_weak",
        "learn_a_roaming_person_writes_a_person_pick_and_a_meet_trig_record",
        "learn_a_claude_person_pick_conversation_is_marked_claude_pick_faceless_and_its_replies",
        "learn_log_keeps_only_records_as_logcat_lines_says_alive_and_rotates",
        "learn_tuning_stamp_is_a_hash_and_the_fields_off_their_defaults",
        "learn_log_close_with_a_full_queue_stops_the_writer_without_blocking_the_caller",
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
