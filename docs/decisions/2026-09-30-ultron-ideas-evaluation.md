---
title: "Ultron testing ideas — what we adopted, what we skipped"
date: 2026-09-30
tags: [testing, desktop, ui, helpers]
status: accepted
---

## Context

Three sources proposed adopting ideas from the Ultron testing framework
(https://github.com/open-tool/ultron) for the desktop Compose UI test
infrastructure. An initial review suggested taking six ideas from Ultron as
in-house helpers. Deeper investigation revealed that the project already has
 analogues for five of the six, and that the library itself has three concrete
 barriers to adoption.

## Idea

Three options were on the table:

1. **Take ultron-compose as a dependency** (`com.open-tool:ultron-compose:2.6.5`).
2. **Take only the architectural ideas** — implement `FailureBundle`, named
   element wrappers, `step()` logging, seed DSL, and a central test config in
   the project's own code.
3. **Skip entirely** — the existing helpers already cover the same ground.

Additionally, five discrete infrastructure improvements were identified as
genuinely missing from the project:
- A Konsist-style enforcement test preventing `runDesktopComposeUiTest` from
  being called directly.
- A lightweight harness for pure Compose tests that need no Koin.
- A `SemanticsMatcher`-based overload of `awaitTag`.
- A named constant for the shared 5-second timeout.
- A documented table of all `singularity.*` switches.

## Decision

**Take none of the Ultron ideas as library code.** Three barriers make it
unworkable at this time:

- **Compose v1 vs v2 API mismatch.** Ultron Compose pins to the deprecated
  `androidx.compose.ui.test` (v1, Compose Multiplatform ≤ 1.9). The project
  already migrated to `androidx.compose.ui.test.v2.runDesktopComposeUiTest` (CMP
  1.12). The two APIs have incompatible receivers; mixing them in one test
  suite is not supported.
- **Allure integration is Android-only.** `ultron-allure` is a pure Android
  library (`androidLibrary` Gradle target, no `jvmMain`). On desktop there is
  no Allure step reporting, no screenshots, and no view hierarchy dumps from
  Ultron's listeners.
- **JUnit 4 only.** Ultron's Compose rule (`createUltronComposeRule`),
  `UltronConfig`, and all examples are JUnit 4 (`@get:Rule`,
  `RuleChain`). The project uses JUnit Jupiter (`kotlin.test.junit5`,
  `useJUnitPlatform()`). Bridging the two requires splitting a source set or
  maintaining a JUnit 4 shadow suite.

**Take the five infrastructure improvements instead.** All five are
implemented in this epic. The project already had equivalents for the
Ultron ideas themselves:

| Ultron concept | Project analogue | Status |
|---|---|---|
| FailureBundle (screenshot + state + log) | `FailureBundle.kt` + `runDesktopAppTest` try/catch | Already existed |
| Named element errors + parent scoping | `DesktopNavigation.awaitTag` + `explainMissingTag` | Already existed |
| Page Object DSL | `TasksRobot.kt` | Already existed |
| Seed DSL | `TaskFixtures.kt` (`koin.seedTask`) | Already existed |
| `TestInfo` injection | JUnit Jupiter `TestInfo` parameter | Already worked |

## Findings from phases 1–3

- `NotesScreenTest` (3 tests) and `TagsRenameUiTest` (4 tests) were calling
  `runDesktopComposeUiTest` directly — bypassing `runDesktopAppTest` and losing
  all FailureBundle capture. Both are now migrated to `IsolatedComposeTest`.
- `TaskRowFlowTest` had a raw `5_000` timeout literal instead of `TIMEOUT_MS`.
- The `singularity.test.log` switch was documented only in `TestLogging.kt`
  KDoc; a reader had no way to discover `singularity.ui.dumpTree` except by
  grepping. Both are now listed with defaults and effects.
- `MenuBarTest` and `ContextMenuTest` remain allowlisted in
  `DesktopTestHarnessEnforcementTest` as intentional smoke tests — they call
  `runDesktopComposeUiTest` directly because a full harness adds no value for
  headless AWT/popup smoke tests.

## Consequences

- `com.open-tool:ultron-compose` is not added to any Gradle configuration.
- `DesktopTestHarnessEnforcementTest` enforces that all new desktop tests go
  through `runDesktopAppTest` or `IsolatedComposeTest`.
- `IsolatedComposeTest` provides semantics-tree capture for pure Compose tests.
- `DesktopNavigation.awaitTag` gains a `SemanticsMatcher` overload for non-tag
  selectors.
- All `singularity.*` switches are documented in `TestLogging.kt`.

## Re-evaluation triggers

- **Ultron adds v2 API support** — check quarterly via `git log` on the
  ultron repository; if `runDesktopComposeUiTest` is replaced with a v2 entry
  point, re-evaluate.
- **Ultron adds multiplatform Allure** — issue #94 on the ultron tracker is
  still open. If it closes with a JVM target, re-evaluate the reporting
  layer.
- **Project adopts JUnit 4 for UI tests** — if a future epic introduces
  Espresso or UiAutomator on Android and aligns the test stack to JUnit 4,
  the JUnit-4-only objection disappears.

## Links

- [Ultron GitHub](https://github.com/open-tool/ultron)
- `2026-09-26-ui-testing-deferred` — Ultron adoption explicitly deferred
- `2026-09-05-ui-tests-ultron` — original Ultron ADR (superseded)
- `2026-09-30-desktop-compose-ui-flow-tests` — desktop harness architecture
- `singularity-todo-test-helpers` skill — existing helpers catalogue
