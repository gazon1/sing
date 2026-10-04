---
title: "Post-MR-2 audit findings"
date: 2026-10-01
tags: [audit, mr-2]
status: accepted
---

# Post-MR-2 audit findings

## NoDate bisect — COMPLETED

**Root cause**: `ProfileAwareCurrentUser._scopedUserId` was initialized to `UserId.anonymous`
before the `combine().collect` collector fired. The test harness's `seedTask()` ran before
the collector fired, getting `UserId.anonymous` and orphaning the task.

**Fix applied**:
- `ProfileAwareCurrentUser`: seed `_scopedUserId` synchronously from `currentUser.userId.value`
  and `profileRepository.activeProfileId.value` (both `StateFlow`, both already seeded).
- `FakeAuthRepository`: default changed from `Session.Anonymous(UserId.anonymous)` to
  `Session.Anonymous(TestUsers.DEFAULT)` so the fake is consistent.

**Verification**: `AgendaTabDefinitionFlowTest.inbox_shows_the_no_date_section_the_today_tab_has_no_room_for`
passes. Full `shared:jvmTest` and `desktopApp:test` suites green (except pre-existing).

**ADR**: `2026-09-30-nodate-fix.md` (accepted). `deferred-backlog.md#nodate-steps-2-4` resolved.

## K6 Clock regression — FIXED

**Root cause**: `val clock: Clock = Clock.System` re-introduced in `ProjectDetailContent.kt:112`
after `remove-platform-clock-object` ADR.

**Fix applied**: `ProjectDetailViewModel.clock` already injected; made it `public` and
`ProjectDetailContent` now reads `viewModel.clock`. Removed `@file:Suppress` and
`@OptIn(ExperimentalTime)`.

## Draft save error paths — ALREADY FIXED

`DraftMviViewModel.save()` was already hardened: broad `catch(Throwable)`, `finally`
resets `isSaving`, `CancellationException` rethrown. `make save() final` (commit
`ee753585`) prevents subclasses from bypassing. No changes needed.

## TestScope semantics — ALREADY PINNED

`AgendaViewModelTest` uses `runCurrent()` not `advanceUntilIdle()` for infinite
`todayFlow` loops. `560f3bf8` style confirmed correct. No changes needed.

## 21 pre-existing failing tests — ALREADY FIXED

`SavedAgendaViewModelTest` (11), `ChatViewModelTest` (3), `NoteEditorTest` (7) —
all pass in current branch. The MVI sweep fixed them. Verified by running all three
test classes explicitly.

## Pomodoro tick test — DEFERRED (MR-3)

`AndroidPomodoroTimerTest` uses skip-based tests. Tick-decrement logic not virtual-time-aware.
`inject TestScope` deferred to MR-3 (NoRealDelayInTest cutoff is also MR-3).

## Cluster 10 (`delay(N)` ban) — CLEAN

No `delay(N)` found in `commonTest`. The `NoRealDelayInTest` detekt rule does not
yet exist — creation deferred to MR-3 (Cluster 8: new rules).

## Desktop test suite

**Pre-existing failure** (not introduced by MR-2):
- `CalendarFlowTest.every_day_of_the_month_has_an_addressable_cell`:
  `calendar_day_2026_10_01` not displayed. The test uses `midMonth = today.year, today.month, 15`
  (September 15) and also checks `today` (September 30). The failure is for Oct 1
  from the next-month pager page — likely the pager doesn't fully compose next-month
  trailing days. **Not fixed in MR-2.** Needs separate investigation.
  *(Note: the test body was later rewritten to assert `awaitAnyDisplayed(calendarDay(midMonth.toString()))` and today's cell instead of pinning a pager index — the original failure mode was addressed, but the test was still live at time of this finding.)*

All other desktop flow tests pass.

## Other `Clock.System` usages

Checked all remaining `Clock.System` usages against `NoDirectClockSystemRule`. The
`ProjectDetailContent` exemption comment is removed (no longer using `Clock.System`).
All others are in `actual` platform implementations or DI bindings — no action needed.

## Items deferred to MR-3 / later

1. **Q2**: `startDate` but no `dueDate` → "No Date"? `TaskComputed.hasNoDate` unification.
2. **NoRealDelayInTest rule**: create + cutoff (MR-3).
3. **Pomodoro timer TestScope injection** (MR-3).
4. **CalendarFlowTest** calendar pager issue (MR-7?).
