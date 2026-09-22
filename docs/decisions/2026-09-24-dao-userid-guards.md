---
title: "ProjectDao mutation methods require userId in WHERE clause"
date: 2026-09-24
tags: [dao, auth, security, userid]
status: accepted
---

## Context

An audit of `ProjectDao` found five mutation/query methods that lack a `userId` parameter in the WHERE clause:

| Method | Risk |
|---|---|
| `setParent(id, parentId, ts)` | Could modify another user's project if ULID collides |
| `setSortOrder(id, sortOrder, ts)` | Could reorder another user's project |
| `restore(id, ts)` | Could restore another user's trashed project |
| `findByIdempotencyKey(key)` | Could return another user's project if key collides |
| `softDelete(id, ts)` | Could delete another user's project |

While ULID collision is astronomically unlikely, and the existing `getById`/`watchById` patterns already pass `userId`, this is a **defense-in-depth** issue. `TagDao` already has `softDeleteForUser` as a model.

## Decision

Add `*ForUser` variants to `ProjectDao` and update all callers in `ProjectsRepositoryImpl`:

```kotlin
// Daos.kt
@Query("UPDATE projects SET parent_id = :parentId, updated_at = :ts WHERE id = :id AND user_id = :userId")
suspend fun setParentForUser(id: String, parentId: String?, ts: Long, userId: String): Int

@Query("UPDATE projects SET sort_order = :sortOrder, updated_at = :ts WHERE id = :id AND user_id = :userId")
suspend fun setSortOrderForUser(id: String, sortOrder: Int, ts: Long, userId: String): Int

@Query("UPDATE projects SET is_deleted = 0, deleted_at = NULL, updated_at = :ts WHERE id = :id AND user_id = :userId")
suspend fun restoreForUser(id: String, ts: Long, userId: String): Int

@Query("SELECT * FROM projects WHERE idempotency_key = :key AND user_id = :userId LIMIT 1")
suspend fun findByIdempotencyKeyForUser(key: String, userId: String): ProjectEntity?

@Query("UPDATE projects SET is_deleted = 1, deleted_at = :ts, updated_at = :ts WHERE id = :id AND user_id = :userId")
suspend fun softDeleteForUser(id: String, ts: Long, userId: String): Int
```

`ProjectsRepositoryImpl` now propagates `currentUser.scopedUserId.value.value` into each call, and uses the returned row count to enforce ownership:

```kotlin
override suspend fun delete(id: ProjectId): Result<Unit> = runCatching {
    val rows = projectDao.softDeleteForUser(id.value, ts, uid)
    require(rows > 0) { "Project $id not found or not owned by user" }
}
```

## Rationale

- `Int` return from Room `UPDATE`/`DELETE` lets us `require(rows > 0)` — turning "no rows matched" into a business error rather than silent no-op.
- `TaskRepositoryImpl` similarly replaced `taskDao.watchById(id).first()` (Flow subscription for a one-shot read) with `taskDao.getById(id)` (direct suspend read), eliminating three unnecessary Flow allocations.
- `findByIdempotencyKeyForUser` is the highest-risk method — idempotency keys are user-chosen strings that could theoretically collide across profiles.

## Consequences

- Old non-`*ForUser` DAO methods remain in the interface for binary compatibility but are no longer called by production code.
- Fake implementations in `FakeProjectDao` add `mutateForUser` that guards by `userId` before mutating, returning 0 if the entity belongs to a different user.
