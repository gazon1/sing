---
status: accepted
---
# 2026-09-17 — VM Testability Audit (rolled back, root cause identified)

## Status
Accepted (deferred to future MR)

## Context

A full audit of the project's 11 ViewModels was performed to assess their
testability and identify a migration path toward the canonical 4-arg
constructor + secondary ctor pattern documented in
`singularity-todo-testable-vm`.

### Audit findings

**Coverage:**

- 11 ViewModels exist (`SavedAgendaViewModel` is the canonical reference).
- 7 VMs have dedicated unit tests:
  - `TasksViewModelTest`, `TaskCreateDebounceTest`, `TaskDetailViewModelTest`,
    `ProjectsViewModelTest`, `ProjectDetailViewModelTest`,
    `CalendarViewModelTest`, `SavedAgendaViewModelTest`.
- 4 VMs have **zero** test coverage: `TaskCreateViewModel`,
  `ProjectEditorViewModel`, `NotesListViewModel`, `AgendaViewModel`,
  `SavedAgendaListViewModel`.

**Test results (as of 2026-09-17):** 593 tests pass, 0 failures, 0 errors.

**Constructor pattern:**

- 10 of 11 VMs use the older `scopeOverride: CoroutineScope? = null` +
  broken getter (`scope: CoroutineScope get() = scopeOverride ?: viewModelScope`)
  pattern, where the getter is unreliable during init blocks because
  `viewModelScope` is not yet attached.
- 1 VM (`SavedAgendaViewModel`, MR4) uses the canonical 4-arg primary ctor
  with secondary ctor for Koin.

**Side effects in `combine`:**

- `TaskDetailViewModel` (lines 113–170) and `ProjectDetailViewModel`
  (lines 145–158) write to `_latestTask.value` / `_latestProject.value` and
  seed drafts inside a `combine` lambda. These writes re-execute on every
  upstream emission and can silently overwrite user-edited drafts when
  unrelated flows (reminders, tags) emit.

### Why an MR was rolled back

A migration MR was attempted that:

1. Replaced the `scopeOverride` pattern with primary + secondary ctor across
   all 8 "simple" VMs (Commit 2).
2. Migrated the 4 side-effects-in-combine VMs (Commits 3, 4).
3. Added 4 smoke tests for previously untested VMs.

During execution the migrated `CalendarViewModelTest`, `TasksViewModelTest`,
and others began failing with `UncompletedCoroutinesError` or
"VM state stuck at Loading after `advanceUntilIdle()`".

### Root cause (re-analysis after rollback)

The root cause is **not** the `scopeOverride` pattern. It is
`ProfileAwareCurrentUser` (in
`shared/src/commonMain/kotlin/com/singularity/todo/feature/profile/ProfileAwareCurrentUser.kt`):

```kotlin
class ProfileAwareCurrentUser(
    currentUser: CurrentUser,
    profileRepository: ProfileRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val scopedUserId: StateFlow<UserId> = combine(
        currentUser.current,
        profileRepository.activeProfileId,
    ) { ... }.stateIn(scope, SharingStarted.Eagerly, UserId.anonymous)
    // ...
    val current: UserId get() = scopedUserId.value
}
```

This class:

1. Creates **its own** `CoroutineScope` with `Dispatchers.Default` inside
   the constructor.
2. Hosts a `stateIn` flow that never completes.

When a `ViewModel` subscribes to `scopedUserId` and is constructed in a
`runTest { ... }` block, the upstream lives on `Dispatchers.Default` —
**outside** the test dispatcher's `TestScheduler`. `advanceUntilIdle()`
advances virtual time only inside the `TestScope`; the real `Default`
dispatcher continues to run on its own scheduler. As a result:

- `runTest` waits for child coroutines that the VM launched on `this`
  (test scope), but the upstream that feeds `_state.value = ...` lives on
  `Dispatchers.Default` and never emits within virtual time.
- Tests that previously "worked" by passing `scopeOverride = null` did so
  because `stateIn(WhileSubscribed)` had no subscribers → state stayed at
  `Loading` and was never observed.
- Tests that tried `scope = this` failed because the VM's init coroutine
  hangs (waiting for an emit that never arrives in virtual time).
- Tests that tried `scope = backgroundScope` did not hang, but state still
  never advanced past `Loading`, so any assertion about `Loaded` content
  failed.

### The fix the user proposed

Refactor `ProfileAwareCurrentUser` so it does **not** own a coroutine
scope. Two options:

**Option A (simplest):** drop `stateIn` entirely and expose a cold
`Flow<UserId>`:

```kotlin
class ProfileAwareCurrentUser(
    currentUser: CurrentUser,
    profileRepository: ProfileRepository,
) {
    val scopedUserId: Flow<UserId> = combine(
        currentUser.current,
        profileRepository.activeProfileId,
    ) { userId, profileId ->
        if (profileId == ProfileId.default) userId
        else UserId.fromString("${profileId.value}/${userId.value}")
    }

    val current: UserId get() = ???  // no longer available — see notes
}
```

Trade-off: `.current` (used in some VMs for imperative reads) must be
removed. Each call site has to do `.scopedUserId.first()` or pass the
`UserId` in via constructor.

**Option B:** keep `.current` semantics but inject a scope that the DI
graph owns (e.g. an application-wide scope):

```kotlin
class ProfileAwareCurrentUser(
    currentUser: CurrentUser,
    profileRepository: ProfileRepository,
    private val scope: CoroutineScope, // Koin provides applicationScope
) {
    val scopedUserId: StateFlow<UserId> = combine(...)
        .stateIn(scope, SharingStarted.Eagerly, UserId.anonymous)
    val current: UserId get() = scopedUserId.value
}
```

Trade-off: tests need to inject `TestScope` / `backgroundScope` and the
test infrastructure needs a tiny `applicationScope` factory.

Both options also require `FakeProfileAwareCurrentUser` to be updated so
its test-side flows run on the test dispatcher.

## Decision

**Defer the constructor migration and the side-effects fix to a future MR
that starts with `ProfileAwareCurrentUser` refactor.** Do not land
constructor changes alone — they will not deliver testability wins until
the upstream dispatcher problem is resolved.

This MR delivers the **skills infrastructure** that future work needs:

1. `singularity-todo-vm-migration-playbook` — the 5-step playbook for
   migrating from `scopeOverride` to primary + secondary ctor.
2. `singularity-todo-test-helpers` — standard test helpers, FakeRepositories
   catalog, three test shapes (smoke / intent→state / regression).
3. `singularity-todo-testable-vm` — updated with the "Concrete VMs following
   this pattern" section listing all 11 VMs.
4. `singularity-todo-feature-scaffold` — updated with a "Test Patterns"
   section.

These skills are ready for the future MR. They make the actual migration
a mechanical, low-risk change once `ProfileAwareCurrentUser` is unblocked.

## Rationale

- **The audit is valuable even when the migration is deferred.** Knowing
  where the testability gaps are (4 untested VMs, 2 side-effects-in-combine
  anti-patterns, 10 broken getters) keeps the project honest about its
  testing debt.
- **Fixing the wrong thing first wastes a lot of time.** A mechanical
  constructor change that "looks testable" but doesn't actually fix
  hanging tests is worse than no change — it gives future engineers a
  false sense of progress.
- **Side effects in `combine`** (Tasks/Project detail VMs) are a real bug
  that needs fixing — but the fix depends on the same dispatcher story,
  so it should go in the same future MR.

## Migration Map (deferred to future MR)

1. Refactor `ProfileAwareCurrentUser` (option A or B above).
2. Update `FakeProfileAwareCurrentUser` and any test that depends on
   `.current` to use the new API.
3. Migrate the 8 simple VMs to primary + secondary ctor (mechanical).
4. Migrate `TaskDetailViewModel` and `ProjectDetailViewModel` with the
   side-effects fix.
5. Add 4 smoke tests for previously untested VMs.
6. Tighten the existing tests now that they actually receive emissions
   from the upstream flow.

## Consequences

- The 4 untested VMs (`TaskCreateViewModel`, `ProjectEditorViewModel`,
  `NotesListViewModel`, `AgendaViewModel`, `SavedAgendaListViewModel`)
  remain untested until the future MR.
- The 2 side-effects-in-combine anti-patterns remain in `TaskDetailViewModel`
  and `ProjectDetailViewModel` (real bug, fixed later).
- The `scopeOverride` getter anti-pattern remains in 10 VMs (the canonical
  alternative is documented in skills, ready to apply).
- All 593 existing tests continue to pass.

## Links

- `singularity-todo-testable-vm`
- `singularity-todo-vm-migration-playbook`
- `singularity-todo-test-helpers`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/profile/ProfileAwareCurrentUser.kt` — root cause file
