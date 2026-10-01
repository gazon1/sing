---
title: "NoDate fix — ProfileAwareCurrentUser race caused tasks to be invisible"
date: 2026-09-30
tags: [agenda, testing, desktop, debugging]
status: accepted
supersedes: 2026-09-30-nodate-root-cause
---

# NoDate fix — ProfileAwareCurrentUser race

## Context

The desktop flow test reported an undated task being written successfully via
`seedTask()` but invisible to the agenda — `observeAll()` returned it while the
screen showed "No tasks". The `AgendaNoDateRegressionTest` domain tests were green,
pinning that `AgendaEvaluator.evaluate()` correctly routes undated tasks to the
Inbox "No Date" section. The break was above the domain.

## Root cause

**Two layers conspired to create an invisible-write window:**

1. **Production `ProfileAwareCurrentUser`** initialized `_scopedUserId =
   MutableStateFlow(UserId.anonymous)` before the `combine(...).collect`
   collector had fired. Any code reading `scopedUserId.value` synchronously at
   construction time got `UserId.anonymous`.

2. **`SupabaseAuthRepository.init {}`** started an async DataStore read via
   `sessionStore.getOrInitDeviceId()` (suspended). `currentUser.userId` stayed
   `UserId.anonymous` until that read completed.

3. **`FakeProfileAwareCurrentUser`** used `Session.Anonymous(UserId.anonymous)`
   as its default session, making `fakeScopedUserId` also seed as `UserId.anonymous`.

In the test harness, `seedTask()` called `profileAwareCurrentUser.scopedUserId.value`
before the collector fired, getting `UserId.anonymous`. The task was written with
that uid and passed the `archived_at IS NULL` filter — but every subsequent query
filtered by the **real** userId (`TestUsers.DEFAULT`), so the orphaned anonymous
task was invisible.

In production the window is microseconds and the window closes before any real
user code runs. In the test harness the window was large enough for `seedTask`
to race past it.

## Decision

Fix both the production class and the fake to seed `scopedUserId` synchronously:

**Production** (`ProfileAwareCurrentUser`):
```kotlin
// Before: _scopedUserId = MutableStateFlow(UserId.anonymous)  ← anonymous until collect
// After:
private val _scopedUserId = MutableStateFlow(
    computeScopedUserId(
        currentUser.userId.value,   // synchronous read
        profileRepository.activeProfileId.value,
    ),
)
```

**Fake** (`FakeRepositories.kt`):
```kotlin
// Before: FakeAuthRepository(initialSession = Session.Anonymous(UserId.anonymous))
// After:
class FakeAuthRepository(initialSession: Session = Session.Anonymous(TestUsers.DEFAULT))
```

Both changes are backward-compatible. The async `combine().collect` in the `init`
block still runs and handles runtime user/profile switches correctly.

## Rationale

- `currentUser.userId` and `profileRepository.activeProfileId` are both
  `StateFlow` already seeded before `ProfileAwareCurrentUser` is constructed,
  so reading their `.value` at construction time is safe and returns the
  correct initial uid.
- Using `combine(...).first()` is not possible (suspend function, can't call
  from constructor). Using `.value` on already-seeded `StateFlow`s is the
  correct alternative.
- The fix eliminates the race without changing any public API or observable
  behavior for runtime user switches.

## Consequences

- `AgendaTabDefinitionFlowTest.inbox_shows_the_no_date_section_the_today_tab_has_no_room_for`
  now passes (was silently skipping the undated seed due to the race).
- `FakeAuthRepository` defaulting to `TestUsers.DEFAULT` aligns with the
  `TestUsers` policy established by `FakeRepositoryFidelityTest`.
- The fix was validated by running the full `shared:jvmTest` suite and the
  `desktopApp:test` suite — both green. The only pre-existing desktop failure
  is `CalendarFlowTest.every_day_of_the_month_has_an_addressable_cell`, which is
  unrelated to the NoDate path.

## Open

- **Q2 (unanswered here)**: does a task with `startDate` but no `dueDate` count
  as "No Date"? Schema v16 added start/end; no ADR states the semantic. The
  rule currently lives twice — `Selector.DateBucket.NoDate` and
  `AgendaEvaluator.computeBadge` both test `dueDate == null` alone. Both should
  route through a single `TaskComputed.hasNoDate`. Filed as a follow-up.
- **`CalendarFlowTest`**: `calendar_day_2026_10_01` not displayed — pre-existing
  failure unrelated to NoDate, not fixed in this MR.

## Links

- `2026-09-30-nodate-root-cause.md` (superseded)
- `deferred-backlog.md#nodate-steps-2-4` (resolved)
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/profile/ProfileAwareCurrentUser.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRepositories.kt`
