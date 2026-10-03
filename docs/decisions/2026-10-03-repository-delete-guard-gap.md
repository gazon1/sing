---
title: "Repository delete/restore/archive — assertCanWrite vs DAO-level guard"
date: 2026-10-03
status: open
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
user's entity — the SQL query simply returns `rows = 0` for a non-owned note, and the
`require(rows > 0)` guard throws an `IllegalArgumentException`.

## The Gap

`assertCanWrite` throws `CrossUserWriteException` with a clear authorization error message.
The DAO-filter-only approach throws `IllegalArgumentException` with a generic "not found"
message. Both prevent the cross-user write, but the error semantics differ.

The write-pipeline skill audit checklist requires `assertCanWrite` on all write operations.
These methods currently don't satisfy the checklist, creating an inconsistency.

## Options

### Option A — Add `assertCanWrite` everywhere (read-first)
Read the entity's `userId` before the write, then call `assertCanWrite`:

```kotlin
override suspend fun delete(id: NoteId): Result<Unit> = runCatching {
    val note = noteDao.getByIdForUser(id.value, currentUser.scopedUserId.value.value)
        ?: throw IllegalArgumentException("Note $id not found")
    currentUser.assertCanWrite(entityId = id.value, entityUserId = note.userId)
    noteDao.softDeleteForUser(...)
}
```

**Cost**: one extra DB read per delete/restore/archive call.
**Benefit**: consistent error types, satisfies audit checklist.

### Option B — Keep DAO-level guard, update skill
Acknowledge that DAO-filter + `require(rows > 0)` is an acceptable alternative guard
for operations where the entity ID is the only input. Update the write-pipeline skill
checklist to explicitly mention this pattern.

**Cost**: none (documentation only).
**Risk**: inconsistency in error types across methods within the same repository.

### Option C — Mixed (Option A for delete/archive, Option B for restore)
`delete` and `archive` are high-impact destructive operations — add `assertCanWrite`.
`restore` is low-impact — keep DAO guard.

## Decision

**TBD** — requires product/team decision. Documenting as open ADR.

## References

- `NotesRepositoryImpl.delete:82`, `.restore:94`, `.archive:205`
- `TaskRepositoryImpl.delete:268`, `.softDelete:277`
- Write-pipeline skill audit checklist
