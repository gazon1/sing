---
title: "Remove FakeTaskRepository legacy observation methods"
date: 2026-09-25
tags: [testing, fakes, cleanup]
status: accepted
---

## Context

During PR10 cleanup, production interfaces were updated to remove ambiguous `userId` overloads. The fake implementations were kept as a "binary compatibility layer" — but since all production callers migrated, these methods became dead code in tests.

Four methods in `FakeTaskRepository` had no callers outside `FakeRepositories.kt` itself:

| Method | Lines | Interface? |
|---|---|---|
| `watchTasks(userId, filter)` | 534-549 | No — not in `TaskRepository` |
| `watchTask(id)` | 551-552 | No — not in `TaskRepository` |
| `watchTasksByDate(userId, date)` | 554-561 | No — not in `TaskRepository` |
| `watchSubtasks(parentId, userId)` | 563-565 | No — not in `TaskRepository` |

A grep audit confirmed zero external call sites.

## Decision

Delete the four methods from `FakeTaskRepository`. No replacement — callers should use the canonical `TaskRepository` interface methods (`observeAll()`, `observeByFilter()`, etc.) which are already implemented via `currentUser.observeForCurrentUser { uid -> ... }`.

## Rationale

- Dead code in tests adds maintenance burden without coverage benefit.
- `FakeTaskRepository` already exposes `tasks: StateFlow<Map<String, Task>>` for direct store inspection when needed.
- Kover counts these methods as covered (the implementations exist), inflating coverage without testing real behavior.

## Consequences

- `FakeTaskRepository` is now ~30 lines shorter.
- No breaking change — these methods were never called externally.
- `Clock` import may become unused in `FakeRepositories.kt` if not used elsewhere.
