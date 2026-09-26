---
title: "Drop ViewModel in AndroidPomodoroTimer; extract PomodoroScheduler port; use kotlinx.datetime.Clock"
date: 2026-09-23
tags: [pomodoro, alarms, architecture, testability, koin]
status: accepted
---

## Context

`AndroidPomodoroTimer` extended `ViewModel` and used `viewModelScope.launch` internally. This made it untestable in isolation — `viewModelScope` is provided by the ViewModel system and isn't available in a plain JVM test. Additionally, it accepted `PomodoroAlarmScheduler` (the concrete Android class) rather than a port, preventing test substitution. The `tasks: StateFlow<List<Task>>` was mixed into the `PomodoroTimer` interface, violating layer separation.

## Decision

1. **`AndroidPomodoroTimer` drops `ViewModel`**: primary constructor takes an injected `CoroutineScope`. Secondary constructor provides `MainScope()` for Koin injection. `@Volatile var` replaces `AtomicBoolean`. Accepts `PomodoroScheduler` interface (not the class).

2. **`PomodoroScheduler` interface** (`commonMain`): extracted from `PomodoroAlarmScheduler`. Methods: `schedulePhaseEnd(fireAtEpochMs, taskId, phase)` and `cancelPhaseEndAlarm()`. Enables test doubles without extending the Android class.

3. **`PomodoroTaskListProvider` SAM fun interface** (`commonMain`): `fun interface PomodoroTaskListProvider { fun tasks(): StateFlow<List<Task>> }`. Replaces `tasks` from the `PomodoroTimer` interface, restoring layer separation.

4. **`kotlinx.datetime.Clock` in DI**: `CoreDiModule` registers `kotlin.time.Clock.System`. `AndroidPomodoroTimer` uses `kotlinx.datetime.Clock` directly (the same type, aliased). `FakeClock` (test fake) implements `kotlinx.datetime.Clock`, making it a drop-in test substitute.

5. **`AlarmContract` object** (`commonMain`): centralises `tagFor()`, Intent extra key constants (`EXTRA_REMINDER_ID`, `EXTRA_USER_ID`, `EXTRA_PHASE`, `EXTRA_TASK_ID`), and phase string values. Used by both `AlarmReceiver` and `AlarmManagerReminderScheduler`.

6. **`LIMIT 20` query**: `AlarmReceiver.handleReminderFire` uses `watchRecentDueBefore(now, 20)` instead of `watchDueBefore(now).first().takeLast(20)`, pushing the limit to the database layer.

7. **`AndroidPomodoroTimerTest` in `androidHostTest`**: tests live in `androidHostTest` (Robolectric), not `jvmTest`, because `AndroidPomodoroTimer` depends on `kotlinx.datetime.Clock` which requires Android runtime. Tests are skip-based (not delay-based) for determinism.

## Rationale

- **Testability**: With `CoroutineScope` injected and `PomodoroScheduler` as interface, the timer is fully testable with `FakeClock`, `FakePomodoroScheduler`, and `TestScope`. The `androidHostTest` source set provides the Android runtime needed by `kotlinx.datetime.Clock`.
- **Layer separation**: `PomodoroTaskListProvider` decouples `PomodoroTimer` from `TaskRepository`, letting the timer be tested without a full repository stack.
- **DRY**: `AlarmContract` removes duplicated string constants across `AlarmReceiver`, `AlarmManagerReminderScheduler`, and `PomodoroAlarmScheduler`.
- **Performance**: `LIMIT 20` at the database avoids loading potentially unbounded reminder sets into memory.

## Consequences

- `factory { AndroidPomodoroTimer(...) }` in Koin is a **memory leak** for ViewModels — must use `factory<PomodoroTimer> { AndroidPomodoroTimer(...) }` or `viewModel { }` for actual ViewModels. `AndroidPomodoroTimer` is not a ViewModel, so `factory` is correct here.
- `kotlinx.datetime.Clock` is aliased as `com.singularity.todo.core.platform.Clock` (expect/actual). Use `kotlinx.datetime.Clock` in new code; the alias is deprecated.
- `androidHostTest` (Robolectric) must be used for any tests that require Android runtime or Android-specific types. `jvmTest` cannot access `androidMain`.
- Tick-based tests (fake clock advancing real `delay()`) are unreliable in unit tests. All `AndroidPomodoroTimer` tests use `skip()` to drive phase transitions without depending on virtual time.
- `AlarmContract` is an `object` (no `Companion`). Static-style access (`AlarmContract.EXTRA_PHASE`) is direct, not via `.Companion`.

## Links

- Commit: `57b78d5 refactor(alarms+pomodoro): cleanup dead code, canonical VM pattern, testable timer`
- New files: `AlarmContract.kt`, `PomodoroScheduler.kt`, `PomodoroTaskListProvider.kt`, `AndroidPomodoroTaskListProvider.kt`, `JvmPomodoroTaskListProvider.kt`, `FakePomodoroAlarmScheduler.kt`, `AndroidPomodoroTimerTest.kt`
- Modified: `AndroidPomodoroTimer.kt`, `PomodoroAlarmScheduler.kt`, `AlarmReceiver.kt`, `CoreDiModule.kt`, `PlatformModule.android.kt`, `PlatformModule.jvm.kt`
