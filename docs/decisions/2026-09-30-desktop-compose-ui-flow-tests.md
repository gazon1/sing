---
title: "Desktop Compose UI tests mount the real App() with an in-memory platform module"
date: 2026-09-30
tags: [desktop, testing, compose, koin, ui-test]
status: accepted
supersedes: 2026-09-06-desktop-smoke-test-with-koin
---

# Desktop Compose UI tests

## Context

`2026-09-26-ui-testing-deferred.md` deferred UI testing and superseded four
September ADRs, including `2026-09-06-desktop-smoke-test-with-koin`. That record
stated that `NavBackStackEntry` lifecycle transitions crash the Compose test host,
and its smoke test was later removed — so whether the real desktop shell could be
driven from `runDesktopComposeUiTest` at all was an open question. Android is
covered by 40-odd Maestro flows; desktop had three smoke tests over two
components.

## Idea

Two options:

1. **Test screens in isolation.** Mount each screen with hand-built state and
   callbacks. Avoids the whole DI graph, but leaves navigation, the version gate,
   auth and cross-feature wiring untested — the parts that broke in the first
   place.
2. **Mount the real `App()`** behind a test platform module. Exercises the true
   startup chain and makes each test a genuine mirror of a Maestro flow, at the
   cost of building a Koin graph that never touches the developer's machine.

## Decision

**Option 2.** `runDesktopAppTest` assembles a per-test `KoinApplication` that
mirrors `desktopApp/src/main/kotlin/com/singularity/todo/main.kt`, then mounts
the production `App()`.

Three things make that sound rather than merely convenient:

- **`testPlatformModule()` replaces `platformModule()` entirely.** The real one
  resolves the SQLite path, three DataStore paths and the backups directory from
  `System.getProperty("user.home")`, runs a settings migration against them while
  its module body is constructed, and hands out ports that shell out to
  `secret-tool`, `notify-send` and `at`. The test module binds `FakeAppDatabase`
  — the existing in-memory `AppDatabase` contract — plus inert ports.
- **It loads after `domainModule()`.** Koin resolves duplicate definitions
  last-wins, so loading earlier let `coreModule()`'s `SupabaseAuthRepository`
  override the fake. Its `userId` resolves from `anonymous` to a generated ULID
  shortly after startup, which orphans anything written in that window and makes
  the row invisible to every later read.
- **No process-global state.** An earlier revision redirected `user.home` to a
  temp dir per test. That property is process-global, so two tests swapping it
  race: the loser opens a directory the winner already deleted and dies with
  `SQLiteException` code 14 (`SQLITE_CANTOPEN`) — a failure that only appears in
  a full-suite run. With `FakeAppDatabase` the module touches nothing global, so
  JUnit parallel execution stays on.

The shell is selected by `contentDescription`, not `testTag`: the drawer entries
and the FAB carry no tag on desktop, unlike their Android counterparts. Arrival is
asserted with `assertCurrentTab`, which reads the drawer's `Selected` semantics —
the top-bar title text matches three nodes at once on a fresh database.

## Rationale

- The alternative tested less of the thing that was broken.
- `FakeAppDatabase` already existed and was written for exactly this shape of
  test; reusing it kept the module small.
- Deriving flows from Maestro rather than inventing them keeps the two platforms
  answering the same question about the same product.

## Consequences

- 30 tests across seven flow suites plus the boot checkpoint, all green.
- `koin-compose`, `kotlinx-datetime` and `lifecycle-viewmodel-compose` were added
  to the desktopApp test source set: `:shared` declares them as `implementation`,
  so they are not visible transitively.
- The three pre-existing desktop tests were moved to
  `androidx.compose.ui.test.v2.runDesktopComposeUiTest`; the v1 overload is
  deprecated in Compose 1.12 and will be removed.
- **Open: undated tasks do not render in the agenda.** A task written through the
  repository is returned by `TaskRepository.observeAll()` while the agenda shows
  "No tasks", and stays empty across a tab switch that recreates the ViewModel.
  This also bounds the create flow, which stops at the editor. The Android suite
  asserts the opposite (`agenda/01-smart-lists.yaml` waits for `"No Date  ·  1"`
  and is documented green), so this is either desktop-only or the Android flow
  predates a change. It is a product question, not a harness one.
- Not covered: Roborazzi snapshots on desktop, navigation lifecycle beyond what
  the flows drive, and the real Room/JDBC path (the flows run against
  `FakeAppDatabase`).

## Links

- `2026-09-26-ui-testing-deferred.md` — the deferral this work resumes
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/DesktopAppHarness.kt`
- `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/TestPlatformModule.kt`
- `.agents/skills/singularity-todo-desktop-compose-ui-tests/SKILL.md`
