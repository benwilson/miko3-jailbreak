"""Shared plumbing for the tests that compile Java fixtures for the host JVM.

The robot has no Android unit-test harness, so several suites compile shared or
mode sources (with stubs) under javac and run a harness main that prints one
line per scenario: "PASS <name>" or "FAIL <name>: <detail>".
"""
import os
import shutil
import sys
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parents[1]


def find_jdk():
    """(javac, java) from the JDK build_common picks for the APK builds, else PATH."""
    sys.path.insert(0, str(SCRIPTS))
    try:
        import build_common
        home = build_common.java_home()
    except Exception:
        home = ""
    finally:
        sys.path.remove(str(SCRIPTS))
    if home and (Path(home) / "bin" / "javac").exists():
        return str(Path(home) / "bin" / "javac"), str(Path(home) / "bin" / "java")
    javac, java = shutil.which("javac"), shutil.which("java")
    return (javac, java) if javac and java else None


def javac_cmd(javac, out_dir, sources, sourcepath=()):
    """The javac command line. Same language level as build_common.compile_java;
    -Xlint:-options hides the "source 8 is obsolete" chatter from modern JDKs."""
    cmd = [javac, "-source", "8", "-target", "8", "-encoding", "UTF-8", "-Xlint:-options"]
    if sourcepath:
        cmd += ["-sourcepath", os.pathsep.join(str(p) for p in sourcepath)]
    return cmd + ["-d", str(out_dir)] + [str(s) for s in sources]


def parse_verdicts(stdout):
    """{scenario name: (verdict, detail)} from a harness's PASS/FAIL lines."""
    results = {}
    for line in stdout.splitlines():
        verdict, _, rest = line.partition(" ")
        if verdict in ("PASS", "FAIL"):
            name, _, detail = rest.partition(": ")
            results[name] = (verdict, detail)
    return results


def add_scenario_tests(case):
    """One test_<name> per entry of case.SCENARIOS, each calling case._assert_pass(name)."""
    for _name in case.SCENARIOS:
        def _test(self, name=_name):
            self._assert_pass(name)
        setattr(case, f"test_{_name}", _test)
