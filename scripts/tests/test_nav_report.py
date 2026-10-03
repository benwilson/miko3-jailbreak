"""Tests for scripts/nav-report.py: the explore navigation scorecard built from
logcat captures. Each test feeds a small synthetic log snippet (the brain's own
note strings, as `adb logcat -v time` or `-v epoch` lines) and checks one metric:
line parsing, dedupe, run splitting by pid, time per mode with capture gaps,
leg auto-labels and the openness-versus-outcome table, turn accuracy, RECOVER
outcomes, wedges/boxed-in/jams/help/wriggles, escapes, seeks, coverage and
place memory, remarks and conversations, frame matching, the per-day roll-up,
and the CLI's markdown, JSON and CSV outputs."""
import contextlib
import csv
import datetime as dt
import importlib.util
import io
import json
import os
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE.parent / "nav-report.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


nav = load("nav_report", SCRIPT)

BASE = dt.datetime(2026, 10, 2, 9, 0, 0)


def L(sec, msg, pid=2230, tag="ExploreBrain", level="I"):
    """One `-v time` line, sec seconds after 09:00:00 on 10-02."""
    t = BASE + dt.timedelta(seconds=sec)
    return f"{t:%m-%d %H:%M:%S}.{t.microsecond // 1000:03d} {level}/{tag}({pid:5d}): {msg}"


def roaming(*lines, pid=2230):
    """A run that starts roaming at t=0 (sensors available), then the given lines."""
    return [L(0, "eyes only: no readings yet", pid), L(0.5, "sensors available and lease held", pid)] + list(lines)


def analyze(lines, gap_s=120.0):
    parsed = nav.parse_lines(lines, year=2026)
    runs = nav.split_runs(parsed)
    gaps = nav.capture_gaps(parsed, gap_s)
    return [nav.analyze_run(r, gaps) for r in runs]


def one(lines, **kw):
    runs = analyze(lines, **kw)
    assert len(runs) == 1, len(runs)
    return runs[0]


class ParseTest(unittest.TestCase):
    def test_time_format(self):
        p = nav.parse_line("10-02 09:08:09.810 I/ExploreBrain( 2230): steer: left 60 deg, open 0.13, turn only",
                           year=2026)
        self.assertEqual(p.ts, dt.datetime(2026, 10, 2, 9, 8, 9, 810000))
        self.assertEqual((p.pid, p.tag, p.level), (2230, "ExploreBrain", "I"))
        self.assertEqual(p.msg, "steer: left 60 deg, open 0.13, turn only")

    def test_epoch_format_is_local_time(self):
        # 1790887142.327 is 13:39:02.327 PDT on 2026-10-01, the same line day2.log has as -v time.
        p = nav.parse_line("         1790887142.327 25176 25250 I ExploreBrain: eyes only: no readings yet",
                           year=2026, tz="America/Los_Angeles")
        self.assertEqual(p.ts, dt.datetime(2026, 10, 1, 13, 39, 2, 327000))
        self.assertEqual((p.pid, p.tag, p.msg), (25176, "ExploreBrain", "eyes only: no readings yet"))

    def test_junk_lines_are_none(self):
        self.assertIsNone(nav.parse_line("--------- beginning of main", year=2026))
        self.assertIsNone(nav.parse_line("", year=2026))

    def test_duplicates_dropped_and_sorted(self):
        a, b = L(2, "coverage: 2 cells"), L(1, "coverage: 1 cells")
        parsed = nav.parse_lines([a, b, a, "--------- beginning of main", b], year=2026)
        self.assertEqual([p.msg for p in parsed], ["coverage: 1 cells", "coverage: 2 cells"])


class RunSplitTest(unittest.TestCase):
    def test_split_by_pid_ignoring_other_processes(self):
        lines = roaming(L(5, "coverage: 1 cells")) + [
            L(6, "ears: something", pid=900, tag="ListenEngine"),
            L(10, "eyes only: no readings yet", pid=12406),
            L(11, "coverage: 1 cells", pid=12406),
        ]
        runs = nav.split_runs(nav.parse_lines(lines, year=2026))
        self.assertEqual([r.pid for r in runs], [2230, 12406])
        self.assertEqual(len(runs[0].lines), 3)

    def test_pid_reuse_after_long_silence_is_a_new_run(self):
        lines = [L(0, "eyes only: no readings yet"), L(4000, "eyes only: no readings yet")]
        runs = nav.split_runs(nav.parse_lines(lines, year=2026))
        self.assertEqual(len(runs), 2)


class TimeTest(unittest.TestCase):
    def test_modes(self):
        r = one([
            L(0, "eyes only: no readings yet"),
            L(10, "sensors available and lease held"),          # roaming 10..70
            L(40, "coverage: 3 cells"),
            L(70, "power: on the charger (POWER says docked)"),  # docked 70..130
            L(71, "docked: on the charger; sitting still and quiet, a look every 60000 ms"),
            L(100, "eyes only: sensors unavailable: tof at fault value 16383"),  # still docked
            L(130, "docked: off the charger; roaming again"),    # roaming 130..160
            L(160, "eyes only: sensors unavailable: stale: no reading for 314 ms"),  # eyes 160..170
            L(170, "sensors available and lease held"),
            L(175, "coverage: 4 cells"),
        ])
        t = r["time"]
        self.assertAlmostEqual(t["roaming_s"], 60 + 30 + 5, places=3)
        self.assertAlmostEqual(t["docked_s"], 60, places=3)
        self.assertAlmostEqual(t["eyes_only_s"], 10 + 10, places=3)
        self.assertAlmostEqual(t["gap_s"], 0, places=3)

    def test_motion_means_roaming_without_the_sensors_note(self):
        # Robot 2026-10-02 10:37: off the charger, a conversation, then he drives with no
        # "sensors available" note (he left EYES_ONLY through the conversation).
        r = one([
            L(0, "eyes only: sensors unavailable: tof at fault value 16383"),
            L(10, "power: off the charger for 2 readings"),
            L(20, "first leg after the conversation: turning RIGHT, away from them"),
            L(25, "turn start: heading 141 deg, asking 120 deg RIGHT"),
            L(30, "measured turn: asked 120 deg, turned 118 deg, overshoot 2 deg"),
        ])
        self.assertAlmostEqual(r["time"]["eyes_only_s"], 20, places=3)
        self.assertAlmostEqual(r["time"]["roaming_s"], 10, places=3)

    def test_capture_gap_excluded(self):
        r = one(roaming(L(30, "coverage: 1 cells"), L(30 + 194, "coverage: 2 cells"), L(250, "coverage: 3 cells")))
        self.assertAlmostEqual(r["time"]["roaming_s"], 29.5 + 26, places=3)
        self.assertAlmostEqual(r["time"]["gap_s"], 194, places=3)

    def test_other_process_lines_bridge_a_quiet_brain(self):
        # A docked brain is quiet for minutes but the capture was alive: not a gap.
        lines = [L(0, "power: on the charger (POWER says docked)")]
        lines += [L(s, "tick", pid=900, tag="ListenEngine") for s in range(60, 300, 60)]
        lines += [L(300, "docked: sitting still again")]
        r = one(lines)
        self.assertAlmostEqual(r["time"]["docked_s"], 300, places=3)
        self.assertAlmostEqual(r["time"]["gap_s"], 0, places=3)


class LegTest(unittest.TestCase):
    def legs(self, *lines):
        return one(roaming(*lines))["legs"]

    def test_clean_leg_to_next_decision(self):
        legs = self.legs(L(10, "steer: left 16 deg, open 0.50, leg x0.42, new 1.00"),
                         L(11, "measured turn: asked 16 deg, turned 16 deg, overshoot 10 deg"),
                         L(15, "steer: straight 0 deg, open 0.80, leg x1.00, new 0.20"))
        self.assertEqual(legs[0]["outcome"], "clean")
        self.assertEqual(legs[0]["open"], 0.50)
        self.assertEqual(legs[0]["band"], "left 16")
        self.assertEqual(legs[0]["kind"], "leg")
        self.assertEqual(legs[1]["outcome"], "unknown")  # the run ends before anything decides it

    def test_turn_only_is_not_a_leg(self):
        legs = self.legs(L(10, "steer: left 60 deg, open 0.13, turn only, new 1.00"),
                         L(15, "steer: straight 0 deg, open 0.19, short leg, new 0.50"),
                         L(18, "curiosity stop: scanning"))
        self.assertEqual([(g["kind"], g["outcome"]) for g in legs], [("short", "clean")])

    def test_look_around_best_turns_turn_only_into_short_leg(self):
        legs = self.legs(L(10, "steer: left 60 deg, open 0.13, turn only, new 1.00"),
                         L(10, "facing the look-around's best: a short leg"),
                         L(12, "hazard while HOP: OBSTACLE"))
        self.assertEqual([(g["band"], g["kind"], g["outcome"]) for g in legs], [("straight 0", "short", "obstacle")])

    def test_outcomes(self):
        cases = [
            ([L(12, "controller refused forward (CPL) on plain floor: a hiccup; 1 ticks to go, trying once more in 400 ms"),
              L(13, "hazard while HOP: CPL")], "cpl"),
            ([L(12, "controller refused forward (CPL) on plain floor: a hiccup; 9 ticks to go, trying once more in 400 ms"),
              L(16, "steer: right 10 deg, open 0.60, leg x0.50")], "cpl_hiccup"),
            ([L(12, "wheels stalled while driving: blocked by something low (1 in a row)")], "stall"),
            ([L(12, "hazard while HOP: OBSTACLE"), L(12, "collision stop: a bump")], "obstacle"),
            ([L(12, "camera reads the way ahead blocked: ending the leg early")], "camera_cut"),
            ([L(11, "hazard while TURN: OBSTACLE")], "turn_hazard"),
            ([L(11, "measured turn blocked: turned 0 of 70 deg in 1512 ms")], "turn_blocked"),
            ([L(11, "hazard at start: OBSTACLE")], "blocked_at_start"),
            ([L(60, "steer: right 10 deg, open 0.60, leg x0.50")], "unknown"),  # past the leg window
        ]
        for tail, want in cases:
            with self.subTest(want=want):
                legs = self.legs(L(10, "steer: left 16 deg, open 0.30, leg x0.42"), *tail)
                self.assertEqual(legs[0]["outcome"], want)

    def test_trusted_doorway_short_leg(self):
        legs = self.legs(L(10, "the doorway at 229 deg reads blocked (open 0.10); trusting Claude: a short leg toward it"),
                         L(12, "wheels stalled while driving: blocked by something low (1 in a row)"))
        self.assertEqual((legs[0]["band"], legs[0]["open"], legs[0]["outcome"]), ("doorway", 0.10, "stall"))

    def test_capture_gap_makes_unknown(self):
        legs = self.legs(L(10, "steer: left 16 deg, open 0.50, leg x0.42"),
                         L(11, "coverage: 1 cells"), L(11 + 200, "hazard while HOP: CPL"))
        self.assertEqual(legs[0]["outcome"], "unknown")

    def test_open_buckets(self):
        r = one(roaming(
            L(10, "steer: left 16 deg, open 0.05, leg x0.42"), L(11, "hazard while HOP: CPL"),
            L(20, "steer: left 16 deg, open 0.08, leg x0.42"), L(30, "steer: left 16 deg, open 0.90, leg x1.00"),
            L(40, "steer: left 16 deg, open 0.95, leg x1.00"),
            L(41, "wheels stalled while driving: blocked by something low (1 in a row)")))
        table = {row["bucket"]: row for row in r["openness"]}
        self.assertEqual((table["0.00-0.10"]["legs"], table["0.00-0.10"]["bad"]), (2, 1))
        self.assertEqual(table["0.20-0.35"]["legs"], 0)
        self.assertEqual((table["0.70-1.00"]["legs"], table["0.70-1.00"]["bad"]), (2, 1))
        self.assertEqual(table["0.70-1.00"]["outcomes"], {"clean": 1, "stall": 1})
        self.assertAlmostEqual(table["0.00-0.10"]["bad_rate"], 0.5)


class TurnTest(unittest.TestCase):
    def test_bias_spread_blocked(self):
        r = one(roaming(
            L(10, "turn start: heading 0 deg, asking 60 deg LEFT"),
            L(11, "measured turn: asked 60 deg, turned 56 deg, overshoot 9 deg"),
            L(12, "turn start: heading 0 deg, asking 60 deg LEFT"),
            L(13, "measured turn: asked 60 deg, turned 64 deg, overshoot 9 deg"),
            L(14, "turn start: heading 0 deg, asking 20 deg RIGHT"),
            L(15, "measured turn: asked 20 deg, turned 20 deg, overshoot 9 deg"),
            L(16, "turn start: heading 0 deg, asking 70 deg RIGHT"),
            L(17, "measured turn blocked: turned 0 of 70 deg in 1512 ms"),
            L(17.1, "measured turn: asked 70 deg, turned 0 deg, overshoot 2 deg"),
            L(18, "turn start: heading 0 deg, asking 120 deg RIGHT"),
            L(18.5, "hazard while TURN: OBSTACLE"),
            L(19, "measured turn: asked 120 deg, turned 58 deg, overshoot 6 deg")))
        t = r["turns"]
        self.assertEqual((t["measured"], t["clean"], t["blocked"], t["interrupted"]), (5, 3, 1, 1))
        self.assertAlmostEqual(t["bias_deg"], 0.0)
        self.assertAlmostEqual(t["spread_deg"], 4.0)  # population stdev of -4, +4, 0 is 3.27; sample is 4
        self.assertAlmostEqual(t["blocked_rate"], 1 / 5)


class TroubleTest(unittest.TestCase):
    def test_hazards_cpl_stalls(self):
        r = one(roaming(L(10, "hazard while HOP: CPL"), L(11, "hazard while TURN: OBSTACLE"),
                        L(12, "hazard at start: OBSTACLE"),
                        L(13, "controller refused forward (CPL) on plain floor: a hiccup; 1 ticks to go, trying once more in 400 ms"),
                        L(14, "wheels stalled while driving: blocked by something low (1 in a row)"),
                        L(15, "wheels stalled backing up"),
                        L(16, "wheels stalled driving in the escape's DRIVE_OFF after 30 counts")))
        c = r["counts"]
        self.assertEqual(c["hazards"], {"CPL": 1, "OBSTACLE": 2})
        self.assertEqual(c["hazards_by_state"], {"HOP": 1, "TURN": 1, "START": 1})
        self.assertEqual(c["cpl_refusals"], 1)
        self.assertEqual(c["stalls"], {"driving": 1, "backing": 1, "escape": 1})

    def test_recover_outcomes(self):
        r = one(roaming(
            L(10, "stall: waiting for the motor board to recover (probes at 2, 5, 10, 20 s) (recovery 1 of 3)"),
            L(12, "recover probe at 2 s: nothing"),
            L(15, "recover probe at 5 s: moved 788 counts backing up: the board is back"),
            L(30, "stall: waiting for the motor board to recover (probes at 2, 5, 10, 20 s)"),
            L(32, "recover probe at 2 s: moved 300 counts (turned 20 deg): the board is back"),
            L(40, "stall: waiting for the motor board to recover (probes at 2, 5, 10, 20 s)"),
            L(60, "recover probe at 20 s: nothing"),
            L(60, "no recovery after 20 s: a real jam")))
        self.assertEqual(r["counts"]["recover"], {"waits": 3, "outcomes": {"5s backing up": 1, "2s turn": 1, "none": 1}})

    def test_wedges_boxed_jams_help_wriggles(self):
        r = one(roaming(
            L(10, "wedged: a turn that would not turn (2 hazards, 1 stalls, 0 failed escapes in a row); escaping"),
            L(11, "wedged: the doorway at 25 deg is forgotten"),
            L(12, "wedged: the back-up went nowhere (0 hazards, 0 stalls, 1 failed escapes in a row); escaping"),
            L(13, "boxed in: 3 forward refusals in 60 s; leaving the way he came (facing 120 deg)"),
            L(14, "boxed in: the steer read open 0.10 or less for 6 decisions in a row; leaving the way he came (facing 10 deg)"),
            L(15, "boxed in: out; the way back under (120 deg) avoided for 60 s"),
            L(16, "fully jammed: the back-up went nowhere and turns both ways turned under 10 deg; no more pushing (jam 1 since start)"),
            L(17, "wriggle LEFT: up to 10000 ms"),
            L(18, "wriggle LEFT: wheels not moving; stopping"),
            L(18.1, "wriggle RIGHT: up to 10000 ms"),
            L(19, "wriggle RIGHT: wheels not moving; stopping"),
            L(19.1, "wriggle failed both ways: asking for help"),
            L(19.2, 'asking for help: "I\'m stuck under here. Could someone pull me out?"'),
            L(40, "wriggle LEFT: up to 10000 ms"),
            L(42, "wriggle LEFT: free after 2000 ms (turned 30 deg, 200 counts)"),
            L(42.1, "wriggled free: roaming again")))
        c = r["counts"]
        self.assertEqual(c["wedges"], {"a turn that would not turn": 1, "the back-up went nowhere": 1})
        self.assertEqual(c["boxed_in"], {"N forward refusals in N s": 1,
                                         "the steer read open N or less for N decisions in a row": 1})
        self.assertEqual((c["jams"], c["help_calls"]), (1, 1))
        self.assertEqual(c["wriggles"], {"episodes": 2, "ways": 3, "freed": 1, "failed": 1})

    def test_escapes(self):
        r = one(roaming(
            L(10, "wedged: hazards in a row (3 hazards, 0 stalls, 0 failed escapes in a row); escaping"),
            L(14, "free after 4000 ms: drove off"),
            L(20, "wedged: a turn that would not turn (1 hazards, 0 stalls, 0 failed escapes in a row); escaping"),
            L(26, "free after 6000 ms: a short leg forward drove cleanly"),
            L(30, "fully jammed: the back-up went nowhere and turns both ways turned under 10 deg; no more pushing (jam 1 since start)"),
            L(31, "wriggle failed both ways: asking for help"),
            L(80, "jam probe moved 400 counts: free, roaming again"),
            L(90, "wedged: the back-up went nowhere (0 hazards, 0 stalls, 1 failed escapes in a row); escaping")))
        e = r["escapes"]
        self.assertEqual((e["episodes"], e["freed"], e["needed_help"], e["unresolved"]), (4, 3, 1, 1))
        self.assertEqual(e["free_ms"], [4000, 6000, 50000])
        self.assertEqual(e["how"], {"drove off": 1, "a short leg forward drove cleanly": 1, "jam probe": 1})


class SeekTest(unittest.TestCase):
    def test_seeks(self):
        r = one(roaming(
            L(10, "seeking: asking Claude where to go (3 frames)"),
            L(13, "seeking: Claude picked frame 2 x 0.40: heading 120 deg (bearing 30)"),
            L(40, "seeking: arrived (the place looks new, novelty 0.80)"),
            L(100, "seeking: heading for the doorway at 250 deg"),
            L(160, "seeking: gave up (blocked: wheels stalled)"),
            L(200, "seeking: asking Claude where to go (3 frames)"),
            L(220, "seeking: gave up (12 legs without arriving)")))
        s = r["seeks"]
        self.assertEqual(s["count"], 3)
        self.assertEqual(s["arrived"], {"the place looks new, novelty N": 1})
        self.assertEqual(s["gave_up"], {"blocked: wheels stalled": 1, "N legs without arriving": 1})
        self.assertEqual(s["durations_s"], [30.0, 60.0, 20.0])


class SocialTest(unittest.TestCase):
    def test_coverage_place_camera(self):
        r = one(roaming(
            L(1, "look in 300 ms: []", tag="ExploreCamera"), L(2, "look in 300 ms: []", tag="ExploreCamera"),
            L(3, "look in 300 ms: []", tag="ExploreCamera"), L(4, "look in 300 ms: []", tag="ExploreCamera"),
            L(5, "coverage: 7 cells"), L(6, "coverage: 12 cells"),
            L(7, "place: seen before (sim 0.95, 1 min ago)")))
        n = r["novelty"]
        self.assertEqual((n["coverage_cells"], n["place_hits"], n["camera_looks"]), (12, 1, 4))
        self.assertAlmostEqual(n["place_hit_rate"], 0.25)

    def test_no_camera(self):
        r = one(roaming(L(5, "coverage: 2 cells")))
        self.assertEqual(r["novelty"]["camera_looks"], 0)
        self.assertIsNone(r["novelty"]["place_hit_rate"])
        self.assertFalse(r["camera"])

    def test_remarks_calls_conversations(self):
        r = one(roaming(
            L(10, "curiosity stop: scanning"), L(20, "remarks in the last 10 min: 1"),
            L(30, "curiosity stop: scanning"), L(40, "remarks in the last 10 min: 2"),
            L(50, "a call from LEFT in HOP"), L(51, "answering the call"),
            L(60, "a conversation with someone unnamed"),
            L(90, "conversation over after 3 turn(s), 0 note delta(s) kept"),
            L(3600.5, "coverage: 1 cells")), gap_s=10000)
        s = r["social"]
        self.assertEqual((s["curiosity_stops"], s["remarks"], s["calls"], s["calls_answered"]), (2, 2, 1, 1))
        self.assertEqual((s["conversations"], s["conversation_turns"]), (1, 3))
        self.assertAlmostEqual(r["rates"]["remarks"], 2.0, places=3)  # exactly 1 h roaming


class FrameTest(unittest.TestCase):
    def test_nearest_frame_before(self):
        with tempfile.TemporaryDirectory() as d:
            # 2026-10-01 13:39:03.553 PDT
            for ms in (1790887143553, 1790887145733):
                Path(d, f"frame-{ms}.jpg").write_bytes(b"")
            frames = nav.load_frames([d], tz="America/Los_Angeles")
            self.assertEqual(frames[0][0], dt.datetime(2026, 10, 1, 13, 39, 3, 553000))
            at = dt.datetime(2026, 10, 1, 13, 39, 5)
            self.assertTrue(nav.nearest_frame(frames, at).endswith("frame-1790887143553.jpg"))
            self.assertIsNone(nav.nearest_frame(frames, at + dt.timedelta(seconds=30)))


def LEG(lid, src="steer", side="R", bend=23, open_="0.54", best="0.71", conf="0.80", nov="0.36", look="-",
        age="-", plan=13, sent=12, l=910, r=930, ms=3100, hdg="229>231", end="done", tof=210, cpl=0, hic=0):
    """One `leg:` record as the brain writes it (2026-10-02)."""
    return (f"leg: id={lid} src={src} side={side} bend={bend} open={open_} best={best} conf={conf} nov={nov} "
            f"look={look} lookAge={age} plan={plan}t sent={sent}t L={l} R={r} ms={ms} hdg={hdg} end={end} "
            f"tofMin={tof} cpl={cpl} hiccups={hic}")


class ExactLegTest(unittest.TestCase):
    def test_leg_records_replace_the_inferred_legs(self):
        r = one(roaming(
            L(10, "steer: right 23 deg, open 0.54, leg x0.80, new 0.36"),
            L(14, LEG(1)),
            L(20, "steer: left 10 deg, open 0.20, short leg"),
            L(22, LEG(2, side="L", bend=10, open_="0.20", end="obstacle", l=300, r=290, ms=900)),
            L(30, LEG(3, src="blind", side="S", bend=0, open_="-", best="-", conf="-", nov="-", end="nowhere",
                      l=0, r=0, ms=700))))
        legs = r["legs"]
        self.assertEqual([g["id"] for g in legs], [1, 2, 3])
        g = legs[0]
        self.assertEqual((g["src"], g["band"], g["open"], g["best"], g["conf"], g["novelty"]),
                         ("steer", "R 23", 0.54, 0.71, 0.80, 0.36))
        self.assertEqual((g["plan"], g["sent"], g["L"], g["R"], g["ms"], g["hdg"], g["tof_min"], g["end"]),
                         (13, 12, 910, 930, 3100, (229, 231), 210, "done"))
        self.assertEqual(g["outcome"], "clean")
        self.assertEqual(g["time"], BASE + dt.timedelta(seconds=14) - dt.timedelta(milliseconds=3100))
        self.assertAlmostEqual(g["dist_m"], 920 / 3000)
        self.assertEqual([x["outcome"] for x in legs], ["clean", "obstacle", "nowhere"])
        self.assertIsNone(legs[2]["open"])
        self.assertTrue(r["exact_legs"])

    def test_end_reasons_map_to_outcomes(self):
        ends = [("done", 0, 0, "clean"), ("reaim", 0, 0, "clean"), ("cpl_retry_ok", 0, 0, "clean"),
                ("camera", 0, 0, "camera_cut"), ("cpl", 0, 1, "cpl_hiccup"), ("cpl", 1, 0, "cpl"),
                ("obstacle", 0, 0, "obstacle"), ("edge", 0, 0, "edge"), ("stall", 0, 0, "stall"),
                ("nowhere", 0, 0, "nowhere"), ("eyes", 0, 0, "sensor_drop"), ("cue", 0, 0, "interrupted"),
                ("person", 0, 0, "interrupted"), ("bath", 0, 0, "interrupted")]
        for end, cpl, hic, want in ends:
            with self.subTest(end=end, cpl=cpl):
                r = one(roaming(L(14, LEG(1, end=end, cpl=cpl, hic=hic))))
                self.assertEqual(r["legs"][0]["outcome"], want)

    def test_frame_matched_exactly_by_the_looks_wall_ms_and_never_while_private(self):
        with tempfile.TemporaryDirectory() as d:
            # 2026-10-02 09:00:10.000 PDT is epoch 1790956810000.
            for ms in (1790956809900, 1790956810000, 1790956810150):
                Path(d, f"frame-{ms}.jpg").write_bytes(b"")
            frames = nav.load_frames([d], tz="America/Los_Angeles")
            parsed = nav.parse_lines(roaming(
                L(14, LEG(1, look="1790956810000", age="180")),
                L(20, LEG(2, look="-", age="-")),
                L(30, LEG(3, look="1790956810152", age="180")),
                L(40, LEG(4, look="1790956899999", age="180"))), year=2026)
            r = nav.analyze_run(nav.split_runs(parsed)[0], [], frames)
            legs = r["legs"]
            self.assertTrue(legs[0]["frame"].endswith("frame-1790956810000.jpg"))
            self.assertEqual((legs[0]["look_ms"], legs[0]["look_age_ms"], legs[0]["frame_exact"]),
                             (1790956810000, 180, True))
            self.assertIsNone(legs[1]["frame"])  # privacy: look=- references no frame, whatever is near
            self.assertIsNone(legs[1]["look_ms"])
            self.assertTrue(legs[2]["frame"].endswith("frame-1790956810150.jpg"))  # a few ms of clock offset
            self.assertFalse(legs[2]["frame_exact"])
            self.assertIsNone(legs[3]["frame"])  # no frame within the tolerance

    def test_distance_metrics(self):
        r = one(roaming(
            L(10, LEG(1, open_="0.80", l=3000, r=3000, end="done")),
            L(20, LEG(2, open_="0.80", l=1500, r=1500, end="obstacle")),
            L(30, LEG(3, open_="0.20", l=600, r=600, end="cpl", cpl=0, hic=1)),
            L(31, LEG(4, src="retry", open_="-", l=900, r=900, end="cpl_retry_ok")),
            L(40, LEG(5, open_="0.20", l=300, r=300, end="cpl", hic=1)),
            L(41, LEG(6, src="retry", open_="-", l=0, r=0, end="cpl", cpl=1)),
            L(50, LEG(7, open_="0.20", l=0, r=0, end="nowhere"))))
        d = r["distance"]
        self.assertAlmostEqual(d["metres"], (3000 + 1500 + 600 + 900 + 300) / 3000)
        self.assertEqual(d["legs"], 7)
        self.assertEqual(d["hazards"], 2)  # the obstacle and the retry's CPL; a hiccup is no hazard
        self.assertAlmostEqual(d["hazards_per_m"], 2 / 2.1)
        self.assertAlmostEqual(d["nowhere_rate"], 1 / 7)
        self.assertEqual((d["cpl_retries"], d["cpl_retries_ok"]), (2, 1))
        self.assertAlmostEqual(d["cpl_retry_success"], 0.5)
        cal = {row["bucket"]: row for row in d["open_calibration"]}
        self.assertEqual((cal["0.70-1.00"]["legs"], cal["0.70-1.00"]["bad"]), (2, 1))
        self.assertAlmostEqual(cal["0.70-1.00"]["metres"], 1.5)
        self.assertAlmostEqual(cal["0.70-1.00"]["bad_per_m"], 1 / 1.5)
        self.assertAlmostEqual(cal["0.70-1.00"]["m_per_leg"], 0.75)
        self.assertEqual((cal["0.10-0.20"]["legs"], cal["0.20-0.35"]["legs"]), (0, 3))
        self.assertEqual(cal["0.20-0.35"]["bad"], 1)  # the nowhere leg; hiccups are not bad
        self.assertAlmostEqual(cal["0.20-0.35"]["metres"], 0.3)

    def test_old_logs_have_no_distance(self):
        r = one(roaming(L(10, "steer: left 16 deg, open 0.50, leg x0.42"), L(12, "hazard while HOP: CPL")))
        self.assertFalse(r["exact_legs"])
        self.assertEqual(r["legs"][0]["outcome"], "cpl")
        self.assertIsNone(r["distance"]["metres"])
        self.assertIsNone(r["distance"]["hazards_per_m"])

    def test_escape_and_seek_ids(self):
        r = one(roaming(
            L(10, "wedged: hazards in a row (3 hazards, 0 stalls, 0 failed escapes in a row); escaping"),
            L(10, "escape#1 start trigger=wedged"),
            L(11, "asking for help: stuck"),
            L(14, "free after 4000 ms: drove off"),
            L(14, "escape#1 end outcome=freed ms=4000"),
            L(15, "steer: straight 0 deg, open 0.80, leg x1.00"),
            L(20, "stall: waiting for the motor board"),
            L(20, "escape#2 start trigger=stall"),
            L(29, "escape#2 end outcome=resumed ms=9000"),
            L(40, "escape#3 start trigger=boxed"),
            L(45, "escape#3 end outcome=jam ms=5000"),
            L(50, "seeking: surroundings familiar (2 stops in a row, every look's novelty 0.30 or less)"),
            L(50, "seek#1 start trigger=familiar"),
            L(51, "seeking: asking Claude where to go (3 frames)"),
            L(70, "seeking: arrived (the place looks new, novelty 0.80)"),
            L(70, "seek#1 end outcome=arrived ms=20000"),
            L(90, "seek#2 start trigger=timer"),
            L(95, "seeking: no least familiar frame with anything open; no seek for 3 min"),
            L(95, "seek#2 end outcome=gave_up ms=5000")))
        e = r["escapes"]
        self.assertEqual((e["episodes"], e["freed"], e["needed_help"], e["unresolved"]), (3, 1, 1, 0))
        self.assertEqual(e["by_trigger"], {"wedged": 1, "stall": 1, "boxed": 1})
        self.assertEqual(e["by_outcome"], {"freed": 1, "resumed": 1, "jam": 1})
        self.assertEqual(e["free_ms"], [4000])
        self.assertEqual(e["how"], {"drove off": 1})
        s = r["seeks"]
        self.assertEqual(s["count"], 2)
        self.assertEqual(s["arrived"], {"the place looks new, novelty N": 1})
        self.assertEqual(s["durations_s"], [20.0, 5.0])
        self.assertEqual(s["by_trigger"], {"familiar": 1, "timer": 1})

    def test_mode_notes_split_the_time(self):
        r = one([L(0, "mode: EYES_ONLY"), L(10, "mode: ROAM"), L(70, "mode: ESCAPE"), L(80, "mode: ROAM"),
                 L(100, "mode: CHAT"), L(130, "mode: DOCKED"), L(200, "coverage: 1 cells")])
        self.assertEqual(r["time"]["modes_s"], {"EYES_ONLY": 10.0, "ROAM": 80.0, "ESCAPE": 10.0, "CHAT": 30.0,
                                                "DOCKED": 70.0})
        self.assertAlmostEqual(r["time"]["roaming_s"], 120.0)
        self.assertAlmostEqual(r["time"]["docked_s"], 70.0)
        self.assertAlmostEqual(r["time"]["eyes_only_s"], 10.0)

    def test_turns_by_why(self):
        r = one(roaming(
            L(10, "measured turn: asked 20 deg, turned 24 deg, overshoot 3 deg why=roam"),
            L(20, "measured turn: asked 60 deg, turned 50 deg, overshoot 3 deg why=escape"),
            L(30, "measured turn: asked 40 deg, turned 42 deg, overshoot 3 deg why=scan"),
            L(40, "measured turn: asked 40 deg, turned 40 deg, overshoot 3 deg")))
        tu = r["turns"]
        self.assertEqual(tu["measured"], 4)
        self.assertEqual(tu["by_why"], {"roam": 1, "escape": 1, "scan": 1, "unknown": 1})
        self.assertEqual(tu["bias_by_why_deg"], {"roam": 4.0, "escape": -10.0, "scan": 2.0, "unknown": 0.0})

    def test_markdown_has_the_distance_section(self):
        parsed = nav.parse_lines(roaming(L(10, LEG(1, l=3000, r=3000)), L(20, LEG(2, end="obstacle"))), year=2026)
        md = nav.render_markdown(nav.build_report(parsed), ["x.log"])
        self.assertIn("### Per metre (exact leg records)", md)
        self.assertIn("hazards per metre", md)


class RollupAndCliTest(unittest.TestCase):
    LOG = roaming(
        L(10, "steer: left 16 deg, open 0.50, leg x0.42"), L(12, "hazard while HOP: CPL"),
        L(20, "steer: straight 0 deg, open 0.80, leg x1.00"), L(30, "coverage: 3 cells"),
        L(1800, "coverage: 4 cells")) + [
        L(1900, "eyes only: no readings yet", pid=12406), L(1901, "sensors available and lease held", pid=12406),
        L(1910, "hazard while HOP: OBSTACLE", pid=12406), L(3700, "coverage: 1 cells", pid=12406)]

    def test_day_rollup(self):
        parsed = nav.parse_lines(self.LOG, year=2026)
        report = nav.build_report(parsed, gap_s=4000)
        self.assertEqual(len(report["runs"]), 2)
        day = report["days"][0]
        self.assertEqual(day["date"], "2026-10-02")
        self.assertAlmostEqual(day["time"]["roaming_s"], 1799.5 + 1799, places=3)
        self.assertEqual(day["counts"]["hazards"], {"CPL": 1, "OBSTACLE": 1})
        self.assertAlmostEqual(day["rates"]["hazards"], 2 / ((1799.5 + 1799) / 3600), places=3)
        self.assertEqual(sum(r["legs"] for r in day["openness"]), 1)
        self.assertEqual(sum(r["unknown"] for r in day["openness"]), 1)

    def test_cli_outputs(self):
        with tempfile.TemporaryDirectory() as d:
            log = Path(d, "day.log")
            log.write_text("\n".join(self.LOG) + "\n")
            md, js, lab = Path(d, "r.md"), Path(d, "r.json"), Path(d, "labels.csv")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                rc = nav.main([str(log), "--year", "2026", "-o", str(md), "--json", str(js), "--labels", str(lab),
                               "--frames-dir", d])
            self.assertEqual(rc, 0)
            text = md.read_text()
            self.assertIn("Openness versus outcome", text)
            self.assertIn("2026-10-02", text)
            data = json.loads(js.read_text())
            self.assertEqual(len(data["runs"]), 2)
            rows = list(csv.DictReader(lab.open()))
            self.assertEqual([r["outcome"] for r in rows], ["cpl", "unknown"])
            self.assertEqual(set(rows[0]), {"time", "run", "open", "band", "kind", "novelty", "outcome", "frame",
                                            "id", "src", "end", "dist_m", "look_ms"})

    def test_json_to_stdout(self):
        with tempfile.TemporaryDirectory() as d:
            log = Path(d, "day.log")
            log.write_text("\n".join(self.LOG) + "\n")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                nav.main([str(log), "--year", "2026", "--json", "-"])
            self.assertIn("days", json.loads(out.getvalue()))


if __name__ == "__main__":
    unittest.main()
