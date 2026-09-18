---
title: "Add @Preview to all screens and widgets via shared PreviewSamples"
date: 2026-09-06
tags: [compose, preview, ui]
status: accepted
---

## Context

Greenfield for `@Preview` annotations — zero previews existed. The codebase has
73+ Composables (23 screens, ~50 reusable widgets) that need visual QA in the
IDE without running an emulator or device. The project is Kotlin Multiplatform
(Android + JVM Desktop) using `composeMultiplatform 1.12.0` (Kotlin 2.3.21).

## Idea

Three alternatives were weighed:

1. **AndroidX `@Preview` (`androidx.compose.ui.tooling.preview.Preview`)** — available
   in Android Studio natively; JetBrains multiplatform artifact (`org.jetbrains.compose.ui:ui-tooling-preview`)
   also on classpath but doesn't resolve for Android target compilation in this
   project setup.
2. **JetBrains `@Preview` (`org.jetbrains.compose.ui.tooling.preview.Preview`)** —
   theoretically the "correct" KMP annotation, but fails compilation in this
   project because the JetBrains artifact is not transitively visible to the
   Android compile task.
3. **No previews** — skip the feature entirely.

Decision: use **AndroidX `@Preview`** (option 1). It compiles reliably in both
Android and desktop targets, is fully supported by Android Studio, and IntelliJ
IDEA renders it in gutter preview for desktop modules too.

## Decision

Added `@Preview` annotations to all screens and reusable widgets using a
shared `core/ui/preview/PreviewSamples.kt` file:

- `PreviewThemed(darkTheme, accent, useSurface)` — theme wrapper composable
- `PreviewSamples` object — sample domain model builders (`task()`, `project()`,
  `tag()`, `note()`, `reminder()`, `attachment()`, `checklistItem()`) using
  epoch-0 `Instant` and a fixed `LocalDate(2026, 9, 6)` to avoid `Clock.System`
  availability issues across KMP targets
- 2–3 preview variants per component: Light, Dark, Purple-dark (accent sanity-check)

Annotation used: `@androidx.compose.ui.tooling.preview.Preview` (fully qualified to
avoid ambiguity; short import added in each file).

Layout: all preview code inline at the **end of each existing file**, preceded by
`// ===== Preview =====`. No separate `*Previews.kt` files. Shared data builders
in one `PreviewSamples.kt`.

## Rationale

- **AndroidX preview works in both targets** — AndroidX compose libraries are
  available in both Android and desktop (via compose-multiplatform transitive deps).
- **`Clock.System` unavailable in some KMP targets** — sample data uses
  `kotlinx.datetime.Instant.fromEpochMilliseconds(0)` and fixed date instead.
- **`org.jetbrains.compose.ui.tooling.preview.Preview` unresolved** — JetBrains
  artifact not visible to Android compile task in this project's dependency
  configuration. AndroidX is a reliable fallback.
- **In-file preview layout** — minimal file changes, co-located with the
  component it previews, easy to find via IDE structure pane.
- **Shared `PreviewSamples`** — DRY sample data, single place to update if
  domain models change.

## Consequences

- `@Preview` annotation is `@androidx.compose.ui.tooling.preview.Preview` —
  always use the fully qualified form or add explicit import; never use
  `org.jetbrains.compose.ui.tooling.preview.Preview` (unresolved in Android target).
- `Clock.System.now()` must not appear in preview code — use
  `kotlinx.datetime.Instant.fromEpochMilliseconds(0)` or a fixed epoch instead.
- Preview functions are `private` and placed at the end of the source file,
  preceded by `// ===== Preview =====`.
- `PreviewParameterProvider` is avoided — individual preview functions used instead
  (simpler, no additional tooling dependency).
- `useSurface = false` when the preview root already contains a `Scaffold`
  (the Scaffold provides its own `MaterialTheme.colorScheme.background`).

## Links

- New file: `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/preview/PreviewSamples.kt`
- Preview sections added to 42 files (12 core/ui/components, 9 feature widgets,
  21 screens)
- Build verified: `./gradlew :androidApp:compileDebugKotlin` and
  `./gradlew :desktopApp:compileKotlin` both pass.
