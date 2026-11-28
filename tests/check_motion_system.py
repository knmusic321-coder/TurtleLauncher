#!/usr/bin/env python3
"""Guard the v15 motion rework and its two frame-health switches.

The launcher had no shared motion values: every call site picked its own duration and
interpolator, the RecyclerView animator allocated a fresh interpolator per row and added an
unbounded per-index delay, and the press-feedback touch listener called performClick() on top
of the framework's own click, so every tap fired twice.

This check keeps the replacement honest:

  * one MotionSpec for durations and easing, all UI durations under 300ms;
  * the item animator uses it, has no overshoot and no rotation, and caps its stagger;
  * press feedback no longer fires the click itself;
  * Appearance.duration applies the MotionGovernor factor, so measured jank reaches every
    duration instead of only the ones that remember to ask;
  * the two new switches exist in AllSettings, are bound by their fragments, are rendered in
    the right settings page, are translated, and are reachable from the AI catalogue.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
main = root / "TurtleLauncher/src/main/java"
res = root / "TurtleLauncher/src/main/res"

anim = main / "com/endiq/turtlelauncher/utils/anim"


def read(path: Path) -> str:
    return path.read_text()


# ── MotionSpec: one source of truth, short durations, strong curves ──────────────
spec = read(anim / "MotionSpec.kt")
assert "PathInterpolator(0.23f, 1f, 0.32f, 1f)" in spec, "missing the strong ease-out curve"
assert "PathInterpolator(0.77f, 0f, 0.175f, 1f)" in spec, "missing the on-screen movement curve"
assert "fun ms(baseMs: Long): Long = Appearance.duration(baseMs)" in spec, \
    "every spec duration must go through the motion budget"

for name in ("PRESS_MS", "RELEASE_MS", "FADE_MS", "ITEM_ADD_MS", "ITEM_REMOVE_MS",
             "ITEM_MOVE_MS", "ITEM_CHANGE_MS"):
    line = next(l for l in spec.splitlines() if f"const val {name} = " in l)
    value = int(line.split("=")[1].strip().rstrip("L"))
    assert value < 300, f"{name}={value}ms is over the 300ms UI ceiling"

assert "MAX_STAGGERED_ROWS" in spec, "list stagger needs a cap"

# ── Item animator: quiet, GPU-only, capped stagger ───────────────────────────────
item = read(anim / "TurtleItemAnimator.kt")
for banned in ("OvershootInterpolator", "DecelerateInterpolator", ".rotation(", "index * 22L"):
    assert banned not in item, f"item animator still uses {banned}"
for curve in ("MotionSpec.EASE_OUT", "MotionSpec.EASE_IN_OUT", "MotionSpec.MAX_STAGGERED_ROWS"):
    assert curve in item, f"item animator does not use {curve}"
assert "MotionSpec.ms(MotionSpec.ITEM_ADD_MS)" in item, \
    "the add duration must be re-read per run, not frozen in init"

# ── Press feedback: no double click, asymmetric timing ──────────────────────────
transitions = read(anim / "TurtleTransitions.kt")
start = transitions.index("fun attachPressFeedback(")
body = transitions[start:transitions.index("private fun pressTo(", start)]
assert ".performClick()" not in body, \
    "the touch listener must not performClick on top of the framework's own click"
assert "pressTo(" in body
press = transitions[transitions.index("private fun pressTo("):]
press = press[:press.index("\n    }")]
assert "MotionSpec.PRESS_MS" in press and "MotionSpec.RELEASE_MS" in press, \
    "press and release need their own durations"
assert "MotionSpec.PRESS_IN" in press and "MotionSpec.EASE_OUT" in press
assert "delay = 0.12f" not in transitions, "list rows must not wait 120ms per child"

# ── Fades: interruptible and eased ──────────────────────────────────────────────
fades = read(anim / "AnimUtils.kt")
assert "view.animate().cancel()" in fades, "a re-targeted fade must cancel the one in flight"
assert "MotionSpec.EASE_OUT" in fades and "MotionSpec.ms(" in fades

# ── Governor: measured, gated, wired to the activity lifecycle ───────────────────
governor = read(anim / "MotionGovernor.kt")
assert "Choreographer.getInstance().postFrameCallback" in governor, "no frame sampling"
assert "Process.THREAD_PRIORITY_DISPLAY" in governor, "no display priority"
assert "Process.THREAD_PRIORITY_DEFAULT" in governor, "the priority is never handed back"
assert "AllSettings.adaptiveMotion.getValue()" in governor
assert "AllSettings.uiThreadPriority.getValue()" in governor
assert "JANK_FACTOR = 0.5f" in governor, "adaptive motion must shorten, not disable"

appearance = read(main / "com/endiq/turtlelauncher/feature/theme/Appearance.kt")
assert "MotionGovernor.factor()" in appearance, \
    "measured jank has to scale every duration, not just new call sites"
assert "baseMs * scale" in appearance

activity = read(main / "com/endiq/turtlelauncher/ui/activity/BaseActivity.java")
assert "MotionGovernor.onResume()" in activity
assert "MotionGovernor.onPause()" in activity

# ── The two switches: setting, page, binding, translation ───────────────────────
settings = read(main / "com/endiq/turtlelauncher/setting/AllSettings.kt")
assert 'BooleanSettingUnit("adaptiveMotion", true)' in settings
assert 'BooleanSettingUnit("uiThreadPriority", true)' in settings

optimization_layout = read(res / "layout/settings_fragment_optimization.xml")
assert '@+id/adaptiveMotion_layout' in optimization_layout
assert "@string/setting_adaptive_motion_title" in optimization_layout
phone_layout = read(res / "layout/settings_fragment_phone.xml")
assert '@+id/uiThreadPriority_layout' in phone_layout
assert "@string/setting_ui_thread_priority_title" in phone_layout

optimization_fragment = read(
    main / "com/endiq/turtlelauncher/ui/fragment/settings/OptimizationSettingsFragment.kt")
assert "AllSettings.adaptiveMotion" in optimization_fragment
assert "MotionGovernor.refresh()" in optimization_fragment
phone_fragment = read(
    main / "com/endiq/turtlelauncher/ui/fragment/settings/PhoneSettingsFragment.kt")
assert "AllSettings.uiThreadPriority" in phone_fragment
assert "MotionGovernor.refresh()" in phone_fragment

english = read(res / "values/strings.xml")
vietnamese = read(res / "values-vi/strings.xml")
for key in ("setting_adaptive_motion_title", "setting_adaptive_motion_desc",
            "setting_ui_thread_priority_title", "setting_ui_thread_priority_desc"):
    assert f'name="{key}"' in english, f"missing English string {key}"
    assert f'name="{key}"' in vietnamese, f"missing Vietnamese string {key}"

actions = read(main / "com/endiq/turtlelauncher/feature/ai/TurtleAiActions.kt")
assert '"adaptive_motion"' in actions and "AllSettings.adaptiveMotion" in actions, \
    "the assistant catalogue must know the new optimization switch"
assert '"ui_thread_priority"' in actions and "AllSettings.uiThreadPriority" in actions, \
    "the assistant catalogue must know the new phone switch"

print("Motion system OK: shared spec, capped stagger, no double click, adaptive motion + UI priority wired")
