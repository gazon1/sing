---
title: "GenericUserScopedRepository<E, ID> — unified CRUD base for all user-scoped repositories"
date: 2026-09-21
tags: [repository, architecture, kotlin, kmp]
status: accepted
---

## Context

13 repository interfaces in the shared module all implement the same CRUD shape
(`observeAll`, `getById`, `create`, `update`, `delete`) but under different names:

- `watchNotesForCurrentUser` / `watchNotes` / `watchAllForCurrentUser` → `observeAll`
- `watchNoteForCurrentUser` / `watchByIdForCurrentUser` / `getNoteByIdForCurrentUser` → `observe(id)`
- `getNoteByIdForCurrentUser` / `getByIdForCurrentUser` / `getById` → `get(id)`
- `softDelete` → `delete`
- `restore` / `unarchive` → `restore`
- `searchNotesForCurrentUser` → `search(query)`

`TaskRepository` already extended `UserScopedRepository<T, ID>`. The other 12 did not,
choosing instead to re-declare the same methods with entity-specific prefixes.

The chaos makes AI-tool generation inconsistent and raises the cost of onboarding new
consumers (every method name must be memorised per-entity).

## Idea

Introduce two narrow interfaces and one extension function:

```kotlin
interface GenericUserScopedRepository<E, ID> {
    fun observeAll(): Flow<List<E>>
    fun observe(id: ID): Flow<E?>
    suspend fun get(id: ID): E?
    suspend fun create(item: E): Result<E>
    suspend fun update(item: E): Result<E>
    suspend fun delete(id: ID): Result<Unit>
}

interface SoftDeletable<E, ID> {
    suspend fun restore(id: ID): Result<Unit>
}

suspend fun <E, ID> GenericUserScopedRepository<E, ID>.exists(id: ID): Boolean =
    get(id) != null
```

Every user-scoped repository extends `GenericUserScopedRepository` (and optionally
`SoftDeletable`). Domain-specific methods (search, archive, parent-child links,
sync fan-out) stay on the entity-specific interface.

The old `UserScopedRepository<T, ID>` becomes a `typealias` pointing to the new
base for one release, then is removed.

## Decision

- `create(item): Result<E>` and `update(item): Result<E>` return the saved entity
  (Spring Data / Django ORM convention). Room implementations return `Result.success(fromRow(toRow(item)))`.
- `ForCurrentUser` suffix is dropped from all method names — the type already guarantees user-scope via ambient `ProfileAwareCurrentUser`.
- Explicit `userId` overloads (`watchNotes(userId)`, `watchProjects(userId)`) are
  **renamed but not removed** in this refactor. Removal is deferred to a later
  commit after all consumers migrate.
- `Searchable<E>` mixin is **deferred** until a second entity acquires free-text
  search. Currently only `NotesRepository` has it.
- `SoftDeletable<E, ID>` has exactly one method: `restore(id)`.
  `archive(id)` / `observeArchived()` stay on `NotesRepository` as domain methods.
- Extension functions (not default interface methods) are preferred for cross-cutting
  helpers (`exists`). Default methods are reserved for semantics that ALL
  implementations must share.
- Outlier repositories (Auth, Backup, Profile, Pomodoro, Archive,
  InternalLink) receive only method renames — no generic base.

## Rationale

**`Result<E>` over `Result<Unit>`:** Spring Data `save()` returns the saved entity.
Room can do the same cheaply. The caller gets `id`, `createdAt`, sync metadata without
a second round-trip. Price: ~3 lines per Room impl.

**Drop `ForCurrentUser` suffix:** Kotlin/AndroidX convention — type carries the
constraint, name does not duplicate it. `ProfileAwareCurrentUser` is a constructor
parameter on every consumer; ambient resolution is intentional.

**Extension over default method for `exists`:** Kotlin idiom. Keeps the interface
surface minimal; extension is visible where called. Default methods are for semantics
every impl must share.

**YAGNI on `Searchable`:** Only `NotesRepository` has search today. A mixin with
one consumer is a speculative abstraction. Deferred.

**YAGNI on `batchDelete` / `findOrThrow`:** Not yet needed by any consumer.
Added when the first caller appears.

**Deferred userId overload removal:** 12 fakes in `FakeRepositories.kt` implement
both the ambient and explicit-userId overloads. Dropping the explicit overload in
one PR would force all fakes + AI-tools to be updated simultaneously. Renaming both
now and removing the explicit one later is safer.

**SoftDeletable = one method:** `restore` is the only operation that is both
(a) common across Task, Project, and Note and (b) genuinely separate from the
base `delete`. `archive` is Note-specific — it belongs on `NotesRepository`.

## Consequences

- **Always** use `GenericUserScopedRepository<E, ID>` as the base for any new
  user-scoped repository. Extend `SoftDeletable` when the entity supports soft-delete + restore.
- **Never** add `ForCurrentUser` suffix to new method names — the type guarantees user-scope.
- **Never** return `Result<Unit>` from `create` / `update` — return `Result<E>`.
- **When** a second entity acquires free-text search — extract `Searchable<E>` mixin
  and move `search(query)` off the entity-specific interface.
- **When** adding a cross-cutting repository helper (batch op, transactional wrap) —
  prefer an extension function first; only promote to default interface method when
  a second implementation needs to override it.
- The old `UserScopedRepository<T, ID>` typealias is removed in the cleanup commit
  (PR 8). Until then, both names are valid.
- Fakes in `test/fakes/FakeRepositories.kt` simplify: one constructor parameter
  per entity, one `store` map, overrides match the interface. Explicit-userId
  overloads remain deprecated, not removed.
- `PomodoroRepository` has 0 production call sites. It is a candidate for deletion
  (tracked separately, out of scope for this refactor).

## Alternatives Considered

**Ktorm DSL** — typed SQL builder layer. Rejected: requires rewriting every repository
implementation; the project uses Room for persistence.

**Room per-DAO (status quo)** — each entity gets a hand-written DAO interface with
custom method names. Rejected: the 13 repositories all have the same 5–7 CRUD
methods — the duplication is the problem this ADR solves.

**Prisma style (generated repositories)** — code-generated `findMany`, `create`,
`update` from schema. Rejected: the project has no code generation pipeline for
this; adding one is out of scope.

**Single mega-interface with all methods** — `Repository<T, ID>` with 20+ methods.
Rejected: violates Interface Segregation Principle. Domain-specific methods
(`setDependencies`, `setPinned`, `archive`) would force every impl to stub them.

## Links

- New file: `shared/src/commonMain/kotlin/com/singularity/todo/core/repository/GenericUserScopedRepository.kt`
- Deprecated: `shared/src/commonMain/kotlin/com/singularity/todo/core/repository/UserScopedRepository.kt` (→ typealias)
- Related decisions: `2026-09-05-koin-suspend-bridge.md`, `2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`
- Migration commits: PR1–PR8 (see commit messages)
