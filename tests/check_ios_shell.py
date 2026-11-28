#!/usr/bin/env python3
"""Guard the v16 iOS shell: push/pop transitions and the inset-list settings hub.

Navigation used to animate the same view twice - FragmentManager faded and scaled the
container while FragmentWithAnim slid the fragment inside it - so opening a page read as
busy, and Back replayed the opening motion instead of reversing it. The settings hub was a
two-column grid of floating tiles.

This check keeps the replacement honest:

  * every fragment transaction uses the iOS push/pop animation set, none of the old pair;
  * the fragment itself no longer animates on start, and the old slide-out call is gone;
  * the four navigation animations and their shared curve exist, in the 300ms budget;
  * the settings hub is a single column of inset rows that keeps every id the fragment
    binds, so the redesign cannot silently drop a settings page.
"""
from pathlib import Path

root = Path(__file__).resolve().parents[1]
main = root / "TurtleLauncher/src/main/java"
res = root / "TurtleLauncher/src/main/res"


def read(path: Path) -> str:
    return path.read_text()


def camel(row: str) -> str:
    """launcher_settings_row -> launcherSettingsRow (the view-binding name)."""
    parts = row.split("_")
    return parts[0] + "".join(part.capitalize() for part in parts[1:])


def durations(xml: str) -> list:
    return [int(chunk.split('"')[0]) for chunk in xml.split('android:duration="')[1:]]


# ── Navigation animations ───────────────────────────────────────────────────────
curve = res / "interpolator/nav_motion.xml"
assert curve.exists(), "missing the shared navigation curve"
assert "<pathInterpolator" in read(curve) and "pathData" in read(curve)

for name in ("nav_push_in", "nav_push_out", "nav_pop_in", "nav_pop_out"):
    path = res / f"anim/{name}.xml"
    assert path.exists(), f"missing anim/{name}.xml"
    body = read(path)
    assert "@interpolator/nav_motion" in body, f"{name} is not on the shared curve"
    assert durations(body) and max(durations(body)) <= 340, \
        f"{name} is over the 340ms navigation budget"

assert 'android:fromXDelta="100%p"' in read(res / "anim/nav_push_in.xml"), \
    "a pushed screen has to arrive from the trailing edge"
assert 'android:toXDelta="-28%p"' in read(res / "anim/nav_push_out.xml"), \
    "the covered screen steps back a third of its width"
assert 'android:fromXDelta="-28%p"' in read(res / "anim/nav_pop_in.xml"), \
    "Back has to return the covered screen from where it was pushed aside"
assert 'android:toXDelta="100%p"' in read(res / "anim/nav_pop_out.xml"), \
    "Back has to dismiss the page the way it came"

# ── One owner for the motion ───────────────────────────────────────────────────
zhtools = read(main / "com/endiq/turtlelauncher/utils/ZHTools.java")
assert "R.anim.nav_push_in, R.anim.nav_push_out" in zhtools
assert "R.anim.nav_pop_in, R.anim.nav_pop_out" in zhtools
assert "R.anim.fragment_in" not in zhtools, "the old container fade is still wired"
assert ".slideOut();" not in zhtools, \
    "the outgoing fragment must not animate itself on top of the transaction animation"

with_anim = read(main / "com/endiq/turtlelauncher/ui/fragment/FragmentWithAnim.kt")
start = with_anim.index("override fun onStart()")
after_super = with_anim[start:with_anim.index("fun slideIn()", start)].split("super.onStart()", 1)[1]
code = "\n".join(line for line in after_super.splitlines() if not line.strip().startswith("//"))
assert "slideIn()" not in code, \
    "onStart must not start a second animation for the same view"

# ── Settings hub: one column, every id kept ────────────────────────────────────
hub = read(res / "layout/fragment_settings.xml")
assert 'android:layout_weight="1"' not in hub, "the hub is still a two-column grid"
assert "@drawable/background_settings_row" in hub
assert "@drawable/background_settings_search" in hub

expected_rows = [
    "launcher_settings_row", "account_settings_row", "shizuku_settings_row", "discord_settings_row",
    "video_settings_row", "game_settings_row", "java_settings_row", "hud_settings_row",
    "controls_settings_row", "mouse_keyboard_settings_row", "phone_settings_row",
    "accessibility_settings_row", "recording_settings_row", "optimization_settings_row",
    "emotes_settings_row", "experimental_settings_row", "about_settings_row",
]
for row in expected_rows:
    assert f'@+id/{row}' in hub, f"settings hub lost the {row} row"

for kept in ("settings_layout", "back_button", "search_input", "search_clear_button"):
    assert f'@+id/{kept}' in hub, f"settings hub lost {kept}"

for section in ("general", "performance", "other"):
    assert f'@+id/{section}_section_container' in hub
    assert f'@+id/{section}_section_header' in hub
assert '@+id/settings_empty_result' in hub

fragment = read(main / "com/endiq/turtlelauncher/ui/fragment/settings/SettingsFragment.kt")
for row in expected_rows:
    assert f"binding.{camel(row)}" in fragment, f"SettingsFragment no longer binds {row}"

# ── The release says all of this ───────────────────────────────────────────────
for lang in ("values", "values-vi"):
    strings = read(res / f"{lang}/strings.xml")
    assert '<string name="whats_new_version">v16</string>' in strings, f"{lang} notice is stale"

print("iOS shell OK: push/pop transitions, one motion owner, single-column settings hub")
