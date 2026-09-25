#!/usr/bin/env python3
"""Tests for scripts/voice/train-piper.py (robot voice plan U4, R5, R7, KTD2, KTD8).

Covers the pure logic: reading the growing LJSpeech-style metadata.csv into
Piper's `id.wav|text` CSV, cleaning the old checkpoint's hyperparameters for
piper1-gpl, the time trial's step timing, projected hours and rent verdict
(R7), the MPS-to-CPU fallback, the rented-GPU upload manifest (only the
synthetic dataset, never the owner's clips), and the sherpa-onnx export
layout. Torch, Lightning, piper and onnx are never imported here: the heavy
parts are passed in as fakes. Runs on the host python (standard library only).
"""
import importlib.util
import io
import json
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
SCRIPT = REPO / "scripts" / "voice" / "train-piper.py"


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


tp = load("voice_train_piper", SCRIPT)


def make_work(td):
    """A fake voice-work/ with a dataset, the owner's clips and a checkpoint."""
    work = Path(td) / "voice-work"
    wavs = work / "dataset" / "wavs"
    wavs.mkdir(parents=True)
    for i in (1, 2, 3):
        (wavs / f"{i:04d}.wav").write_bytes(b"RIFF")
    # Row 0004 has no wav yet; the last line is still being written (no newline).
    (work / "dataset" / "metadata.csv").write_text(
        "0001|Hooray, the dusty shelf is back!\n"
        "0002|The busy chef knocked.\n"
        "bad line without a bar\n"
        "0003|The little robot rolled.\n"
        "0004|Not rendered yet.\n"
        "0005|Half writ", encoding="utf-8")
    (work / "dataset" / "progress.log").write_text("log\n")
    for private in ("clips", "reference", "samples"):
        (work / private).mkdir()
        (work / private / "owner.wav").write_bytes(b"RIFF")
    (work / "checkpoints").mkdir()
    (work / "checkpoints" / "lessac-medium-piper1.ckpt").write_bytes(b"ckpt")
    return work


class MetadataTests(unittest.TestCase):
    def test_reads_complete_rows_only(self):
        with tempfile.TemporaryDirectory() as td:
            work = make_work(td)
            rows = tp.read_metadata(work / "dataset" / "metadata.csv")
        self.assertEqual([r[0] for r in rows], ["0001", "0002", "0003", "0004"])
        self.assertEqual(rows[0][1], "Hooray, the dusty shelf is back!")

    def test_preprocess_writes_piper_csv_for_rendered_rows(self):
        with tempfile.TemporaryDirectory() as td:
            work = make_work(td)
            out = work / "piper" / "full"
            n = tp.preprocess(work / "dataset", out, work_root=work)
            lines = (out / "metadata.csv").read_text(encoding="utf-8").splitlines()
        self.assertEqual(n, 3)
        self.assertEqual(lines, ["0001.wav|Hooray, the dusty shelf is back!",
                                 "0002.wav|The busy chef knocked.",
                                 "0003.wav|The little robot rolled."])

    def test_preprocess_limit_takes_the_first_rows(self):
        with tempfile.TemporaryDirectory() as td:
            work = make_work(td)
            n = tp.preprocess(work / "dataset", work / "piper" / "sub", limit=2, work_root=work)
        self.assertEqual(n, 2)

    def test_preprocess_refuses_output_outside_voice_work(self):
        with tempfile.TemporaryDirectory() as td:
            work = make_work(td)
            with self.assertRaises(SystemExit):
                tp.preprocess(work / "dataset", Path(td) / "elsewhere", work_root=work)

    def test_text_with_bar_is_rejected(self):
        self.assertIsNone(tp.piper_row("0001", "a | b"))
        self.assertEqual(tp.piper_row("0001", " hi. "), "0001.wav|hi.")


class CheckpointTests(unittest.TestCase):
    def test_clean_hparams_keeps_model_args_and_stringifies_paths(self):
        hp = {"sample_rate": 22050, "sample_bytes": 2, "accelerator": "gpu",
              "dataset": [Path("/x")], "dataset_dir": Path("/home/x"), "learning_rate": 2e-4}
        accepted = {"sample_rate", "learning_rate", "dataset_dir"}
        self.assertEqual(tp.clean_hparams(hp, accepted),
                         {"sample_rate": 22050, "learning_rate": 2e-4, "dataset_dir": "/home/x"})


class ProjectionTests(unittest.TestCase):
    def test_steps_per_epoch_matches_piper_split_and_drop_last(self):
        # 1400 rows, 10 % validation, 0 test -> 1260 train rows; 1260 // 32 = 39.
        self.assertEqual(tp.steps_per_epoch(1400, 32, validation_split=0.1, num_test=0), 39)
        # A train split not larger than the batch keeps its one ragged batch.
        self.assertEqual(tp.steps_per_epoch(20, 32, validation_split=0.0, num_test=0), 1)

    def test_step_seconds_drops_warmup_and_takes_median(self):
        self.assertAlmostEqual(tp.step_seconds([20.0, 3.0, 2.0, 2.5, 9.0], warmup=1), 2.75)
        with self.assertRaises(ValueError):
            tp.step_seconds([20.0], warmup=1)

    def test_projection_hours(self):
        p = tp.project(step_s=2.0, full_rows=1400, batch_size=32, epochs=1000,
                       validation_split=0.1, num_test=0)
        self.assertEqual(p["steps_per_epoch"], 39)
        self.assertAlmostEqual(p["epoch_s"], 78.0)
        self.assertAlmostEqual(p["total_h"], 78.0 * 1000 / 3600)

    def test_rent_verdict_threshold(self):
        self.assertFalse(tp.needs_rent(23.9))
        self.assertFalse(tp.needs_rent(24.0))
        self.assertTrue(tp.needs_rent(24.1))
        self.assertTrue(tp.needs_rent(5.0, threshold_h=4.0))


class TimeTrialTests(unittest.TestCase):
    def test_falls_back_to_cpu_when_mps_fails(self):
        calls = []

        def runner(accelerator, **kw):
            calls.append(accelerator)
            if accelerator == "mps":
                raise RuntimeError("aten::foo not implemented for MPS")
            return [30.0, 10.0, 10.0, 12.0]

        with redirect_stdout(io.StringIO()):
            r = tp.time_trial(runner, full_rows=1400, batch_size=32, steps=4,
                              mps_available=True, epochs=1000)
        self.assertEqual(calls, ["mps", "cpu"])
        self.assertEqual(r["accelerator"], "cpu")
        self.assertIn("MPS", r["mps_error"])
        self.assertAlmostEqual(r["step_s"], 10.0)
        self.assertTrue(r["rent"])

    def test_uses_mps_when_it_works_and_skips_it_when_absent(self):
        with redirect_stdout(io.StringIO()):
            r = tp.time_trial(lambda accelerator, **kw: [5.0, 0.5, 0.5], full_rows=100,
                              batch_size=32, steps=3, mps_available=True, epochs=1000)
            self.assertEqual(r["accelerator"], "mps")
            self.assertFalse(r["rent"])  # 2 steps/epoch * 0.5 s * 1000 = 0.28 h
            seen = []
            tp.time_trial(lambda accelerator, **kw: seen.append(accelerator) or [1.0, 1.0],
                          full_rows=100, batch_size=32, steps=2, mps_available=False)
        self.assertEqual(seen, ["cpu"])

    def test_report_names_the_verdict(self):
        with redirect_stdout(io.StringIO()) as out:
            tp.time_trial(lambda accelerator, **kw: [9.0, 3.0, 3.0], full_rows=1400,
                          batch_size=32, steps=3, mps_available=True, epochs=1000)
        self.assertIn("rented GPU", out.getvalue())


class RentManifestTests(unittest.TestCase):
    def test_uploads_only_the_dataset_and_checkpoint(self):
        with tempfile.TemporaryDirectory() as td:
            work = make_work(td)
            ckpt = work / "checkpoints" / "lessac-medium-piper1.ckpt"
            m = tp.rent_manifest(work / "dataset", ckpt, work_root=work)
            uploads = [Path(p).resolve() for p in m["upload"]]
            dataset = (work / "dataset").resolve()
            data_files = [p for p in uploads if p != ckpt.resolve()]
            self.assertTrue(data_files)
            for p in data_files:
                self.assertTrue(p.is_relative_to(dataset), p)
            for private in ("clips", "reference", "samples"):
                self.assertFalse(any(p.is_relative_to((work / private).resolve()) for p in uploads))
            names = {p.name for p in uploads}
            self.assertEqual(names, {"metadata.csv", "0001.wav", "0002.wav", "0003.wav",
                                     "lessac-medium-piper1.ckpt"})
            self.assertTrue(any("piper.train fit" in c for c in m["commands"]))
            self.assertTrue(any("--trainer.accelerator gpu" in c for c in m["commands"]))

    def test_refuses_a_dataset_outside_voice_work_dataset(self):
        with tempfile.TemporaryDirectory() as td:
            work = make_work(td)
            with self.assertRaises(SystemExit):
                tp.rent_manifest(work / "clips", work / "checkpoints" / "lessac-medium-piper1.ckpt",
                                 work_root=work)

    def test_refuses_a_checkpoint_outside_checkpoints(self):
        with tempfile.TemporaryDirectory() as td:
            work = make_work(td)
            with self.assertRaises(SystemExit):
                tp.rent_manifest(work / "dataset", work / "clips" / "owner.wav", work_root=work)


class ExportTests(unittest.TestCase):
    CONFIG = {"audio": {"sample_rate": 22050}, "espeak": {"voice": "en-us"},
              "num_speakers": 1, "phoneme_id_map": {"_": [0], "^": [1], "a": [14]}}

    def test_export_builds_sherpa_layout(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            config = td / "config.json"
            config.write_text(json.dumps(self.CONFIG))
            espeak = td / "espeak-ng-data"
            (espeak / "voices").mkdir(parents=True)
            (espeak / "phontab").write_bytes(b"x")
            out = td / "assets" / "voice"
            ran, meta = [], {}

            def runner(cmd):
                ran.append(cmd)
                Path(cmd[cmd.index("--output-file") + 1]).write_bytes(b"onnx")

            def add_metadata(path, data):
                meta.update(data)

            with redirect_stdout(io.StringIO()):
                tp.export(td / "last.ckpt", config, out, espeak, runner=runner,
                          add_metadata=add_metadata)
            self.assertEqual(tp.missing_sherpa_files(out), [])
            self.assertEqual((out / "tokens.txt").read_text(encoding="utf-8"), "_ 0\n^ 1\na 14\n")
            self.assertTrue((out / "espeak-ng-data" / "phontab").is_file())
            self.assertTrue((out / "README.md").is_file())
            self.assertIn("piper.train.export_onnx", " ".join(map(str, ran[0])))
            self.assertEqual(meta["model_type"], "vits")
            self.assertEqual(meta["comment"], "piper")
            self.assertEqual(meta["voice"], "en-us")
            self.assertEqual(meta["has_espeak"], 1)
            self.assertEqual(meta["sample_rate"], 22050)
            self.assertEqual(meta["n_speakers"], 1)
            self.assertEqual(meta["language"], "English")

    def test_tokens_skip_multi_character_symbols(self):
        # piper1-gpl's id map lists diphthongs ("aɪ") that sherpa-onnx cannot
        # read; without vowel_clusters training never uses them.
        cfg = dict(self.CONFIG, phoneme_id_map={" ": [3], "a": [14], "aɪ": [161]})
        self.assertEqual(tp.tokens_text(cfg), "  3\na 14\n")
        with self.assertRaises(SystemExit):
            tp.tokens_text(dict(cfg, vowel_clusters={"aɪ": ["a", "ɪ"]}))

    def test_missing_sherpa_files_names_each_gap(self):
        with tempfile.TemporaryDirectory() as td:
            self.assertEqual(tp.missing_sherpa_files(Path(td)),
                             ["model.onnx", "tokens.txt", "espeak-ng-data/"])

    def test_export_fails_when_onnx_is_not_written(self):
        with tempfile.TemporaryDirectory() as td:
            td = Path(td)
            config = td / "config.json"
            config.write_text(json.dumps(self.CONFIG))
            (td / "espeak-ng-data").mkdir()
            with self.assertRaises(SystemExit), redirect_stdout(io.StringIO()):
                tp.export(td / "last.ckpt", config, td / "out", td / "espeak-ng-data",
                          runner=lambda cmd: None, add_metadata=lambda p, d: None)


if __name__ == "__main__":
    unittest.main()
