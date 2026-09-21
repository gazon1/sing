---
status: accepted
date: 2026-09-21
deciders:
  - Singularity Developer
---

# User-Scoped Repository Pattern

## Context

- **19 ViewModels** import `ProfileAwareCurrentUser` — each duplicates the same
  `flatMapLatest(scopedUserId) { ... }` boilerplate (~80 LOC total).
- A single bug-pattern emerged: an edit-screen VM could **forget** to subscribe to
  user-switch, showing data from the wrong user after a profile change.
- `NoteDao.search(q)` and `TaskDao.search(q)` are **global** — they do not filter by
  `userId`. Any user can search all notes/tasks in the database (cross-user data leak).
- `TaskRepository.changes: SharedFlow<Task>` emits writes from **all** users — the
  SyncEngine must filter by `userId` itself.

## Idea

- `ProfileAwareCurrentUser.scopedUserId` (`StateFlow<UserId>`, Eagerly seeded) is
  the single reactive source of truth for the current identity.
- A reusable helper: `ProfileAwareCurrentUser.observeForCurrentUser { uid → Flow<T> }`
  wraps `flatMapLatest(scopedUserId.distinctUntilChanged())` so repositories don't
  repeat this pattern.
- A marker interface `UserScopedRepository<T, ID>` declares the minimal contract
  (observe + CRUD), making the pattern explicit and testable.
- **Composition, not inheritance** — per `InMemoryStore.kt:13` the marker has no
  shared state; each repository owns its data source and `ProfileAwareCurrentUser`
  internally.
- Auth-safety: caller-trust model — `entity.userId` is set by the caller (factory or
  VM snapshot), not overwritten by the repository. This keeps blast radius minimal.

## Decision

1. `ProfileAwareCurrentUser.observeForCurrentUser { source(it) }` — the helper
   extension on `ProfileAwareCurrentUser`. Uses `scopedUserId.distinctUntilChanged()
   .flatMapLatest { source(it) }`.

2. `UserScopedRepository<T, ID>` as a **marker interface only** (no abstract base
   class, no shared state). Declares:
   - `observeAllForCurrentUser(): Flow<List<T>>`
   - `observeForCurrentUser(id: ID): Flow<T?>`
   - `create(item: T): Result<Unit>`
   - `update(item: T): Result<Unit>`
   - `delete(id: ID): Result<Unit>`

3. Domain-specific observers use short naming (no `ForCurrentUser` suffix) —
   `observeByFilter(filter)`, `observeByDate(date)`, `observeSubtasks(parentId)`.
   The filter/parameter already makes them user-scoped.

4. Repositories read `ProfileAwareCurrentUser.current` for mutations
   (caller-trust `entity.userId`). The repository stamps `userId` on entities
   only via the factory functions in use cases, not in the repository layer.

5. `TaskFilter.Search` + `TaskDao.search(q)` → `searchByTitle(userId, q)`:
   both the DAO-level query and the repository-level `observeByFilter(Search)` are
   fixed to require `userId`. No schema migration needed (the suspend variant
   `searchByTitle(userId, q)` already existed for `NoteDao`).

6. Phase 1 + Phase 2.1 combined into one **infra+pilot MR**: the full
   `TaskRepository` migration demonstrates the pattern on real code. Six remaining
   repositories follow in Phase 2 (one MR each).

7. Tests: helper-level unit tests for `observeForCurrentUser` (2 tests) + one
   `TaskRepositoryImpl` integration test that simulates user-switch via
   `FakeAuthRepository.session.value = Session.Anonymous(otherUser)`.

8. `TaskRepository.changes: SharedFlow<Task>` — **out of scope**. SyncEngine
   is responsible for filtering by `userId`. Documented as a known issue.

## Rationale

- **Composition > inheritance**: `InMemoryStore.kt:13` explicitly discourages
  generic abstract base classes that only share state. A marker interface +
  extension function preserves the design principle.
- **caller-trust `entity.userId`**: avoids a second write on every mutation;
  the single authoritative source is the factory/use-case layer. An override
  option (repo re-stamps `userId` on every mutation) is a separate ADR.
- **`FakeProfileAwareCurrentUser` stays thin**: it is a factory, not a mutable
  test double. User-switch in integration tests uses
  `FakeAuthRepository.session.value = ...` (the `MutableStateFlow` inside the
  fake, propagating through `CurrentUser → ProfileAwareCurrentUser`).
- **Auth-safety override**: a separate ADR if the caller-trust model proves
  insufficient in practice.

## Consequences

- ~12 MRs total, ~6–9 weeks.
- ViewModels become thin read-through: `tasks = taskRepo.observeByFilter(filter)
  .stateIn(...)` — no `currentUser`, no `flatMapLatest`.
- AI tools (11 Koog `SimpleTool` implementations) drop `currentUser` from
  their constructors.
- `ProfileAwareCurrentUser` moves **inside** repositories; the DI graph registers
  it once at root scope, and `TaskRepositoryImpl` (and future repositories)
  receive it as a constructor dependency.
- Performance: one extra `StateFlow.distinctUntilChanged().flatMapLatest()` per
  observe call — negligible cost given the Eagerly-seeded StateFlow.

## Known Issues (NOT in this MR scope)

- `TaskRepository.changes: SharedFlow<Task>` emits writes from all users. SyncEngine
  must filter. A future ADR may address whether the repository should filter here.
- `SettingsViewModel:77` uses `currentUser.userId` (raw) instead of
  `scopedUserId` — Phase 0.2 audit required to determine if this is a bug or
  intentional (settings are per-device, not per-user).
- `BackupRepository` hardcodes `UserId.anonymous`.
- `AiUsageViewModel` uses `profileRepository.activeProfileId` on a different axis
  (profile vs userId).

## Links

- `testable-vm` skill — canonical VM constructor shape
- `coroutine-scopes` skill — where `CoroutineScope` lives in the DI graph
- `multi-profile` skill — `ProfileAwareCurrentUser` design
- `InMemoryStore.kt:13` — composition rationale
- `ProfileAwareCurrentUser.kt:36-45` — `scopedUserId` implementation
- `FakeRepositories.kt:1048` — `FakeProfileAwareCurrentUser` factory
- `Daos.kt:99` — `TaskDao.search` (pre-fix, global)
- `Daos.kt:198` — `NoteDao.search` (pre-fix, global)
