---
title: "Explicit userId overload removal"
status: accepted
date: 2026-09-22
---

# Explicit userId overload removal

## Context

During Phase 8+ cleanup, the codebase accumulated many explicit `userId: UserId` overloads — methods that took `UserId` as a parameter even though the ambient `ProfileAwareCurrentUser` pattern was already established. These were dead code: grep confirmed zero callers outside test sources.

## Decision

Drop all explicit `userId` overloads from production code. Inline the filter logic directly where `observeByFilter` delegates internally.

### Dropped from `TaskRepository` interface + impl

| Dropped | Reason |
|---|---|
| `softDelete` override (alias for `delete`) | `delete` is already soft-delete via `SoftDeletable` |
| `watchTask(id)` | No callers — auth smell |
| `watchDependencies(taskId)` (impl) | Alias for `observeDependencies` |
| `watchBlockingBy(taskId)` (impl) | Alias for `observeBlockingBy` |
| `watchTasks(userId, filter)` (impl) | Inlined into `observeByFilter` |
| `watchTasksByDate(userId, date)` (impl) | No callers |
| `watchSubtasks(parentId, userId)` (impl) | No callers |

### Dropped from `NotesRepository` interface + impl

| Dropped | Reason |
|---|---|
| `watchAll(userId)` | No callers |
| `watchPinned(userId)` | No callers |
| `watchArchived(userId)` | No callers |
| `watchRootNotes(userId)` | No callers |
| `searchNotes(query, userId)` | No callers |

### `TaskRepository` interface renames

| Old | New |
|---|---|
| `watchDependencies(taskId)` | `observeDependencies(taskId)` |
| `watchBlockingBy(taskId)` | `observeBlockingBy(taskId)` |

## Audit

All explicit `userId` overloads confirmed dead via grep across all production source sets (`shared`, `androidApp`, `desktopApp`, `mcp-server`). No callers found.

## Consequences

- `TaskRepository.delete()` now calls `taskDao.softDelete()` directly instead of delegating to `softDelete()`
- `observeByFilter` now contains the filter-logic inline (was delegated to `watchTasks`)
- All `FakeRepositories` updated to match

## Links

- Parent ADR: [ADR-0015 GenericUserScopedRepository](./2026-09-21-generic-user-scoped-repository.md)
- PR10: `refactor/repository-naming-final` branch
