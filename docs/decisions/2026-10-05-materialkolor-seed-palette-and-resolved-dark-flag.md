---
title: The theme palette is generated from a seed, and the resolved dark flag is published
date: 2026-10-05
status: accepted
tags: [ui, theme, compose, architecture, contract]
---

## Context

`SingularityTheme` took a user-selected `accent` and then ignored it when
building the palette. Two module-level constants decided the entire color
system:

```kotlin
private val DarkColorScheme = darkColorScheme(primary = Color(0xFF90CAF9), …)
private val LightColorScheme = lightColorScheme(primary = Color(0xFF1976D2), …)
```

`accent` was only forwarded into `LocalAccentColor`. So the picker offered nine
accents and the app rendered the same blue in all nine cases, except on the
calendar screen, which read the enum's raw `color` directly. Nine settings, one
visual outcome — a setting that cannot be observed.

`MaterialKolor` was already in the version catalog (`materialkolor = "4.1.1"`)
and already on the `commonMain` classpath
(`shared/build.gradle.kts:131`). Nothing imported it. This is the
"unwired surface" defect that `AGENTS.md` names as the project's most common
one: fully vendored, fully paid for, called by nobody. It had survived
`find-unwired-surfaces.py`, which counts *symbols*, not declared dependencies —
a dependency has no symbol to count until something references it.

## Decision

**1. The palette is generated, not authored.**
`SingularityTheme` derives the whole `ColorScheme` from `accent.color` as a seed
color. The two hand-written schemes are deleted. Light and dark stop being two
artifacts to keep in sync and become one argument (`isDark`).

MaterialKolor is pinned to `5.0.1`. The version choice is not a guess: its POM
declares `org.jetbrains.compose.material3:material3:1.12.0-alpha03` and
`org.jetbrains.compose.runtime:runtime:1.12.0`, which are this project's exact
coordinates. The expected CMP-skew risk that normally accompanies a
third-party theme library does not exist here — there is no skew to resolve.

**2. The resolved dark flag is published, not re-derived.**
`LocalIsDarkTheme` is provided by `SingularityTheme` from its own `darkTheme`
parameter. This fixes a live bug (below) and establishes the rule: **theme-aware
subtrees read the theme's own resolution; they never call
`isSystemInDarkTheme()` themselves.**

## The bug that fix #2 closes

`ProvideCalendarPalette` selected its palette with:

```kotlin
val isDark = isSystemInDarkTheme()   // …then darkCalendarPalette(…)
```

But this app's dark mode is a **persisted user setting**, not a system
read-through: `SettingsBundle.Appearance.darkTheme` is a plain `Boolean`
defaulting to `false` (`SettingsDefaults.Appearance.DARK_THEME`). There is no
"follow system" tri-state. `SingularityTheme` receives that setting as a
parameter; the calendar ignored the parameter and asked the OS instead.

Consequence, on the default configuration: a user whose phone is in **system
dark mode** and who has not touched the appearance setting gets a **light** app
everywhere — and a calendar screen painted with the hardcoded navy dark palette
(`background = 0xFF0B1220`, `textPrimary = 0xFFE7ECF5`). Light text on navy,
inside a light application, across seven composables
(`CalendarTopBar`, `MiniCalendarPanel`, `MonthGridView`, `TaskChip`,
`TimeGridView`, `ViewModeDropdown`, `CalendarScreen`).

Not an edge case: it is the *intersection of the shipped default and a common
device state*, so it reproduces on first launch for a large share of users. And
it is invisible to review, because the two sources of truth look interchangeable
and both are named `dark`.

`darkCalendarPalette` also accepted a `scheme: ColorScheme` parameter and never
read it — every field was a hex literal. The parameter was removed rather than
left as a lie about where the palette comes from.

## Why publish the flag instead of threading a parameter

`ProvideCalendarPalette` is called deep inside `CalendarScreen`; threading
`isDark` from the theme through the navigation graph to reach it would add a
parameter to two or three signatures to carry one boolean that the theme has
already decided. The repository's existing idiom for exactly this is a
`CompositionLocal` beside the value it accompanies (`LocalAccentColor`,
`LocalHaptic`, `LocalCalendarPalette`), so the flag joins them rather than
inventing a second mechanism.

This also means the bug is now *structurally* harder to reintroduce: the
authoritative value is provided at the root, and the incorrect alternative has
to be reached for deliberately.

## Consequences

- Nine accents now do nine visibly different things.
- Deleting an accent, or adding one, is a change to one enum entry. No palette
  maintenance.
- The generated light and dark `background`/`surface` differ from the previous
  hand-picked values. This is a **visible change on first launch** for every
  user, because MaterialKolor's tonal surfaces replace `0xFFFFFBFE` and
  `0xFF121212`.

  Checked before claiming a blast radius: this repository has **no screenshot
  baselines and no snapshot tests** (`find -iname "*screenshot*"` is empty;
  Roborazzi is not adopted). So nothing is stale and nothing needs re-capturing
  — but that is also why no automated gate can catch a palette regression. The
  only verification available is a human looking at the app, which is why the
  visual check below is not optional.
- The dark calendar palette remains hand-authored navy and is now, for the first
  time, reachable through the correct branch — so it becomes *visible* in the
  case it was written for. It has therefore never actually been reviewed
  against the generated dark scheme. Tracked in the deferred backlog rather
  than changed here, because aligning it is a design decision, not a bug fix.
- `LocalIsDarkTheme` defaults to `false`. A preview that composes calendar
  content without `SingularityTheme` gets the light palette, which matches the
  previous `isSystemInDarkTheme()` behaviour for a light-themed host and is a
  safe default.

## The regression guard

`ArchitectureTest` gained `only the theme itself reads isSystemInDarkTheme`,
keyed on a one-entry allowlist (`DARK_THEME_READER_ALLOWLIST` =
`SingularityTheme.kt`). It matches on `codeOnly()` so a KDoc *mention* of the
name does not count as a call.

The bug is a plausible refactor in both directions — "simplify, just ask the
system" reads as harmless, and nobody edits this file expecting a rendering
regression. A comment at the call site does not survive that, because the
person who breaks it never sees the calendar screenshot. The rule turns the
invariant into something the build enforces.

It was validated with a negative control rather than by assertion: the bug was
reintroduced at `CalendarTheme.kt:50`, the suite was re-run, and the guard
failed (`12 tests completed, 1 failed`). A guard that has only ever been seen
green is not evidence of anything.

Note for whoever runs this next: `ArchitectureTest` is `@Tag("slow")`, so a
plain `./gradlew :shared:jvmTest` does not execute it. The full set needs
`-Ptest.tags=fast,slow`. A bare run reports `BUILD SUCCESSFUL` while skipping
every Konsist boundary — the green is real and the coverage is not.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/theme/SingularityTheme.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar/presentation/theme/CalendarTheme.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/settings/SettingsBundle.kt` — `darkTheme`
- `gradle/libs.versions.toml` — `materialkolor = "5.0.1"`
- Deferred backlog: `dark-calendar-palette-is-hand-authored-against-a-generated-scheme`
