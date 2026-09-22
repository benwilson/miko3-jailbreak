---
title: "Per-mode eye looks on the shared EyesPage never render: the inline blink animation beats non-!important stylesheet rules"
date: 2026-09-22
category: ui-bugs
module: "Miko 3 shared eyes page (shared EyesPage) + explore mode per-state looks (ExploreState STATE_CSS)"
problem_type: ui_bug
component: frontend
symptoms:
  - "On the robot the explore eyes-only look never appears; eyes stay fully round and keep blinking"
  - "#rig.className is 's-eyes-only' in the page, yet a screenshot shows full round glows"
  - "getComputedStyle on .glow-core shows the blink animation still running instead of flinch/drowse/none"
  - "Host tests pass because they only assert the per-state CSS text is present in the page"
root_cause: logic_error
resolution_type: code_fix
severity: medium
related_components:
  - "testing_framework"
tags:
  - "css-cascade"
  - "inline-style"
  - "css-animation"
  - "important-override"
  - "eyes-page"
  - "explore-mode"
  - "webview"
  - "browser-verification"
---

# Per-mode eye looks silently overridden by the shared eyes' inline blink

## Problem

Every custom mode on the Miko 3 renders the same eyes page, built by `shared/src/com/miko3/shared/EyesPage.java`. The page is a `#rig` containing two lenses, each `.housing > .glass > .glow > .glow-core`. The class javadoc invites callers to "restyle them by class on #rig from its own CSS (the voice mode does this per conversation state)" and to wrap the global `gazeTo(x,y,speedMs)` (`shared/src/com/miko3/shared/EyesPage.java:35-40`).

The explore mode followed that hook. Its `STATE_CSS` (`mode-explore/src/com/miko3/mode/explore/ExploreState.java`) added per-state looks on `.glow-core`, keyed by the `s-<state>` class its poll script puts on `#rig`:

- `s-eyes-only`: half-closed and dimmed (`transform:scale(1,.45);opacity:.7`)
- `s-flinch`: a one-shot `flinch` keyframe squint
- `s-resting`: a looping `drowse` keyframe

None of these looks ever appeared on the robot. The eyes stayed fully round and kept blinking in every state. The CSS was present and the selectors matched, but a declaration the shared page sets inline, which the mode's CSS never touched, beat every rule.

## Symptoms

- On the robot, with no usable sensors (the eyes-only state), the eyes stayed fully open and blinked normally instead of showing the half-closed, dimmed look.
- Flinch and resting looked exactly like idle: no squint, no drowsy breathing.
- In a headless browser against the robot's page, `document.getElementById('rig').className` was `"s-eyes-only"`, so the state class was applied, yet a screenshot showed full round glows.
- `getComputedStyle` on `.glow-core` reported `animationName: "blink"` in every state.
- Host tests (`scripts/tests/test_explore_state_page.py`) passed throughout. They only checked that the CSS text was in the page.

## What Didn't Work

- **Trusting host tests that assert CSS text.** The page tests checked that each non-idle state had a `#rig.s-<state>` rule. That proves the text is in the page, not that the rule wins the cascade. Such a test cannot fail on this bug.
- **Reasoning from the stylesheet alone.** Read on its own, `STATE_CSS` looks correct: `#rig.s-eyes-only .glow-core` is more specific than `.glow-core` in `LENS_CSS`. Specificity was never the issue. The competing declarations are not in any stylesheet. They are set from script, as inline styles.
- **Editing the shared EyesPage.** Moving the blink out of the inline style would have fixed explore. But it changes the page every mode serves, and `scripts/tests/test_eyes_page_golden.py:103-108` pins the composed page byte for byte against a golden fixture. The fix belongs in the mode's own CSS, which is where the javadoc says customization goes.

## Solution

The fix is in PR #4 (benwilson/miko3-jailbreak), unmerged as of writing: mark the per-state `animation` declarations `!important`, and have eyes-only cancel the blink explicitly. The shared `EyesPage` is unchanged.

Before (the pre-fix `mode-explore/src/com/miko3/mode/explore/ExploreState.java` in PR #4, before its "per-state eye looks were overridden by the inline blink" commit):

```java
private static final String STATE_CSS =
        "#rig.s-look .glow-core{filter:brightness(1.15)}"
        + "@keyframes flinch{0%{transform:scale(1,1)}25%{transform:scale(1.18,.3)}"
        + "60%{transform:scale(.95,1.08)}100%{transform:scale(1,1)}}"
        + "#rig.s-flinch .glow-core{animation:flinch .55s cubic-bezier(.3,1.4,.5,1) 1}"
        + "#rig.s-eyes-only .glow-core{transform:scale(1,.45);opacity:.7;transition:transform .6s,opacity .6s}"
        + "@keyframes drowse{from{transform:scale(1,.6);opacity:.85}to{transform:scale(1,.3);opacity:.55}}"
        + "#rig.s-resting .glow-core{animation:drowse 2.4s ease-in-out infinite alternate}";
```

After (`mode-explore/src/com/miko3/mode/explore/ExploreState.java:117-130`, comments trimmed):

```java
private static final String STATE_CSS =
        "#rig.s-" + LOOK + " .glow-core{filter:brightness(1.15)}"
        + "@keyframes flinch{0%{transform:scale(1,1)}25%{transform:scale(1.18,.3)}"
        + "60%{transform:scale(.95,1.08)}100%{transform:scale(1,1)}}"
        + "#rig.s-" + FLINCH + " .glow-core{animation:flinch .55s cubic-bezier(.3,1.4,.5,1) 1!important}"
        + "#rig.s-" + EYES_ONLY + " .glow-core{animation:none!important;transform:scale(1,.45);opacity:.7;"
        + "transition:transform .6s,opacity .6s}"
        + "@keyframes drowse{from{transform:scale(1,.6);opacity:.85}to{transform:scale(1,.3);opacity:.55}}"
        + "#rig.s-" + RESTING + " .glow-core{animation:drowse 2.4s ease-in-out infinite alternate!important}";
```

Three changes:

1. `flinch` and `resting`: `!important` on the `animation` shorthand.
2. `eyes-only`: a new `animation:none!important`. This stops the blink, so the plain `transform` and `opacity` now take effect. They need no `!important` once nothing is animating them.
3. `look` is unchanged. `filter` is not a property the blink animates, and nothing inline sets it.

The same PR also added a regression test, `test_animated_looks_override_the_inline_blink` (`scripts/tests/test_explore_state_page.py:61-70`).

A browser re-test against the robot confirmed each state. Eyes-only computed `animationName: "none"` with `transform: matrix(1, 0, 0, 0.45, 0, 0)`. Flinch computed `"flinch"`, resting computed `"drowse"`, and idle still computed `"blink"`.

## Why This Works

Two cascade rules combined to hide the looks.

**1. An inline style beats any stylesheet rule that is not `!important`.** The shared page starts the blink from script, as an inline style on each core (`shared/src/com/miko3/shared/EyesPage.java:113-116`):

```js
cores[g].style.animation='blink 6.5s infinite';
cores[g].style.animationDelay=(g*0.2)+'s';
```

Inline declarations sit above every normal author stylesheet declaration, whatever the selector specificity. So `#rig.s-flinch .glow-core{animation:flinch ...}` and the `drowse` rule lost to `blink 6.5s infinite` every time. For the `animation` property, only an `!important` author declaration outranks a normal inline one.

**2. A running animation beats a normal declaration of the property it animates.** The `blink` keyframes animate `transform` and `opacity` on `.glow-core`, with `transform:scale(1);opacity:1` for 90% of each cycle (`EyesPage.java:97-98`). In the cascade, animation values sit above all normal declarations, inline or stylesheet, and below `!important` ones. So even though `#rig.s-eyes-only .glow-core{transform:scale(1,.45);opacity:.7}` matched, the still-running blink replaced both values with its keyframe values on every frame. The eyes-only look had to stop the animation (`animation:none!important`) before its static transform could show.

`EyesPage` already records the second rule. The comment at `EyesPage.java:69-78` says gaze and blink are split across `.glow` and `.glow-core` because "a CSS animation's transform wins over one set via element style for the same property on the same element." The explore looks hit the same trap from the other side: they set static values on the element the blink animates.

**Why voice mode's per-state CSS didn't collide** (`mode-voice/src/com/miko3/mode/voice/VoiceState.java:109-127`):

- The engaged states restyle `.glow-core`'s `background`, which the blink doesn't animate and nothing sets inline.
- Connecting changes `width/height/left/top` on `.glow`, not `.glow-core`.
- The `talk` pulse for speaking and closing is an `animation` on `.glow`. That is a different element from the one carrying the inline blink, so the two animations don't compete.
- The one voice rule that does touch the blink, `#rig.s-unreachable .glow-core{...animation-duration:13s!important}` (`VoiceState.java:125-127`), already uses `!important`. Its comment says why: "because the blink's duration is set inline by the eyes' script" (`VoiceState.java:122`). Voice mode had hit this same issue for one property. Explore was the first mode to replace the blink outright.

## Prevention

**Rules for any mode that customizes the shared eyes:**

- Before styling `.glow-core`, list what the shared script sets inline: `animation` and `animationDelay` on `.glow-core`, and `transform` and `transition` on `.glow` from `gazeTo` (`EyesPage.java:113-126`). Also note what the `blink` keyframes animate: `transform` and `opacity` on `.glow-core`.
- A per-state look that replaces, stops, or retimes the blink needs `!important` on its `animation` (or `animation-*`) declaration. To show a static squash or dim on `.glow-core`, use `animation:none!important` first, then set `transform` and `opacity` normally.
- Put per-state size or position changes on `.glow` or `.housing`, and pulses on `.glow` (as voice's `talk` does). Keep in mind that `gazeTo` owns `.glow`'s `transform` inline, so a per-state `transform` on `.glow` would need `!important` too, and would then break gaze.
- Don't fix this in `EyesPage`. It is shared and golden-pinned (`scripts/tests/test_eyes_page_golden.py:103-108`), and the mode-level `!important` override is the intended hook.

**Verify eye looks in a real browser, not by grepping the CSS.** On the robot:

1. Start the mode on the robot. Then run `adb forward tcp:8446 tcp:8446` (the explore mode's `HTTPS_PORT`, `mode-explore/src/com/miko3/mode/explore/ModeApp.java:40`; other modes use their own port).
2. In a headless browser that ignores certificate errors (the cert is self-signed), open `https://127.0.0.1:8446/`. It serves the same page as `/device-view` (`ModeApp.java:24-26`).
3. Force a state with `document.getElementById('rig').className='s-eyes-only'`. The poll only rewrites the class when the published state changes (`ExploreState.java:141`), so a forced class stays until the brain's state changes.
4. Read the computed style of a core:
   ```js
   const c = getComputedStyle(document.querySelector('.glow-core'));
   [c.animationName, c.transform, c.opacity]
   ```
   Expected results: eyes-only gives `none`, `matrix(1, 0, 0, 0.45, 0, 0)`, `0.7`. Flinch gives `flinch` (read it within 0.55 s of setting the class). Resting gives `drowse`. Idle gives `blink`. A computed `blink` in a non-idle state means the look lost the cascade.
5. Take a screenshot as well. Computed style can be right while the look still reads wrong on the lens.

**Regression test pattern.** Test the precondition and the override together, so the test fails if either side changes (`scripts/tests/test_explore_state_page.py:61-70`):

```python
def test_animated_looks_override_the_inline_blink(self):
    self.assertIn("cores[g].style.animation=", self.page)
    for state in ("flinch", "eyes-only", "resting"):
        rule = re.search(r"#rig\.s-" + re.escape(state) + r" \.glow-core\{([^}]*)\}", self.page)
        self.assertIsNotNone(rule, state)
        self.assertRegex(rule.group(1), r"animation:[^;]*!important", state)
```

The first assertion ties the test to the cause: if `EyesPage` ever stops setting the blink inline, the test fails, and the `!important` becomes something to revisit rather than something kept out of habit. A new mode that adds `.glow-core` looks should copy this test for its own states. Host tests still can't evaluate the cascade, so the browser check above is the real acceptance step.

## Related Issues

- `docs/solutions/integration-issues/soundpool-plays-silently-on-miko3-use-mediaplayer.md`: the other device-only surprise found while building the same mode (explore's startle chirp).
- `docs/plans/2026-09-22-1438-feat-explore-mode-plan.md`: the explore mode plan (KTD10 covers the eyes).
- PR #4 (benwilson/miko3-jailbreak): where this was found and fixed.
- Follow-up: the `EyesPage` class javadoc says callers restyle the eyes "by class on #rig" without mentioning the inline blink on `.glow-core`. A one-line pointer to this doc there would stop the next mode from repeating this.
