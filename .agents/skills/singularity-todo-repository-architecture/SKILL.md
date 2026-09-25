---
name: singularity-todo-repository-architecture
description: Canonical repository-architecture invariants in this KMP project. Use when creating a new repository (Room DAO + RepositoryImpl + Fake), auditing DAO mutations for missing userId WHERE clauses, replacing `watchById().first()` one-shot reads, or designing atomic bootstrap/seed patterns. Encodes the Phase 11-12 audit findings as detekt-enforced rules.
---

# Repository Architecture

This skill captures architectural invariants for Room-backed repositories in this KMP project. Each invariant exists because the audit during Phase 11-12 found a specific bug or anti-pattern that broke the principle of "the repository owns auth-safety, not the call site."

## Five invariants

### 1. DAO mutations include `userId` in the WHERE clause

Every Room mutation method on a DAO that owns user-scoped data MUST take `userId: String` as the last parameter and add `AND user_id = :userId` to the WHERE clause. Returns `Int` (rows affected) so callers can enforce ownership.

```kotlin
// ✅ Right — defense-in-depth + observable enforcement
@Query("""
    UPDATE projects
       SET parent_id = :parentId, updated_at = :ts
     WHERE id = :id AND user_id = :userId
""")
suspend fun setParentForUser(
    id: String,
    parentId: String?,
    ts: Long,
    userId: String,
): Int

// Repository impl propagates currentUser and enforces ownership
override suspend fun setParent(id: ProjectId, parentId: ProjectId?, updatedAt: Long) {
    val uid = currentUser.scopedUserId.value.value
    projectDao.setParentForUser(id.value, parentId?.value, updatedAt, uid)
}

// ❌ Wrong — non-`ForUser` variant: silently mutates another user's row if ULID collides
suspend fun setParent(id: String, parentId: String?, ts: Long)
```

**Why**: While ULID collisions are astronomically unlikely, `Int` return lets callers do `require(rows > 0)` and turn "no row matched" into a business error. Matches Spring Data's `@Modifying @Query` convention where every write includes the principal.

**Audit check** (run on every repository impl):

```bash
# Find DAO mutations without userId guard
grep -n "suspend fun.*UPDATE\|suspend fun.*DELETE" shared/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt
# Each line must end with `: Int` and have `userId: String` in its signature
```

### 2. Atomic bootstrap returns an immutable result carrier

A bootstrap method that does **seed + lookup + activate** must return the activated id (or any lookup result) so callers don't re-issue a Flow subscription. This eliminates the race window between bootstrap and subsequent Flow observation.

```kotlin
// ✅ Right — atomic result, no race
data class ProfileBootstrapResult(
    val created: Set<String>,
    val activated: ProfileId?,
)

suspend fun run(
    seedExtras: List<SeedProfile> = emptyList(),
    activateName: String? = null,
): ProfileBootstrapResult {
    repository.ensureDefaults(extraProfiles = seedTuples)
    val profiles = repository.observeAll().first().associateBy { it.name }
    val activated = profiles[activateName]?.also { repository.switchTo(it.id) }
    return ProfileBootstrapResult(
        created = profiles.keys - alreadyExisted.keys,
        activated = activated?.id,
    )
}

// Caller reads directly — no separate .first() call
val agentId = result.activated?.let { it.value }

// ❌ Wrong — bootstrap + separate Flow lookup = race window
bootstrapper.run(seedExtras, activateName)
val agentId = repository.observeAll().first()  // ← separate call, race-prone
    .first { it.name == "AI Agent" }.id.value
```

**Why**: Between `bootstrapper.run()` returning and the caller's `.first()`, a concurrent coroutine could observe inconsistent state. With `ProfileBootstrapResult` the activate id is captured atomically.

### 3. `watchById().first()` is a one-shot read anti-pattern

`Flow.first()` on a `watchById` flow subscribes the Flow just to read one value — wasteful and risks discarding the initial value. Use `getById(id)` for one-shot reads.

```kotlin
// ✅ Right — direct suspend read
override suspend fun toggleComplete(id: TaskId): Result<Unit> = runCatching {
    val task = taskDao.getById(id.value) ?: return@runCatching
    // ...
}

// ❌ Wrong — Flow allocation just to read one value
override suspend fun toggleComplete(id: TaskId): Result<Unit> = runCatching {
    val task = taskDao.watchById(id.value).first() ?: return@runCatching
    // ...
}
```

**Audit check**:

```bash
# Find any repository impl that does this
grep -rn "watchById.*\.first()\|watchByIdForUser.*\.first()" shared/src/commonMain/kotlin/com/singularity/todo/feature/
# Expected: 0 matches (post-Phase-12)
```

### 4. No static `ProfileAwareCurrentUser` access

`ProfileAwareCurrentUser` is a pure DI class. The companion `scopedUserId` / `current` / `instance` / `setInstance()` accessors were removed in PR12b. Detekt rule `NoStaticProfileAwareCurrentUser` enforces this.

```kotlin
// ✅ Right — constructor injection
class CreateTaskTool(
    private val taskRepository: TaskRepository,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) {
    val userId = currentUser.scopedUserId.value
}

// ❌ Wrong — static singleton access (caught by detekt)
class CreateTaskTool(...) {
    val userId = ProfileAwareCurrentUser.scopedUserId.value
}
```

**Detekt rule**: `NoStaticProfileAwareCurrentUser` in `detekt-rules/`. Auto-runs as part of `./gradlew :shared:detekt`.

### 5. Fakes implement the production interface — no legacy overloads

`Fake*Repository` classes implement `GenericUserScopedRepository<E, ID>` and the feature-specific interface only. Dead `userId`-overload methods (legacy migration scaffolding) must be removed once production migration is complete.

```kotlin
// ✅ Right — Fake implements interface only
class FakeTaskRepository(
    private val dao: TaskDao = InMemoryTaskDao(),
    private val explicitCurrentUser: ProfileAwareCurrentUser? = null,
) : TaskRepository {
    // ... only TaskRepository members
}

// ❌ Wrong — leftover legacy method (not in TaskRepository)
fun watchTasks(userId: UserId, filter: TaskFilter): Flow<List<Task>> { ... }
```

**Audit check before deletion**:

```bash
# Confirm zero callers of a method before deleting
grep -rn "FakeTaskRepository.*watchTasks\|\.watchTasksByDate\b\|\.watchSubtasks\b" shared/src/ --include="*.kt" \
    | grep -v "FakeRepositories.kt"
# Expected: empty (or only docstrings/comments)
```

### 6. Write pipeline: `assertCanWrite` → `dao.upsert` → `syncRepository.enqueue`

Every `create`/`update`/`restore` follows this exact sequence. See `singularity-todo-write-pipeline` skill for full details.

```kotlin
// ✅ Right
override suspend fun create(item: Task): Result<Task> = runCatching {
    currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
    val toInsert = item.copy(userId = currentUser.scopedUserId.value)
    taskDao.upsert(toInsert.toEntity())
    syncRepository.enqueue(toInsert)
    toInsert
}

// ❌ Wrong — missing enqueue (sync is silently dropped)
override suspend fun create(item: Task): Result<Task> = runCatching {
    currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
    taskDao.upsert(item.toEntity())
    // syncRepository.enqueue missing!
}

// ❌ Wrong — guard after upsert (race window)
override suspend fun create(item: Task): Result<Task> = runCatching {
    taskDao.upsert(item.toEntity())              // ← upsert before guard
    currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
    syncRepository.enqueue(item)
}
```

**Note on `assertCanWrite` vs DAO guards:** Both are required. `assertCanWrite` is authorization (business logic layer). DAO `*ForUser` methods are data isolation (SQL layer). The guard throws `CrossUserWriteException`; the DAO returns `Int` for ownership enforcement.

## Repository template

A canonical repository impl has **4 layers**:

```
domain/port/ProjectRepository.kt          — interface only, no implementation
data/ProjectsRepositoryImpl.kt            — Room impl, currentUser injected, *ForUser propagation
test/fakes/FakeAppDatabase.kt             — FakeProjectDao: stub implementations of DAO methods
test/fakes/FakeRepositories.kt            — FakeProjectsRepository: in-memory implementation
```

Mandatory constructor signature:

```kotlin
class ProjectsRepositoryImpl(
    private val projectDao: ProjectDao,
    private val clock: Clock,
    private val currentUser: ProfileAwareCurrentUser,
) : ProjectsRepository {
    // ...
}
```

`currentUser` is mandatory — never optional, never lazy-resolved via a static singleton.

## Fake DAO implementation

`FakeProjectDao` lives in `test/fakes/FakeAppDatabase.kt`. When adding a new `*ForUser` method to a real DAO, also add it to the fake DAO:

```kotlin
// FakeProjectDao
override suspend fun setParentForUser(
    id: String, parentId: String?, ts: Long, userId: String,
): Int = mutateForUser(id, userId) { it.copy(parentId = parentId, updatedAt = ts) }

private fun mutateForUser(
    id: String, userId: String, fn: (ProjectEntity) -> ProjectEntity,
): Int {
    store.update { current ->
        val existing = current[id] ?: return@update current
        if (existing.userId != userId) return@update current
        current + (id to fn(existing))
    }
    return 1
}
```

## Audit checklist

Before merging any PR that touches a repository:

- [ ] All new DAO mutations have `userId: String` in WHERE clause AND return `Int`
- [ ] Repository impl propagates `currentUser.scopedUserId.value.value` into every DAO mutation
- [ ] No new `taskDao.watchById(id).first()` calls (use `getById`)
- [ ] No new `ProfileAwareCurrentUser.scopedUserId` static reads (detekt will catch this)
- [ ] Fake DAO has stub implementations of any new `*ForUser` methods
- [ ] `create`/`update`/`restore` follow `assertCanWrite` → `dao.upsert` → `syncRepository.enqueue`
- [ ] `assertCanWrite` throws `CrossUserWriteException` (explicit `if/throw`, NOT `require()`)
- [ ] Detekt 0 new findings (run `./gradlew :shared:detekt`)

## Related Skills

- `singularity-todo-write-pipeline` — the write pipeline pattern (assertCanWrite → upsert → enqueue)
- `singularity-todo-feature-scaffold` — 7-file feature template
- `singularity-todo-clean-architecture-audit` — layer-boundary grep checks
- `singularity-todo-coroutine-scopes` — `createBackgroundScope()` placement
- `singularity-todo-decisions-workflow` — when to write an ADR for repository decisions
- `singularity-todo-kmp-platform-specific` — Room composite PK SQL patterns
- ADR: `docs/decisions/2026-09-23-mcp-bootstrap-result-pattern.md`
- ADR: `docs/decisions/2026-09-24-dao-userid-guards.md`
- ADR: `docs/decisions/2026-09-24-profile-aware-current-user-di.md`
- ADR: `docs/decisions/2026-09-25-no-store-library-local-first-pattern.md`
- ADR: `docs/decisions/2026-09-25-repository-architecture-gaps.md`
