#!/usr/bin/env python3
"""
Host-side zip4j extraction test - diagnose why the Miko 3's zip4j extracts nothing.

Run: python3 scripts/test-zip4j.py
"""
import zipfile
import os
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
APPS_ZIP = REPO / "hooks" / "sd-free" / "APPS.zip"
APPS_TEST_ZIP = REPO / "hooks" / "sd-free-test" / "APPS-test.zip"
OUTPUT_DIR = REPO / "hooks" / "sd-free-test" / "extracted"


def test_zip(path: Path, label: str):
    print(f"\n{'='*60}")
    print(f"Testing: {label}")
    print(f"Path: {path}")
    print(f"Exists: {path.exists()}")
    if not path.exists():
        print(f"  SKIPPING - file not found")
        return
    
    print(f"Size: {path.stat().st_size} bytes")
    print(f"Magic: {path.read_bytes()[:4].hex()}")
    
    # Python's zipfile - does it extract?
    print(f"\n--- Python zipfile ---")
    try:
        with zipfile.ZipFile(path) as zf:
            print(f"  isValidZipFile: True")
            print(f"  file count: {len(zf.namelist())}")
            for name in zf.namelist():
                info = zf.getinfo(name)
                print(f"    {name}: {info.file_size} bytes, comp={info.compress_type}")
            
            # Try extraction
            out = OUTPUT_DIR
            out.mkdir(parents=True, exist_ok=True)
            zf.extractall(out)
            print(f"  Extraction: SUCCESS")
            print(f"  Extracted to: {out}")
            for f in out.rglob("*"):
                print(f"    {f.relative_to(out)}")
    except Exception as e:
        print(f"  isValidZipFile: False")
        print(f"  Error: {e}")
    
    # Compare with zipinfo
    print(f"\n--- zipinfo ---")
    import subprocess
    result = subprocess.run(
        ["zipinfo", "-1", str(path)],
        capture_output=True,
        text=True,
        check=False
    )
    for line in result.stdout.splitlines()[:20]:
        print(f"  {line}")


if __name__ == "__main__":
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    test_zip(APPS_ZIP, "Original APPS.zip (from hooks/sd-free)")
    test_zip(APPS_TEST_ZIP, "Test APPS-test.zip (from hooks/sd-free-test)")
    print(f"\n{'='*60}")
    print("Test complete. Check hooks/sd-free-test/extracted/ for results.")