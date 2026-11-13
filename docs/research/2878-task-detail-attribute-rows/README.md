# Task Detail attribute rows — before and after

Evidence for issue [#2878](https://github.com/gazon1/sing/issues/2878): the Task
Detail's attribute rows read on semantic surfaces, with readable foreground roles.

## How these were taken

| | |
|---|---|
| Device | `0O65321F32204DD5` (Realme RMX3938, Android 15, 720×1600 px, density 320 → 1dp = 2px) |
| App | `com.singularity.todo`, debug build |
| "before" | base commit `b37a9d1c0` (the commit the branch was cut from) |
| "after" | branch `gazon1/ui-task-detail-establish-accessible-visual-found` |
| Method | `adb shell screencap -p`, WebP (the repository ignores `*.png`) |
| Task | one task, title `Task-detail-visual-check`, priority **High** |
| Theme / accent | as named in each file name; the app's own settings were switched between captures |

Both sets were taken from the **same installed app data** (the same task, the same
theme and accent per pair), and each pair was captured without changing anything
else on the screen — the pairs differ by 2–3% of pixels, which is the changed
region and nothing more.

## The pairs

| File | What it shows |
|---|---|
| `02-priority-sheet-light` | The priority sheet, blue accent, light theme |
| `03-detail-high-light` | The detail's top, blue accent, light theme |
| `04-subtasks-light` | The subtasks section, blue accent, light theme |
| `05-detail-high-dark` | The detail's top, blue accent, dark theme |
| `06-priority-sheet-dark` | The priority sheet, blue accent, dark theme |
| `07-subtasks-dark` | The subtasks section, blue accent, dark theme |
| `08-detail-high-teal-light` | The detail's top, teal accent, light theme |
| `09-priority-sheet-teal-light` | The priority sheet, teal accent, light theme |

## What the pairs show

**The priority level is no longer carried by colour alone.** In the "before" sheet
every level wore a raw priority hue; measured on the sheet's own background
(`#F2F3FA`) they run from **1.43:1 (Medium) to 3.15:1 (High)** — below the 3:1 that
a non-text graphic needs, and they are the only thing that told the levels apart.
In the "after" sheet each level has its own glyph, and the colour says one thing
only: whether the level is an exception (the error role for High and Urgent,
ordinary text colour for Low and Medium, the muted role for None).

**The default level no longer looks like an alarm.** In the "before" detail the set
priority was painted in the accent (`#003862`, the blue) whatever the level was;
in the "after" detail a High priority is painted in the error role
(`#740006`), and Medium — the level a task has by default — wears the ordinary
text colour.

**The subtasks header is an icon now.** In the "before" subtasks section the
header carried a literal emoji: a block of fully saturated pixels at the header's
left edge. In the "after" section the same place holds a monochrome vector icon
tinted with the theme, and the saturated block is gone.

**The rows share one rhythm.** The "after" rows are 48dp tall at minimum and take
their type from the typography slots, so the text scale reaches them; the
"before" rows used literal `sp` sizes.

## What is not in these files

These are ad-hoc captures, not a screenshot suite: the repository has no
screenshot framework (ADR `2026-09-30-roborazzi-not-built-superseded.md`), and
building one is issue #2887. Nothing here is asserted by a test — the colour
numbers are pinned by `TaskDetailContrastTest` (all eight accents, light and
dark) and the type-scaling numbers by `TaskDetailFontScaleTest`.

## When they were taken

The captures were taken before `main` merged #2890 (the coherent scheduling
surface), which adds reminder and deadline rows under the due date. The screens
here therefore show the attribute rows — priority, due date, subtasks — and not
those two rows. The branch was rebased onto `main` after the captures; the
attribute-row code they document is unchanged by that rebase, and the numbers
quoted above were measured on the captured builds, not on the rebased tree.
