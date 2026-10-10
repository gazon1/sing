---
title: "Tasks Feature Pins Its Own Dark Palette And Ignores The Theme Entirely"
date: 2000-01-01
status: CLOSED
tags: ["deferred", "tasks-tokens-follows-the-theme"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** [#198](https://github.com/gazon1/sing/issues/198)

**OpenSpec change:** `openspec/changes/tasks-tokens-follows-the-theme/`
(capability `app-theming`, REQ-THEME-004/005/006)

**Status update 2026-10-05: PARTIALLY CLOSED.** The two token objects are gone
and all 27 literals now live in `theme/TaskSemanticColors.kt`; 19 consumer files
read the active scheme. What remains is not this entry but two follow-ups found
while doing it: #199 (the other four screens that carry the same defect, which
this entry's "counted 47 literals" figure under-reported because it was scoped to
`feature/*/presentation` and three of those files sit outside that segment), and
the duplicate `priorityColor` noted below.

**Found in:** 2026-10-05, immediately after the MaterialKolor seed-palette
change (ADR `2026-10-05-materialkolor-seed-palette-and-resolved-dark-flag`),
while auditing what else hardcodes colour now that the app *has* a generated
palette to read from.

**Situation.** The Tasks feature — the core of the app — has two token objects
that are **dark-only by construction**, with no light branch anywhere:

| Object | File | Literals | Anchor |
|---|---|---|---|
| `TaskColors` | `feature/tasks/presentation/theme/TaskTheme.kt` | 12 | `Background = 0xFF0F1115`, `TextPrimary = 0xFFE2E4E9` |
| `TaskListColors` | `feature/tasks/presentation/theme/TaskListTokens.kt` | 15 | `Background = 0xFF0B0E14`, `Surface = 0xFF161A22` |

Both are `object`s of `val`s, not functions of a `ColorScheme`, so they cannot
respond to anything at runtime. They are consumed by **11 production files**:
the task list, the whole detail/editor surface (`TaskEditorContent`,
`TaskTitleRow`, `TaskEditorDueDateRow`, `TaskEditorEstimateRow`,
`TaskEditorPriorityRow`, `TaskDescriptionField`, `TaskAttributeCard`,
`LinkedBacklinksCard`, `LogbookSection`), `PriorityMeta`, and
`TimeTrackingSection` in the timetracking feature.

Consequences, all pre-existing:

1. **A light-theme user gets a dark task screen.** `darkTheme` defaults to
   `false`, so this is the shipped default, on the app's primary screen.
2. **The accent picker does nothing here.** `AccentBlue = 0xFF4A90E2` is a
   literal, so choosing Pink leaves the task editor blue. This is the same
   defect class the seed-palette change just fixed for the app at large.
3. The KDoc on `TaskListTokens.kt` claims it gives "один источник правды при
   смене темы" — one source of truth *when the theme changes*. It is the
   opposite: a single hardcoded truth that cannot change with the theme.

**Why it was not fixed here.** Thirteen token files' worth of colour, across
the most-used surface in the app, is a redesign with nine accents × two modes
to review by eye — and there is no screenshot baseline in this repository to
catch a mistake. Attempting it inside a change that was scoped to the theme
root would also have made that change unreviewable. This needs visual review
per accent, which is a human-in-the-loop task.

**Checks already performed.** Counted every `Color(0x…)` literal under
`feature/*/presentation` (47 total, in 5 files: the two token objects, the
14-literal `CalendarPalette`, `PriorityChip`'s four priority hues, and two in
`NotesListScreen`). Confirmed neither token object is a function or reads
`MaterialTheme`/`LocalAccentColor`/`LocalIsDarkTheme`. Enumerated all 11
consumers. Confirmed there is no light-mode counterpart object.

**Try next:** convert both objects into functions of `MaterialTheme.colorScheme`
returning a small token data class, resolved inside a `CompositionLocalProvider`
at the screen root — the same shape `CalendarPalette` already uses. Keep the
four priority hues as literals: priority is a *semantic* scale, not a theme
role, and `PriorityChip`'s green/amber/red/pink is correct as data. Then verify
by eye across all nine accents in both modes, starting with the light theme on
the task list, because that is the largest visible delta in the app.

A Konsist rule now exists and is live in `ArchitectureTest`
(`colour literals live in theme files, not in feature code`), validated with a
negative control: a literal injected into a non-theme feature file makes it fail.
Its scope is the **whole `feature/` tree**, not `feature/*/presentation` — three
screens keep their composables directly under `feature/<x>/`, so the narrower
scope would have passed while 22 literals sat outside it. The allowlist that
keeps it green names six files; #199 tracks the four that still need converting.

---
