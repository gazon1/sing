---
title: "Repository architecture gaps — Tag userId types, dead ConflictResolver.merge, empty-string sentinels"
date: 2026-09-25
tags: [repository, tech-debt, sync, type-safety]
status: accepted
---

## Context

During the `assertCanWrite` refactor (PR #8) an audit revealed three non-critical but worth-fixing gaps in the repository layer:

1. **`Tag.userId` and `TagGroup.userId` are `String`, not `UserId`** — inconsistent with `Task`, `Project`, `Note`, `Reminder` which all use `UserId` value class. Causes friction at call sites: `UserId()` wrapping needed in `TagsRepositoryImpl`, and `assertCanWrite` cannot be applied uniformly without wrapping.
2. **`ConflictResolver.merge()` is dead code** — implemented but never called in production. `SyncEngine` only uses `ConflictResolver.checksum()`. Keeps `LWW` (last-write-wins) as the active strategy.
3. **`SavedAgendaView.userId` uses empty-string `""` as anonymous sentinel** — unlike every other entity which uses `UserId.anonymous`. Requires ad-hoc normalisation in `RoomSavedAgendaViewsRepository.upsert()`.

## Decision

All three issues are **accepted technical debt** — not blockers for the current PR, but should be addressed in a follow-up cleanup sprint.

### 1. Migrate `Tag.userId` and `TagGroup.userId` to `UserId`

**Target:** `feature/tags/Ids.kt` (`Tag`, `CreateTagInput`) and `feature/tags/domain/model/TagGroup.kt`.

After migration, `assertCanWrite` can be applied to `TagsRepositoryImpl` and `TagGroupRepositoryImpl` without `UserId()` wrapping at call sites. The `TagGroupRepositoryImpl.update` fix (adding `assertCanWrite` with `UserId()` wrapping as a workaround) should be reverted and replaced with the clean call once this migration is done.

**Migration steps:**
- Change `val userId: String` → `val userId: UserId` in domain models.
- Update all call sites that currently pass `String` (mostly repositories and DAOs).
- Room entities already store `userId` as `String` — the `toEntity()` mapper wraps `.value` and `toTagGroup()` unwraps via `UserId()`.
- Add `assertCanWrite` to `TagsRepositoryImpl.create/update` (now clean, no wrapping needed).

**Risk:** medium. Touches domain model, 3 repositories, and DAOs. Should be done in a dedicated PR with full test coverage.

### 2. Remove `ConflictResolver.merge()` or mark it `@Deprecated`

**Option A (preferred):** Delete `merge()` and its test `ConflictResolverTest`. The `checksum()` method stays.

**Option B:** Mark `@Deprecated(message = "Unused in production; LWW remote-wins strategy is active")` and keep the test.

**Decision:** Option A — delete. `ConflictResolver.merge()` was designed for a future CRDT-based merge that was never implemented. Keeping dead code creates maintenance confusion.

**Migration:** Delete the `merge()` function body from `ConflictResolver.kt` and remove `ConflictResolverTest.kt`.

### 3. Normalise `SavedAgendaView.userId` to `UserId.anonymous`

**Target:** `feature/agenda/domain/model/SavedAgendaView.kt` and `feature/agenda/data/RoomSavedAgendaViewsRepository.kt`.

Change `SavedAgendaView.userId` from `String` to `UserId`. Update the repository to remove the `if (view.userId == "")` normalisation branch — `UserId.anonymous` is the canonical anonymous sentinel everywhere else.

**Note:** This changes the wire format for sync. A data migration may be needed if existing local DB rows store `""` instead of `"anonymous"`. Check whether `SavedAgendaView` is included in the backup/restore path.

## Consequences

After all three fixes:
- `assertCanWrite` can be applied uniformly across all user-scoped repositories with no type-wrapping workarounds.
- `ConflictResolver` is leaner and accurately represents the LWW strategy.
- `SavedAgendaView` follows the same `UserId` convention as all other entities.

## Links

- PR #8: `refactor: unify repository write pipeline`
- `GenericUserScopedRepository` KDoc — canonical write pipeline
- `core/sync/ConflictResolver.kt`
- `feature/tags/Ids.kt` — `Tag`, `CreateTagInput`
- `feature/tags/domain/model/TagGroup.kt`
- `feature/agenda/domain/model/SavedAgendaView.kt`
- `feature/agenda/data/RoomSavedAgendaViewsRepository.kt`
