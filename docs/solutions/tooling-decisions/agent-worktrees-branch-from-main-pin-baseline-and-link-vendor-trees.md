---
title: "Worktree workers start on main, not the feature branch: pin the baseline in the dispatch packet and symlink the gitignored vendor trees"
module: "Agent-driven implementation waves in this repo (Claude Code orchestrator + worktree-isolated workers; scripts/build_common.py, scripts/build-custom-launcher.py, scripts/build-mode-explore.py, .gitignore)"
date: "2026-09-26"
problem_type: tooling_decision
category: tooling-decisions
component: tooling
severity: high
related_components:
  - "development_workflow"
  - "testing_framework"
  - "infrastructure"
tags:
  - "git-worktree"
  - "claude-code"
  - "subagent-dispatch"
  - "baseline-commit"
  - "cherry-pick"
  - "gitignored-vendor-trees"
  - "serviceexam-jadx"
  - "sherpa-onnx"
  - "build-scripts"
applies_when:
  - "A Claude Code orchestrator dispatches implementation or simplification workers with worktree isolation (Workflow agent({isolation:'worktree'}) or the Agent tool's worktree isolation) while the orchestrator itself sits on a feature branch"
  - "Worker diffs must be cherry-picked back onto the orchestrator's branch in dependency order, or reviewed uncommitted before landing"
  - "A worker in a fresh worktree needs to run scripts/build-custom-launcher.py, scripts/build-mode-explore.py, or the build tests (test_build_mode_explore.py, test_build_mode_voice.py, BuildScriptTest cases in test_listen_service.py)"
  - "A fresh checkout lacks tools/serviceexam_jadx/ (extracted vendor APK + arm64-v8a native libs), tools/third_party/ (sherpa-onnx release) or mode-explore's staged libmiko_drivers.so because they are gitignored"
symptoms:
  - "A worker's first 'git log --oneline -1' in its worktree shows the main tip (e.g. c14c39e) instead of the feature branch tip the orchestrator was on (4590e88, later 81254fd on feat/explore-meeting-small-talk)"
  - "Worker diffs reference files or symbols that do not exist on the feature branch, or cherry-picks conflict on code the feature branch already changed"
  - "scripts/build_common.py aborts with 'vendor wake-word libraries missing from .../tools/serviceexam_jadx/resources/lib/arm64-v8a' in a worktree that builds fine from the main checkout"
  - "Build tests fail in the worktree with missing tools/third_party/ or lib_stage inputs while passing in the primary checkout"
root_cause: incomplete_setup
resolution_type: workflow_improvement
---

# Worktree workers start on main, not the feature branch: pin the baseline in the dispatch packet and symlink the gitignored vendor trees

## Context

During the 2026-09-25/26 session on `feat/explore-meeting-small-talk`, the orchestrator dispatched two waves of workers with worktree isolation: a four-worker implementation wave and a three-worker simplification wave (the feature branch stood at the session's `4590e88` and later `81254fd`; these and the other SHAs in this doc are examples from that session and will not survive a squash merge, so treat them as illustrations, not references). Both times every worktree was created from the tip of `main` (`c14c39e`, which is `git merge-base main feat/explore-meeting-small-talk` today; `main` has since moved to `2ed86be` (the `main` tip at that moment) via PR #17). None of the workers were on the branch the orchestrator was on. A worker that trusted the worktree would have implemented against code that lacked the whole conversation feature (ChatSession, the multi-turn Claude client, the line clips) and returned a diff that was either unmergeable or wrong.

The same worktrees exposed a second problem as soon as a worker ran a build script or the build tests. The vendor trees are gitignored (`.gitignore:61` ignores `*_jadx/`, which covers `tools/serviceexam_jadx/`; `.gitignore:73` ignores `tools/third_party/`; the `.gitignore` entries at lines 26-27 ignore the `out` and `build` output directories). A fresh worktree therefore has none of the files the build scripts stage, and they fail by design with an actionable message.

Neither problem is a bug in the worktree tooling; both are properties of how this repo is laid out, and both need a fixed recipe in every dispatch packet.

## Guidance

### 1. Put the baseline in the dispatch packet, and make the worker reset to it

State the exact commit and require the worker to verify and reset before touching anything. Wording that worked:

```
BASELINE: feat/explore-meeting-small-talk at 81254fd.
Your worktree was probably created from main, not from this branch.
Before you read or edit anything:
  git log --oneline -1          # if this is not 81254fd, you are on the wrong base
  git reset --hard 81254fd
Then confirm `git log --oneline -1` prints 81254fd and proceed.
Commit your work on your worktree branch; do not rebase or merge main.
```

Use the full SHA in the real packet (short SHAs are fine to quote in reports). The commit is reachable from the worktree because worktrees share the main checkout's object store, so `git reset --hard` needs no fetch.

### 2. Verify each worktree branch before integrating

From the main checkout, for each worker branch:

```
git merge-base --is-ancestor 81254fd <worker-branch> && echo ok || echo WRONG BASE
git log --oneline 81254fd..<worker-branch>
```

A worker that skipped the reset fails the first line; do not integrate its commits, re-dispatch with the packet above.

### 3. Integrate by cherry-pick in dependency order, then delete the worktrees

```
git cherry-pick <hash>            # lands the worker's commit as-is
git cherry-pick -n <hash>         # lands the changes uncommitted, for a review or squash step first
git worktree remove --force <path>
git branch -D <worker-branch>
```

`cherry-pick -n` was used when the simplification wave's three results were reviewed as one diff before committing (the simplification commit on this branch, `0488d67` in the session that produced this doc). Do not `git merge` the worker branches: with a wrong base that would pull `main`'s later commits into the feature branch silently.

### 4. Vendor trees in a fresh worktree: symlink from the main checkout, remove before committing

The build scripts stage from gitignored directories:

- `scripts/build_common.py:35` sets `VENDOR_LIB_DIR = tools/serviceexam_jadx/resources/lib/arm64-v8a`; `wakeword_native_libs()` at `build_common.py:54-66` raises `BuildError` ("vendor wake-word libraries missing from ...; re-extract it into tools/serviceexam_jadx/resources/ (jadx)") when any of the three wake-word `.so` files is absent.
- `scripts/build-mode-explore.py:56-57` stages `libmiko_drivers.so` from the same directory (`vendor_native_libs()` at lines 75-86 raises "vendor motor-driver library missing"), and caches the ONNX Runtime AAR in `tools/third_party/` (`ORT_CACHE`, line 66; extracted at lines 109-111).
- `scripts/build-custom-launcher.py:77` uses the same `VENDOR_LIB_DIR`; lines 96 and 107 cache the sherpa-onnx release and the listen model under `tools/third_party/`; `vendor_native_libs()` at lines 355-374 raises for a missing `libmiko_drivers.so` or the DSP library.

The tests read the same paths: `scripts/tests/test_build_mode_explore.py:28`, `scripts/tests/test_build_mode_voice.py:38-41` (lines 40-41 also read the vendor wake-word Java source and its tflite model from inside `tools/serviceexam_jadx/`), `scripts/tests/test_listen_service.py:40-41` (`VENDOR_JNI`, `VENDOR_LIB_DIR`, used by `BuildScriptTest` at line 500+), and `scripts/tests/test_speech_service.py:517` (`VENDOR_DIR`; its vendor-library test skips rather than fails when the library is not extracted, so only the first three fail in a fresh worktree).

In a worktree at `<wt>`, with the main checkout at `<main>`:

```
ln -s <main>/tools/serviceexam_jadx <wt>/tools/serviceexam_jadx
ln -s <main>/tools/third_party      <wt>/tools/third_party
```

Both targets are ignored, so `git status` stays clean, but remove the links before the final commit and before the orchestrator removes the worktree, so nothing ever depends on them (`git worktree remove --force` deletes the link itself and does not follow it; removing the links first is hygiene, not a safety requirement):

```
rm <wt>/tools/serviceexam_jadx <wt>/tools/third_party
```

`cp -R` works too but copies a populated `tools/third_party/`, roughly 1 GB on this machine (the ONNX Runtime AAR and its extracted libraries, the sherpa-onnx release, the listen model, the voicecheck model); a symlink is enough because the scripts only read from these directories (downloads into `tools/third_party/` are checksummed and idempotent, so a shared cache is safe).

Put the symlink recipe in the packet of any worker whose task includes running a build script or `python3 -m unittest` over `scripts/tests/`.

## Why This Matters

- A wrong base produces a diff that looks complete and passes the worker's own tests, so the failure only shows up at integration, after the whole wave's cost is spent. Two waves hit it in one session; without the packet rule it will happen on every wave.
- The build scripts fail loudly (`BuildError` is a `SystemExit` subclass, `build_common.py:50`), which is the right behaviour for a real missing vendor extraction, but in a worktree the message sends the worker off to re-extract an APK it does not have. Naming the symlink recipe up front avoids that detour.
- `cherry-pick` plus `merge-base --is-ancestor` keeps the feature branch's history linear and free of `main` commits that were never reviewed for this feature.

## When to Apply

- Any time an orchestrator on a non-default branch dispatches workers with worktree isolation, in this repo. Assume the worktree is on `main` until `git log --oneline -1` proves otherwise.
- Any worker task that runs `scripts/build-custom-launcher.py`, `scripts/build-mode-explore.py`, `scripts/build-mode-voice.py`, or the tests named above from a worktree.
- Not needed for workers that only edit Java or Python sources and run tests that do not touch `tools/`; the reset rule still applies.

## Examples

Before (implementation wave, 2026-09-25): packet said "implement U8 on the feature branch"; all four worktrees reported `c14c39e` from `git log --oneline -1`; the orchestrator had to redirect each worker mid-task to reset to `4590e88`.

After (simplification wave, 2026-09-26): packet carried the block from Guidance 1 with `81254fd`; each worker's first report line was `81254fd`; the orchestrator ran `git merge-base --is-ancestor 81254fd <branch>` on all three, landed them with `git cherry-pick -n` in dependency order, reviewed the combined diff, committed `0488d67`, and removed the three worktrees and branches.

Worker report excerpt that fixed the build tests in a worktree (paraphrased from the session): "test_build_mode_explore and BuildScriptTest fail: `vendor motor-driver library missing: .../tools/serviceexam_jadx/resources/lib/arm64-v8a/libmiko_drivers.so`. Symlinked tools/serviceexam_jadx and tools/third_party from the main checkout; all scripts/tests pass; removed the symlinks before committing."

Commit hashes above are from this session and may be rewritten if the branch is rebased; the branch name and PR numbers (#16 for `c14c39e`, #17 for `2ed86be`) are the stable references.

## Related

- `docs/solutions/tooling-decisions/on-device-object-detection-yoloe-onnx-runtime-miko3.md`: Documents that build-mode-explore.py downloads the ONNX Runtime AAR into the gitignored tools/third_party/ and that the detector export venv lives in gitignored tools/detector-export/.
- `.gitignore`, `scripts/build_common.py`, `scripts/build-custom-launcher.py`, `scripts/build-mode-explore.py`: the ignore rules and the staging that make the vendor trees a prerequisite in every worktree.
