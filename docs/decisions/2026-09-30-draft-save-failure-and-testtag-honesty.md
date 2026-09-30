---
title: "A save that throws must be visible, and a declared testTag must be applied"
date: 2026-09-30
tags: [testing, ui, draft-mvi, testtags, debuggability]
status: accepted
---

# Silent saves and unapplied test tags

Two defects surfaced while building the desktop Compose UI suite
(`2026-09-30-desktop-compose-ui-flow-tests.md`). Both are invisible to the
existing test stack, and both cost most of the debugging time that suite needed.

## Context

**1. `DraftMviViewModel.save()` swallowed exceptions.**
`save()` was `try { ... } finally { ... }` with no `catch`. `validate` and
`persist` are `open`/`abstract`, and an implementation may satisfy either by
throwing rather than by returning `Either.Left`. A throw cancelled the save
coroutine, `finally` re-enabled the button, and the user got a save that did
nothing — no error state, no snackbar, no log.

It surfaced as a desktop UI test whose every assertion on the task editor was
green while the saved task simply never appeared. Nothing in the editor's own
test could express it, because the existing suite only covered `Either.Left`.

**2. The TestTags registry published names nothing produces.**
`TASK_EDITOR_DUE_ROW`, `TASK_EDITOR_PRIORITY_ROW`, `PRIORITY_OPTION_*`,
`TASKS_LIST`, `EditorOverflow.ARCHIVE/PIN/UNPIN` and `SNACKBAR_SAVED` are
declared in `TestTags.kt` and applied by no composable. `SlugTest` references
several of them, which makes them *look* live to a reader.

A declared-but-unapplied constant is worse than a missing one: a test author
writes `onNodeWithTag(TestTags.TASK_EDITOR_DUE_ROW)` and gets a bare "could not
find any node", with nothing indicating the tag was never wired. Three planned
desktop flows were written against these before the gap was found.

## Decision

**Fix `save()` and `validate()`.** `save()` gained a `catch (Throwable)` that
logs and sets `error`; `CancellationException` is rethrown so structured
concurrency still works. `validate` is now called through a private
`safeValidate` from both call sites — `pushUiState` on every keystroke and
`save()` — so a throwing validator degrades to a validation error instead of
escaping through `onIntent` into the composition.

**Enforce the registry.** `TestTagsWiringTest` (Konsist-style scan in
`:shared:jvmTest`) asserts every declared `TestTags` constant is applied by some
production composable, with an allowlist carrying a reason per entry. It scans
`commonMain`, `androidMain` and `jvmMain` — the shell chrome carrying
`NAV_MENU_BUTTON` and `TASKS_FAB` lives in `androidMain`, so a commonMain-only
scan reports both as dead. `*_PREFIX` constants are excluded: they are building
blocks for a generated tag, not tags themselves.

**Make test logging opt-in and usable.** `initTestLogging()` routes Kermit to
stdout at verbose severity with a timestamp and tag. Kermit's JVM default
already writes, so this is not about making the suite speak up — it is that the
default severity filter hides `Logger.d`/`Logger.v`, which is where repository
and ViewModel tracing lives, and the default format carries no tag while one test
mounts a whole app.

## Rationale

- A control the user pressed and the system silently discarded is the worst
  failure mode in an editor; the cost of a visible error is a snackbar.
- `save()` is `open` and used by every draft-backed editor, so the gap was
  reachable from any of them, not just task creation.
- The registry is advertised as the single source for selectors, so a false entry
  is a correctness bug in the contract, not a documentation nit.
- `find-unwired-surfaces.py` covers the inverse (implemented but never called);
  this covers the tag half of the same defect — a name published but never
  produced.

## Consequences

- Two new tests in `DraftMviViewModelTest` fail on the old code and pass on the
  new, so the regression is pinned.
- The `TestTagsWiringTest` allowlist starts with eleven entries. Each needs the
  constant *applied* before it can be deleted; removing the constant instead
  would leave the same trap, undocumented.
- Test switches need explicit forwarding: `-D` on the Gradle CLI configures the
  daemon, not the forked test JVM, so the flag parses and does nothing.
  `desktopApp/build.gradle.kts` forwards `singularity.*` — without it the logging
  switch would appear to work and be inert.
- Unchanged: what the UI does about a failed save. The error becomes visible; it
  does not become retryable.

## Open

- Undated tasks are written successfully and never rendered by the agenda. That
  is a product question; see `2026-09-30-desktop-compose-ui-flow-tests.md`.
- The desktop shell carries no testTags at all — drawer entries, FAB and
  hamburger are addressable only by `contentDescription`. Adding them would let
  Maestro selectors carry over unchanged, at the cost of editing production code
  for tests. Not decided.

## Links

- `2026-09-30-desktop-compose-ui-flow-tests.md`
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/TestTagsWiringTest.kt`
- `scripts/find-unwired-surfaces.py` — the inverse check
