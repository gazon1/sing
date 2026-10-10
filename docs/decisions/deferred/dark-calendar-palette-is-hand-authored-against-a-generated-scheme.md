---
title: "Dark Calendar Palette Is Hand Authored Against A Generated Scheme"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "calendar-palette-follows-the-theme"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** [#197](https://github.com/gazon1/sing/issues/197)

**OpenSpec change:** `openspec/changes/calendar-palette-follows-the-theme/`
(capability `app-theming`, REQ-THEME-001/002/003)

**Found in:** 2026-10-05, while replacing `SingularityTheme`'s hand-written
palettes with MaterialKolor seed generation (ADR
`2026-10-05-materialkolor-seed-palette-and-resolved-dark-flag`).

**Situation.** `CalendarPalette` has sixteen fields, and
`darkCalendarPalette` fills all sixteen with hex literals — `0xFF0B1220`
background, `0xFF101A2C` surface, `0xFF1C2740` grid lines, `0xFFE7ECF5` text.
It was tuned against a *previous* dark theme (`0xFF121212` / `0xFF1E1E1E`),
and the light twin already derives from `MaterialTheme.colorScheme`. The two
halves of the same data class now have different provenance, and neither is
checked against the seed-generated scheme the app actually ships.

Three of the sixteen fields — `taskSelected`, `todayBadge`, `nowIndicator`,
`accent` — are set to `accent.color`, the **raw seed**, in both palettes. The
seed is a vivid user-picked hue; it is not a role colour. With
`SingularityAccents.Yellow` (`0xFFFFEB3B`) those are near-illegible as a
surface on the light background. MaterialKolor makes the fix free
(`scheme.primary` is tone-adjusted and contrast-checked against `onPrimary`),
but it changes the calendar's look, so it is a design call rather than a bug
fix and was not made unilaterally.

**Why it was not fixed here.** The dark palette encodes a deliberate aesthetic
("tuned for the deep navy/blue-grey aesthetic of the reference screenshots").
Re-deriving it is a visual-design decision with no single correct answer, and
this change was scoped to making the seed actually drive the theme. Bundling a
redesign into a bug fix is how a review loses the ability to see the bug fix.

**Checks already performed.** Confirmed all sixteen fields are literals in the
dark branch and scheme-derived in the light branch; confirmed the seed-derived
slots are identical expressions in both branches; confirmed no test asserts any
`CalendarPalette` value, so there is no behavioural guard to update.

**Try next:** re-derive `darkCalendarPalette` from `MaterialTheme.colorScheme`
exactly as `lightCalendarPalette` already is, then replace the three raw-seed
slots with `scheme.primary` / `onPrimary`. Do it as its own change with
before/after screenshots on all nine accents, and check the yellow and orange
accents first — they are the ones that fail contrast. Consider collapsing
`CalendarPalette` itself: if both branches end up derived from the scheme, the
sixteen-field data class may be redundant with `ColorScheme` and the screen
could read scheme roles directly.

---
