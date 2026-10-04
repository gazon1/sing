---
title: "MR-2 Repository Read-Path Isolation"
date: 2026-09-30
status: accepted
tags: [mr, architecture, repository]
---

## Context

Repository read methods that return data for multiple entities must be scoped to the current user — either through a scoped DAO query (`user_id` in WHERE) or by combining with `currentUserFlow`. An unscoped read is a cross-profile data leak.

Two read paths in `TaskRepositoryImpl` were identified as unscoped:
- `observeDependencies()` called `TaskDao.getDependencyIdsForTask()` — no userId filter
- `observeBlockingBy()` called `TaskDao.getBlockingTaskIdsForTask()` — no userId filter

Both cross-ref tables (`task_dependencies`) have no `user_id` column of their own. The fix is to use the already-available scoped DAO variants that join against `tasks.user_id`.

## Decision

1. **New Konsist rule** `repository read methods are user-scoped` added to `ArchitectureTest.kt`. It scans all `*RepositoryImpl` files and flags any DAO `@Query` call (non-mutation) that lacks `user_id` in its SQL, unless the method is allowlisted.

2. **Allowlist** — three DAO methods are safe "by construction" because the caller already guarantees the task belongs to the current user:
   - `TaskDao.getDependencyIdsForTask` — used in `observeDependencies`, guarded by `currentUserFlow` wrapper
   - `TaskDao.getDependencyIdsForUser` — already scoped (had `user_id`)
   - `TaskDao.getBlockingTaskIdsForTask` — same reasoning as `getDependencyIdsForTask`
   Note: after the fix, `getBlockingTaskIdsForTask` is no longer called directly from `TaskRepositoryImpl` — it's replaced by `getBlockingTaskIdsForUser`

3. **Sweep** — `TaskRepositoryImpl.observeDependencies` and `observeBlockingBy` now wrap the DAO calls in `currentUser.observeForCurrentUser { uid -> ... }` and use the scoped DAO variants.

4. **New DAO method** `TaskDao.getBlockingTaskIdsForUser` added to `Daos.kt` — needed because only `getBlockingTaskIdsForTask` (unscoped) existed.

5. **Test fakes updated** — `FakeAppDatabase` and `FakeRepositories` both stub `getBlockingTaskIdsForUser`.

## Consequences

- `TaskRepositoryImpl` now correctly scopes dependency reads to the current user
- New Konsist rule will catch future unscoped reads at PR level
- `getBlockingTaskIdsForUser` required a new DAO method (schema unchanged — Room migration not needed, the cross-ref table has no userId column)

## Resolution (accepted)

Resolved 2026-10-05: the Konsist rule landed and is live.

Verified: `arch/ArchitectureTest.kt:354` declares `repository read methods are user-scoped`
— the rule named in this ADR's Decision section, with the allowlist this ADR specified.
It runs in `:shared:jvmTest`, which passes.
