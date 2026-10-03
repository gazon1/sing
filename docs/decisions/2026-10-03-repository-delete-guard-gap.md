---
title: "Repository delete/restore/archive — assertCanWrite vs DAO-level guard"
date: 2026-10-03
status: accepted
tags: [write-path, security, repository]
---

## Context

The write-pipeline skill mandates `assertCanWrite` as the first line of every state-changing
method in user-scoped repositories. However, `delete`, `restore`, and `archive` methods in
`NotesRepositoryImpl` and `TaskRepositoryImpl` do NOT call `assertCanWrite` — they rely
solely on the DAO `*ForUser` filter:

```kotlin
// NotesRepositoryImpl:82 — no assertCanWrite
override suspend fun delete(id: NoteId): Result<Unit> = runCatching {
    val rows = noteDao.softDeleteForUser(id.value, clock.now().toEpochMilliseconds(),
        currentUser.scopedUserId.value.value)
    require(rows > 0) { "Note $id not found or not owned by current user" }
    enqueueFresh(id)
}
```

The DAO `WHERE user_id = :scopedUserId` filter already prevents a user from deleting another
user's entity — the SQL query returns `rows = 0` for a non-owned note, and the
`require(rows > 0)` guard throws.

## Decision

**Option B — Keep DAO-level guard, update skill** (accepted).

The write-pipeline skill is updated to explicitly list `delete`, `archive`, `unarchive`,
and `restore` as operations that do **not** require `assertCanWrite` when:

1. The DAO method uses `WHERE user_id = :scopedUserId` (or equivalent scoping)
2. The method uses `require(rows > 0)` or equivalent to validate the row was found

**Rationale**: `assertCanWrite` is designed for operations where an **external caller**
provides a `userId` that must be validated against the current user. For `delete`/`archive`/
`restore`, the `userId` is always the current user's (`scopedUserId`) — there is no
external input to validate. The DAO filter provides equivalent protection.

**Error semantics differ**: these methods throw `IllegalArgumentException` ("not found")
rather than `CrossUserWriteException` (authorization). Both prevent cross-user writes.
If `CrossUserWriteException` semantics are preferred, add a read-first `assertCanWrite`
— but it costs an extra DB read per operation.

## Consequences

- No code changes required
- Skill accurately reflects existing practice
- Discrepancy between `create`/`update` (throw `CrossUserWriteException`) and
  `delete`/`archive` (throw `IllegalArgumentException`) is documented

## References

- Write-pipeline skill: `assertCanWrite` section
- `NotesRepositoryImpl.delete:82`, `.restore:94`, `.archive:205`
- `TaskRepositoryImpl.delete:268`, `.softDelete:277`
