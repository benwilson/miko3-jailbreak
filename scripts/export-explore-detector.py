#!/usr/bin/env python3
"""export-explore-detector.py — build explore mode's object detector from its
vocabulary (camera curiosity, KTD2).

YOLOE-26n is an open-vocabulary detector: given a list of names, it bakes their
text embeddings into its classification head, and the exported model is then a
plain detector for exactly those names, in that order. This exports it to ONNX
for ONNX Runtime on the robot, at the camera's 480x640 frame size.

Runs in its own environment, since Ultralytics pulls in PyTorch:

  uv venv --python 3.11 tools/detector-export/.venv
  uv pip install --python tools/detector-export/.venv/bin/python ultralytics \\
      "git+https://github.com/ultralytics/CLIP.git"
  tools/detector-export/.venv/bin/python scripts/export-explore-detector.py

Writes mode-explore/assets/detector.onnx and prints a sanity detection on an
optional test image (--check IMAGE).

YOLOE is AGPL-3.0; fine for this personal build, but publish the source if the
APK is ever distributed.
"""
import argparse
import shutil
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VOCABULARY = REPO / "mode-explore" / "assets" / "vocabulary.txt"
OUT = REPO / "mode-explore" / "assets" / "detector.onnx"
WEIGHTS = "yoloe-26n-seg.pt"
IMGSZ = (480, 640)  # the camera's frame, height x width


def read_vocabulary(path=VOCABULARY):
    """The names, in order: non-blank lines that are not '#' comments."""
    names = []
    for line in Path(path).read_text().splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            names.append(line)
    if len(set(names)) != len(names):
        dupes = sorted({n for n in names if names.count(n) > 1})
        raise SystemExit(f"!! duplicate names in {path}: {', '.join(dupes)}")
    return names


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("--check", metavar="IMAGE", help="run the detector on this image before exporting")
    ap.add_argument("--out", default=str(OUT))
    args = ap.parse_args()

    from ultralytics import YOLOE  # only in the export environment

    names = read_vocabulary()
    model = YOLOE(WEIGHTS)
    model.set_classes(names)
    if args.check:
        result = model.predict(args.check, conf=0.25, verbose=False)[0]
        for box in result.boxes:
            print(f"  {names[int(box.cls)]:<20} {float(box.conf):.2f} "
                  f"{[int(v) for v in box.xyxy[0].tolist()]}")
    exported = Path(model.export(format="onnx", imgsz=IMGSZ, simplify=True, dynamic=False))
    Path(args.out).parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(exported), args.out)
    print(f"wrote {args.out} ({Path(args.out).stat().st_size // 1024} KB, {len(names)} names)")


if __name__ == "__main__":
    sys.exit(main())
