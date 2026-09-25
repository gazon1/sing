---
title: "Do not adopt MobileNativeFoundation/Store — local-first repository pattern"
date: 2026-09-25
tags: [repository, local-first, sync, architecture]
status: accepted
---

## Context

Requested analysis of [MobileNativeFoundation/Store](https://github.com/MobileNativeFoundation/Store) for potential adoption. The library provides:

- **Fetcher + Repository + Store** — a three-layer cache + network pipeline (memory → disk → network).
- **Source of Truth** abstraction via `MutableStore` / `Store<T>`.
- **Barriers / Coordinators** — async coordination patterns for multi-key requests.
- **Automatic background refresh** with configurable stale windows.

Our project already has a local-first architecture: Room as single source of truth, a batch push outbox (`SyncOutbox`), HLC timestamps for conflict resolution, and ambient `ProfileAwareCurrentUser` for user-scoped data.

## Idea

Instead of importing Store, extract and formalise the patterns already present in the codebase:

1. **Canonical write pipeline** — document `assertCanWrite → upsert → enqueue` in `GenericUserScopedRepository` KDoc.
2. **Cross-user guard** — centralise the 7 duplicated inline guards into `ProfileAwareCurrentUser.assertCanWrite()`.
3. **Dead code removal** — remove `TaskRepository.changes: SharedFlow<Task>` (zero consumers).

## Decision

**Rejected: do not import MobileNativeFoundation/Store.**

The library's core value proposition (multi-layer cache + SourceOfTruth) conflicts with Room-as-SoT:

| Store concept | Our equivalent | Conflict |
|---|---|---|
| `Store<T>.get(key)` | `dao.observe(key)` | We always go through Room; no in-memory cache layer |
| `MutableStore` / `SourceOfTruth` | Room DAOs | Room is already the SoT; wrapping it adds indirection |
| `Fetcher` (network) | `SyncRepository.enqueue()` | We batch-push; Store expects per-key network on miss |
| `Barriers` | None needed | Our use-cases are single-entity or batch; no multi-key coordination |

The library is designed for **network-first with local cache** (like Apollo Client, OkHttp cache). Our architecture is **local-first with background sync** (like Linear, Things 3, Todoist mobile). These are inverted priorities.

Real technical debt found and addressed by this refactor:

- `TaskRepository.changes: SharedFlow<Task>` — dead code, no consumers.
- 7 repositories with duplicated inline cross-user guards.
- No formal documentation of the write pipeline contract.

## Rationale

Adopting Store would require:

- Migrating 8 repositories to a new `MutableStore<Entity>` interface (~150–200 LoC).
- Writing `SourceOfTruth` implementations wrapping each Room DAO (duplicate of existing DAO logic).
- Adding `Barriers` / `Coordinators` for batch operations we already handle via `SyncOutbox`.
- Maintaining a second test suite for the new abstractions.
- Accepting a depencency with different concurrency semantics (Store uses its own `StoreScope`).

Payoff: saving ~3 lines per `create`/`update` is not worth the migration cost and ongoing maintenance.

The three concrete problems above are solved with a 60-line extension file, KDoc, and dead-code removal.

## Consequences

- Write pipeline is now formalised in `GenericUserScopedRepository` KDoc.
- `assertCanWrite` is the single entry point for cross-user write guards across all user-scoped repositories.
- If a future use-case requires a true `SourceOfTruth` abstraction (e.g., migrating part of the data to a KV-store or SqlDelight), the decision to adopt Store or a custom `LocalStore<T>` interface can be revisited.
- `SyncEngine` and `SyncOutbox` are unaffected — they remain the canonical sync pipeline.

## Links

- [MobileNativeFoundation/Store](https://github.com/MobileNativeFoundation/Store)
- `GenericUserScopedRepository` KDoc — canonical write pipeline
- `core/repository/UserScopedWriteExt.kt` — `assertCanWrite` implementation
- `docs/decisions/DIGEST.md`
- skill `singularity-todo-repository-architecture`
