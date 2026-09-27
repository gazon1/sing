---
title: Test Flaky Root Causes — Findings from Test Suite Audit
date: 2026-09-25
status: accepted
---

# Test Flaky Root Causes — Findings from Test Suite Audit

## Context

During the test-suite-cleanup session, three real bugs were discovered that caused
test flakiness or incorrect assertions. This ADR documents them for future reference.

---

## Finding 1: `CalendarViewModelTest` — shared `ProfileAwareCurrentUser`

### Symptom
Nine smoke tests (`GoNext does not crash`, `ViewModeChanged does not crash`, etc.)
assert `vm.state.value is CalendarUiState.Loading`. These tests passed but appeared
to be testing the wrong thing — if the VM transitions to `Loaded` immediately after
construction, the `Loading` assertion would fail.

### Root Cause
`FakeReminderRepository()` was instantiated with the **default** `FakeProfileAwareCurrentUser`
(using `Dispatchers.Default` internally), not the test's `fakeCurrentUser`. This meant
all repository collectors inside `FakeReminderRepository` ran on `Dispatchers.Default`,
outside the test's `StandardTestDispatcher` control. `advanceUntilIdle()` could not
drive them to completion.

### Fix
```kotlin
// Before:
private val fakeReminderRepo = FakeReminderRepository()

// After:
private val fakeCurrentUser = FakeProfileAwareCurrentUser(...)
private val fakeReminderRepo = FakeReminderRepository(fakeCurrentUser)  // share user
```

**Rule**: Every `Fake*Repository` that internally creates a `ProfileAwareCurrentUser`
**must** receive the test's shared instance so all collectors run on the same
`CoroutineDispatcher`. In production this is enforced by DI; in tests it must be
explicit.

---

## Finding 2: `AgendaEvaluatorTest` — `todayInSystemZone()` instead of fixed date

### Symptom
`DateBucket ThisWeek matches task within current week` failed with
`AssertionFailedError: Expected value to be true` on machines where the system
date was not September 16, 2026.

### Root Cause
```kotlin
// Wrong — uses real system date:
private val today: LocalDate get() = todayInSystemZone()
```

The test assumed `today` would resolve to September 16, but `todayInSystemZone()`
returns the **actual** system date. If the test ran on a machine with a different
date, the `ThisWeek` bucket calculation produced a different result.

### Fix
```kotlin
// Correct — fixed deterministic date:
private val today: LocalDate = LocalDate(2026, Month.SEPTEMBER, 16)
```

**Rule**: Domain logic tests must use fixed dates. Only use `Clock.System.now()` /
`todayInSystemZone()` in integration tests where system time is part of the
scenario.

---

## Finding 3: `TaskDetailViewModelTest` — outdated comment

### Old (incorrect) comment
```
Timing note: stateIn with WhileSubscribed(5000) delays the flatMapLatest chain
until a subscriber exists.
```

### Reality
A grep across all `commonMain` sources found **zero** uses of `stateIn` with
`WhileSubscribed`. The `MviViewModel` base class uses plain `MutableStateFlow`.
The comment was legacy from an earlier architecture.

### Actual root cause
`TaskDetailViewModel` creates `CreateTaskUseCase(FakeTaskRepository, Clock,
FakeProfileAwareCurrentUser(...))`. Inside `CreateTaskUseCase`, the
`FakeProfileAwareCurrentUser` (version accepting `CoroutineDispatcher`) defaults to
`Dispatchers.Default`. Real wall-clock delays (50–100ms) are needed because the
test dispatcher cannot virtualize `Dispatchers.Default`.

### Fix
Comment updated to document the real root cause and reference ADR-2026-09-25-testable-vm-dispatcher-clock.

---

## Consequences

1. **New test rule**: Every `Fake*Repository` must accept `ProfileAwareCurrentUser`
   as a constructor parameter (not create one internally). All call sites must
   pass the test's shared instance.
2. **New test rule**: Domain tests use `LocalDate` constants, never `Clock.System`
   or `todayInSystemZone()`.
3. **Codebase grep**: Run `grep -rn "WhileSubscribed\|stateIn.*5000" shared/src/commonMain`
   periodically to catch stale comments.

---

## Links

- `CalendarViewModelTest.kt` — fix applied in `chore/test-suite-cleanup`
- `AgendaEvaluatorTest.kt` — fix applied in `chore/test-suite-cleanup`
- `TaskDetailViewModelTest.kt` — comment corrected in `chore/test-suite-cleanup`
- ADR `2026-09-25-testable-vm-dispatcher-clock` — dispatchers in fakes
