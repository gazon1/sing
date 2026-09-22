---
title: Drop userId from InternalLinkRepository
date: 2026-09-26
status: accepted
---

## Context

`InternalLinkRepository` exposes three suspend methods that each take an explicit `userId: UserId` parameter:

```kotlin
interface InternalLinkRepository {
    suspend fun searchNotes(userId: UserId, query: String): List<Note>
    suspend fun searchTasks(userId: UserId, query: String): List<Task>
    suspend fun getBacklinkNotes(noteId: String, userId: UserId): List<Note>
}
```

The implementation (`InternalLinkRepositoryImpl`) already receives `ProfileAwareCurrentUser` via constructor injection, but ignores it — instead, callers pass `userId` explicitly.

This is inconsistent with the pattern established by [2026-09-22-explicit-overload-removal](/docs/decisions/2026-09-22-explicit-overload-removal.md) and creates redundant ceremony at every call site.

## Decision

Drop the `userId` parameter from all three `InternalLinkRepository` interface methods.

Resolve `userId` internally via `currentUser.scopedUserId.value`.

## Consequences

- **Breaking change** for `NoteEditor`, `NotePreview`, and their tests — the `userId` argument is removed from `linkRepo.searchNotes(...)`, `linkRepo.searchTasks(...)`, and `linkRepo.getBacklinkNotes(...)` calls.
- `InternalLinkRepositoryImpl` now fully owns the user resolution — consistent with `TagsRepository`, `TaskRepository`, etc.
- No repository contract overloads are needed for this interface (it has no non-Koin callers).
