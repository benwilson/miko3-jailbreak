"""Host tests for the voice evaluation's scripts (owner 2026-10-03).

scripts/voice-eval-report.py (parsing the launcher's "voice eval:" lines, the per-model
summary, the EER estimate and the recommendation), scripts/push-voice-eval-models.py (the
root-shell install, with a fake adb) and build-custom-launcher.py's pinned extra models.
The launcher side (VoiceEval) is proven in test_voice_id's harness.
"""
import importlib.util
import subprocess
import tempfile
import unittest
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPTS = REPO / "scripts"


def _load(name, file):
    spec = importlib.util.spec_from_file_location(name, SCRIPTS / file)
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


report = _load("voice_eval_report", "voice-eval-report.py")
push = _load("push_voice_eval_models", "push-voice-eval-models.py")
build = _load("build_custom_launcher", "build-custom-launcher.py")

LOG = """\
10-04 19:00:01.000 I/ListenEngine( 1246): voice eval: model=campplus best=0.000 second=nan margin=nan ms=820
10-04 19:00:03.000 I/ListenEngine( 1246): voice eval: model=nemo_en_titanet_small best=0.000 second=nan margin=nan ms=1400
10-04 19:00:04.000 I/ListenEngine( 1246): voice eval: truth model=campplus true=0.760 other_best=nan
10-04 19:00:05.000 I/ListenEngine( 1246): voice eval: model=campplus best=0.780 second=0.700 margin=0.080 ms=900
10-04 19:00:06.000 I/ListenEngine( 1246): voice eval: truth model=campplus true=0.700 other_best=0.720
10-04 19:00:06.000 I/ListenEngine( 1246): voice eval: truth model=campplus true=0.750 other_best=0.740
10-04 19:00:06.000 I/ListenEngine( 1246): voice eval: truth model=campplus true=0.800 other_best=0.690
10-04 19:00:06.000 I/ListenEngine( 1246): voice eval: truth model=nemo_en_titanet_small true=0.700 other_best=0.300
10-04 19:00:06.000 I/ListenEngine( 1246): voice eval: truth model=nemo_en_titanet_small true=0.650 other_best=0.350
10-04 19:00:06.000 I/ListenEngine( 1246): voice eval: truth model=nemo_en_titanet_small true=0.720 other_best=0.280
10-04 19:00:07.000 I/ListenEngine( 1246): voice eval: model=nemo_en_titanet_small failed: IllegalStateException
10-04 19:00:08.000 I/ListenEngine( 1246): voice: match band=strong score=0.72 margin=solo
"""


class ReportTest(unittest.TestCase):
    def test_parses_answer_and_truth_lines_per_model(self):
        d = report.parse(LOG)
        self.assertEqual(sorted(d), ["campplus", "nemo_en_titanet_small"])
        self.assertEqual(d["campplus"]["ms"], [820, 900])
        self.assertEqual(d["campplus"]["margin"], [0.08])
        self.assertEqual(d["campplus"]["true"], [0.76, 0.7, 0.75, 0.8])
        self.assertEqual(d["campplus"]["other"], [0.72, 0.74, 0.69])
        self.assertEqual([round(x, 3) for x in d["campplus"]["sep"]], [-0.02, 0.01, 0.11])
        self.assertEqual(d["nemo_en_titanet_small"]["ms"], [1400])

    def test_eer_is_zero_for_separated_scores_and_high_for_overlap(self):
        self.assertEqual(report.eer([0.7, 0.65, 0.72], [0.3, 0.35, 0.28])[0], 0.0)
        e, _ = report.eer([0.7, 0.75, 0.8], [0.72, 0.74, 0.69])
        self.assertGreater(e, 0.2)
        self.assertIsNone(report.eer([0.7, 0.8], [0.1, 0.2, 0.3]))

    def test_summary_and_recommendation_pick_the_separating_model(self):
        rows = report.summarise(report.parse(LOG))
        by = {r["model"]: r for r in rows}
        self.assertAlmostEqual(by["nemo_en_titanet_small"]["sep_mean"], 0.38, places=3)
        self.assertEqual(by["nemo_en_titanet_small"]["sep_positive"], 1.0)
        self.assertEqual(report.recommend(rows), "nemo_en_titanet_small")
        text = report.format_report(rows)
        self.assertIn("recommendation: nemo_en_titanet_small", text)
        self.assertIn("campplus", text)

    def test_not_enough_data_and_no_lines(self):
        rows = report.summarise(report.parse(LOG.splitlines()[0]))
        self.assertIsNone(report.recommend(rows))
        self.assertIn("not enough data", report.format_report(rows))
        self.assertIn("no 'voice eval:' lines", report.format_report([]))

    def test_cli_reads_a_file(self):
        with tempfile.NamedTemporaryFile("w", suffix=".log", delete=False) as f:
            f.write(LOG)
        r = subprocess.run(["python3", str(SCRIPTS / "voice-eval-report.py"), f.name], capture_output=True,
                           text=True)
        Path(f.name).unlink()
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertIn("recommendation: nemo_en_titanet_small", r.stdout)


class FakeAdb:
    def __init__(self, root=True, fail_on=None):
        self.cmds = []
        self.root = root
        self.fail_on = fail_on

    def __call__(self, cmd, timeout=600):
        self.cmds.append(cmd)
        if self.fail_on and self.fail_on in cmd:
            return subprocess.CompletedProcess(cmd, 1, "", "boom")
        out = ("0" if self.root else "2000") if cmd[-2:] == ["id", "-u"] else "ok"
        return subprocess.CompletedProcess(cmd, 0, out, "")


class PushScriptTest(unittest.TestCase):
    def test_install_script_copies_chowns_and_relabels_into_the_launchers_files(self):
        s = push.install_script(["a.onnx", "b.onnx"])
        self.assertIn("uid=$(stat -c %u /data/data/com.miko3.launcher)", s)
        self.assertIn("cp /data/local/tmp/miko3-voiceeval/a.onnx /data/data/com.miko3.launcher/files/voiceeval/"
                      "models/a.onnx", s)
        self.assertIn('chown -R "$uid:$uid" /data/data/com.miko3.launcher/files/voiceeval', s)
        self.assertIn("restorecon -R", s)
        self.assertIn("rm -rf /data/local/tmp/miko3-voiceeval", s)
        self.assertEqual(push.PROP, "debug.miko3.voice_eval")

    def test_push_stages_each_model_then_installs_as_root(self):
        with tempfile.TemporaryDirectory() as td:
            p = Path(td) / "m.onnx"
            p.write_bytes(b"x")
            adb = FakeAdb(root=False)
            self.assertIsNone(push.push("SER", [p], runner=adb))
            self.assertIn(["adb", "-s", "SER", "push", str(p), "/data/local/tmp/miko3-voiceeval/m.onnx"], adb.cmds)
            last = adb.cmds[-1]
            self.assertEqual(last[:8], ["adb", "-s", "SER", "shell", "su", "0", "sh", "-c"])
            adb_root = FakeAdb(root=True)
            push.push(None, [p], runner=adb_root)
            self.assertEqual(adb_root.cmds[-1][:2], ["adb", "shell"])
            self.assertNotIn("su", adb_root.cmds[-1])
            failing = FakeAdb(fail_on="push")
            self.assertIn("push of m.onnx failed", push.push(None, [p], runner=failing))

    def test_restart_and_remove(self):
        cmds = push.restart_cmds(None)
        self.assertEqual(cmds[0], ["adb", "shell", "am", "force-stop", "com.miko3.launcher"])
        self.assertEqual(cmds[1][-1], "com.miko3.launcher/.MainActivity")
        self.assertIn("rm -rf /data/data/com.miko3.launcher/files/voiceeval", push.remove_script())


class BuildPinTest(unittest.TestCase):
    def test_pins_the_two_extra_models_and_never_bundles_them(self):
        self.assertEqual(sorted(build.VOICE_EVAL_MODELS), ["3dspeaker_speech_eres2net_sv_en_voxceleb_16k.onnx",
                                                           "nemo_en_titanet_small.onnx"])
        for sha in build.VOICE_EVAL_MODELS.values():
            self.assertRegex(sha, r"^[0-9a-f]{64}$")
        main = (SCRIPTS / "build-custom-launcher.py").read_text().split("def main():", 1)[1]
        assets = [ln for ln in main.splitlines() if "voiceid_root, stamp_root" in ln]
        self.assertTrue(assets)
        for ln in assets:
            self.assertNotIn("eval", ln)
        # Fetched only on request.
        self.assertIn("if args.voice_eval_models:", main)

    def test_fetches_each_model_with_its_checksum(self):
        seen = []

        def fetch(url, dest, sha):
            seen.append((url, Path(dest).name, sha))
            return dest

        with tempfile.TemporaryDirectory() as td:
            paths = build.voice_eval_models(Path(td), fetch=fetch)
        self.assertEqual(len(paths), 2)
        for url, name, sha in seen:
            self.assertEqual(url, build.VOICE_EVAL_URL + name)
            self.assertEqual(sha, build.VOICE_EVAL_MODELS[name])
        self.assertTrue(build.VOICE_EVAL_URL.endswith("/speaker-recongition-models/"))


if __name__ == "__main__":
    unittest.main()
