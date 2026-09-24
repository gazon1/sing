---
title: "Accumulated deprecation warnings and pre-existing test failures"
date: 2026-09-23
tags: [technical-debt, deprecation, tests]
status: accepted
---

## Context

During the `refactor/cleanup-alarms` worktree session, the following issues were identified but deferred for later fixes because they are non-critical (warnings, not errors) or require significant test infrastructure changes.

## Decision

Document the issues for future cleanup; do not fix in this worktree.

## Consequences

### A. kotlinx.datetime deprecation warnings (non-critical)

The following files use deprecated `kotlinx.datetime` APIs that produce warnings but do not break the build:

**`LocalDate.dayOfMonth` → `LocalDate.day`** (kotlinx-datetime 0.8.0 deprecates `dayOfMonth`):
- Fixed: `RussianDateFormatter.kt`, `MiniCalendarPanel.kt`, `TimeGridView.kt`, `MonthGridView.kt`, `CalendarEventMapper.kt`
- Not fixed (requires `Int` → `Month` migration in DI): `CalendarScreen.kt:29` — `anchorDate.monthNumber` passed as `Int` to `parametersOf(year, monthNumber, mode)`. `CalendarDiModule` accepts `Int`, not `Month`. Fix requires changing DI parameter type from `Int` to `Month` and updating all call sites.

**`LocalDate.monthNumber` → `LocalDate.month`**:
- Partially fixed: `CalendarEventMapper.kt` `nextDay()` function now uses `monthNumber` (local variable, not property) to avoid ambiguity.
- Not fixed: `CalendarScreen.kt` — same DI issue as above.

**`CalendarScreen.kt` uses `java.time.LocalDate`** (not `kotlinx.datetime.LocalDate`), so its `.dayOfMonth` is NOT deprecated. The warning on this file is from `monthNumber` usage.

**`StatisticsScreen.kt` uses `java.time.LocalDate`** — `dayOfMonth` here is NOT deprecated.

### B. Pre-existing test failures (`UncompletedCoroutinesError`)

Affected tests (all in `jvmTest`):
- `SavedAgendaViewModelTest` — 11 tests
- `ChatViewModelTest` — 3 tests
- `NoteEditorTest` — 7 tests

**Symptom:** `kotlinx.coroutines.test.UncompletedCoroutinesError` at `TestBuilders.kt:353`

**Root cause:** `SavedAgendaViewModel` launches `scope.launch { when(mode) { Edit/Create } }` in `init`. The `FakeSavedAgendaViewsRepository.observe()` returns a `Flow` that never completes. When `advanceUntilIdle()` is called, all coroutines advance but the VM's collector is still active (waiting on the flow). When the test ends, the unclosed scope causes `UncompletedCoroutinesError`.

The tests DO call `advanceUntilIdle()` but the VM's scope isn't cancelled before test completion — `scope` is passed as a child of `TestScope`, but the VM's init-launched coroutine outlives the test body.

**Fix (deferred):** Either:
1. Add `scope.cancel()` in `@AfterTest` via `cleanup()` — but requires making `SavedAgendaViewModel.scope` accessible or adding a `close()` method.
2. Use `runTest(timeout = 10.seconds)` instead of parameterless `runTest` — the timeout forces the test to end even with unclosed coroutines.
3. Change `SavedAgendaDeps.repo.observe()` to emit an initial value synchronously in tests (via a `BehaviorRelay`-style fake).

### C. `expect object Clock` typealias deprecation

`CoreDiModule` line 149 registers `kotlin.time.Clock.System` in DI with a deprecated typealias:
```
e: 'typealias Clock = Clock' is deprecated. This type is deprecated in favor of `kotlin.time.Clock`.
```

**Fix (deferred):** Keep `com.singularity.todo.core.platform.Clock` as the project-level wrapper but stop using it in new code. New code should use `kotlinx.datetime.Clock` directly. Eventually remove the expect/actual pair and the typealias.

### D. Tick-based PomodoroTimer tests

`AndroidPomodoroTimerTest` uses skip-based tests. The tick-decrement logic (`clock.advance(5.seconds)`) does NOT control `delay()` in the timer because `testScope()` wraps `TestScope` but the timer's `delay(1000.milliseconds)` is not virtual-time-aware.

**Fix (deferred):** For proper virtual-time tick tests, inject `TestScope` (not just `CoroutineScope`) into `AndroidPomodoroTimer`. This requires:
1. Changing `AndroidPomodoroTimer` constructor to accept `TestScope` or a virtual-time controller
2. Or using `kotlinx-coroutines-test` `TestCoroutineScheduler` as the clock source

The current skip-based tests cover: start, pause, resume, skip, stop, no-op-when-running, OS alarm scheduling, race guard. Tick decrement is implicitly tested by skip-based phase transitions.

## Links

- Commit: `57b78d5` — refactor(alarms+pomodoro): cleanup dead code, canonical VM pattern, testable timer
- `AndroidPomodoroTimerTest.kt` — 10 passing tests in `androidHostTest`
- `SavedAgendaViewModelTest.kt` — 11 pre-existing failing tests
