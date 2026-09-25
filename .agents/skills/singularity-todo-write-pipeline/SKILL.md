---
name: singularity-todo-write-pipeline
description: Canonical write pipeline for user-scoped repositories: assertCanWrite guard → Room upsert → SyncRepository.enqueue. Use when implementing create/update/restore in any repository, reviewing a PR for missing sync calls, or fixing cross-user write vulnerabilities.
---

# Write Pipeline — Canonical Pattern

Every `create`, `update`, and `restore` in a user-scoped repository follows this exact three-step sequence.

## The Pattern

```kotlin
// 1. Guard — prevents cross-user writes
currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)

// 2. Local write — Room is the single source of truth
dao.upsert(item.toEntity())

// 3. Sync — schedules remote push via outbox
syncRepository.enqueue(item)
```

Domain-specific side effects (outgoing links, counter updates, cross-refs) go **between step 2 and step 3**.

## The Guard — `assertCanWrite`

**File:** `core/repository/UserScopedWriteExt.kt`

```kotlin
fun ProfileAwareCurrentUser.assertCanWrite(
    entityId: String,
    entityUserId: UserId,
)
```

Throws `CrossUserWriteException` when `entityUserId` is neither the current scoped user nor `UserId.anonymous`.

**Usage in `create`:**
```kotlin
override suspend fun create(item: Task): Result<Task> = runCatching {
    currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
    val toInsert = item.copy(userId = currentUser.scopedUserId.value)
    taskDao.upsert(toInsert.toEntity())
    syncRepository.enqueue(toInsert)
    toInsert
}
```

**Usage in `update`:**
```kotlin
override suspend fun update(item: Task): Result<Task> = runCatching {
    currentUser.assertCanWrite(entityId = item.syncId, entityUserId = item.userId)
    taskDao.upsert(item.toEntity())
    syncRepository.enqueue(item)
    item
}
```

## When `assertCanWrite` is NOT needed

| Case | Why |
|---|---|
| `create` generates entity internally (e.g., `TagGroupRepositoryImpl`) | No external input; `userId` is stamped from `scopedUserId` |
| Sync bootstrap / pull handler calls `dao.upsert()` directly | Already validated by upstream sync protocol |
| `restore` re-creates from deleted state | Stamps with current user, entity is already owned |

## Guard vs DAO-level filter

Two layers of protection exist:

1. **`assertCanWrite`** — authorization check. Throws `CrossUserWriteException` if the entity's `userId` doesn't match the current profile.
2. **DAO `*ForUser` methods** — data isolation. SQL `WHERE user_id = :userId` prevents leaking data across users even if the guard is bypassed.

Both are required. The guard is the first line of defense and provides a clear error. The DAO filter is defense-in-depth.

## `userId` type matters

| Entity | `userId` type | Guard call |
|---|---|---|
| `Task`, `Project`, `Note`, `Reminder` | `UserId` | `assertCanWrite(id, item.userId)` |
| `Tag` | `String` | `assertCanWrite(id, UserId(item.userId))` ← wrap |
| `TagGroup` | `String` | `assertCanWrite(id, UserId(existing.userId))` ← wrap |
| `SavedAgendaView` | `String` | `assertCanWrite(id, UserId(uid.value))` after normalisation |

**ADR:** `docs/decisions/2026-09-25-repository-architecture-gaps.md` — `Tag` and `TagGroup should migrate to `UserId`.

## Testing `assertCanWrite`

```kotlin
// shared/src/commonTest/kotlin/com/singularity/todo/core/repository/UserScopedWriteExtTest.kt
class UserScopedWriteExtTest {
    @Test
    fun matchesCurrentUser_doesNotThrow() {
        val cu = FakeProfileAwareCurrentUser(initialUserId = UserId("u-1"))
        cu.assertCanWrite(entityId = "task-1", entityUserId = UserId("u-1"))
    }

    @Test
    fun anonymousUser_isAccepted() {
        val cu = FakeProfileAwareCurrentUser(initialUserId = UserId("u-1"))
        cu.assertCanWrite(entityId = "task-1", entityUserId = UserId.anonymous)
    }

    @Test
    fun differentUser_throws() {
        val cu = FakeProfileAwareCurrentUser(initialUserId = UserId("u-1"))
        assertFailsWith<CrossUserWriteException> {
            cu.assertCanWrite(entityId = "task-1", entityUserId = UserId("u-2"))
        }
    }
}
```

## Audit Checklist

Before merging any PR that touches repository `create`/`update`/`restore`:

- [ ] `assertCanWrite` is the **first line** before any `dao.upsert`
- [ ] `syncRepository.enqueue(item)` is called **after** `dao.upsert`
- [ ] No `require()` or manual `if (userId != ...)` — use `assertCanWrite`
- [ ] `assertCanWrite` throws `CrossUserWriteException`, not `IllegalArgumentException` (use explicit `if/throw`, not `require()`)
- [ ] Stamping (`item.copy(userId = currentUser.scopedUserId.value)`) happens **after** the guard
- [ ] `userId` type mismatch handled — wrap `String`-typed `userId` with `UserId()` at call site

## Related Skills

- `singularity-todo-repository-architecture` — five invariants for Room-backed repositories
- `singularity-todo-test-helpers` — testing with `FakeProfileAwareCurrentUser`
- `singularity-todo-sync` — `SyncRepository.enqueue()` semantics, outbox batch push
- ADR: `docs/decisions/2026-09-25-no-store-library-local-first-pattern.md`
- ADR: `docs/decisions/2026-09-25-repository-architecture-gaps.md`
