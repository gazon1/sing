---
title: "Material icons have no auto-mirrored chevron, so a directional chevron is KeyboardArrowLeft/Right"
date: 2026-11-09
status: accepted
tags: [ui, icons, rtl, accessibility]
---

# Material icons have no auto-mirrored chevron, so a directional chevron is KeyboardArrowLeft/Right

## Context

Issue #1669 asks for the directional icons of the app to be auto-mirrored, so that a
right-to-left layout draws a "next" chevron pointing the other way. The change that
implemented it wrote `Icons.AutoMirrored.Filled.ChevronLeft` and
`Icons.AutoMirrored.Filled.ChevronRight`.

Those symbols do not exist. The `AutoMirrored` set of the Material icon set contains
no `Chevron` at all: `material-icons-core` ships seven auto-mirrored symbols
(`ArrowBack`, `ArrowForward`, `ExitToApp`, `KeyboardArrowLeft`, `KeyboardArrowRight`,
`List`, `Send`) and `material-icons-extended` ships 139, none of them a chevron. That
is true of the JetBrains port this repository pins (`1.7.3`, the newest release of
the artifact) and of the upstream AndroidX release it is a port of (`1.7.8`, the
newest release of the artifact). A grep of the icon set finds the answer; the
compiler finds it too, as an unresolved reference at every call site.

## Idea

A directional chevron in this app is an auto-mirrored symbol that exists in the
pinned icon set and is drawn as a chevron.

## Decision

`Icons.AutoMirrored.Filled.KeyboardArrowLeft` and
`Icons.AutoMirrored.Filled.KeyboardArrowRight`.

Their vector is the chevron angle — `moveTo(8.59, 16.59) → lineTo(13.17, 12) →
lineTo(8.59, 7.41) → lineTo(10, 6) → lineToRelative(6, 6) → …` — and they are
declared with `autoMirror = true`, so Compose flips them in a right-to-left layout
without the call site doing anything. They live in `material-icons-core`, which
`material3` already brings in, so nothing new is added to the dependency graph. The
`KeyboardArrow` family is already the house style for the state buttons of the agenda
section editor (`Icons.Default.KeyboardArrowUp` / `KeyboardArrowDown`).

## Rationale

The two alternatives were rejected:

- `Icons.AutoMirrored.Filled.ArrowLeft` / `ArrowRight` exist and are auto-mirrored,
  but they are arrows with a shaft, not chevrons: the shape of every row affordance
  in the app would change.
- Flipping `Icons.Default.ChevronRight` by hand (`graphicsLayer { scaleX = -1f }` in a
  right-to-left layout) keeps the exact glyph, but every call site has to remember
  the flip, and the flip cannot be carried by an `ImageVector` — so a registry of
  icons, which the project has and intends to grow, could not hold it.

`KeyboardArrowLeft` / `KeyboardArrowRight` is the only option that is a chevron, is
auto-mirrored by the framework, and needs no code at the call site.

## Consequences

- The chevron is drawn slightly heavier than Material's `ChevronRight`: the stroke is
  about 2 dp against about 1.4 dp. The shape and the direction are the same.
- A future release of the icon set that adds an auto-mirrored `Chevron` can swap the
  symbol back; the call sites name the direction, not the glyph.
- The icon set, not the code, is the limit here. A directional glyph that the set does
  not carry has to be drawn by the app, and a hand-drawn glyph does not flip itself.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/SettingsSection.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/components/sheet/MultiDatePickerSheet.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar/presentation/components/calendar/CalendarTopBar.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/components/ProjectTreeSection.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/screen/ProjectDetailBody.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/screen/ProjectDetailHero.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/components/SectionEditorCard.kt`
