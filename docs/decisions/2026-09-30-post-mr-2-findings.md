---
title: "MR-2 Repository Read-Path Isolation — Post-MR-2 Findings"
date: 2026-09-30
status: open
tags: [mr-review, tech-debt]
---

## Context

MR-2 added a Konsist rule `repository read methods are user-scoped` and fixed two unscoped DAO reads in `TaskRepositoryImpl.observeDependencies` and `observeBlockingBy`. The `task_dependencies` cross-ref table has no `user_id` column, so reads must be scoped via the parent `tasks.user_id` through the scoped DAO variants.

## Items Fixed Immediately

- `TaskRepositoryImpl.observeDependencies`: now uses `currentUser.observeForCurrentUser { uid -> taskDao.getDependencyIdsForUser(...) }` instead of the unscoped `getDependencyIdsForTask`
- `TaskRepositoryImpl.observeBlockingBy`: new `TaskDao.getBlockingTaskIdsForUser` DAO method added; VM now uses it via `currentUser.observeForCurrentUser { uid -> taskDao.getBlockingTaskIdsForUser(...) }`
- `FakeAppDatabase` and `FakeRepositories` stubs updated with `getBlockingTaskIdsForUser`

## Items Requiring Future Refactor

| Severity | File | Issue | Estimated |
|---|---|---|---|
| **S-3** | `FakeRepositories.kt:273` | `getBlockingTaskIdsForUser` stub uses `error("not implemented")` — caller exists but FAKE doesn't have the data to exercise it. Low risk since `FakeAppDatabase` has the real implementation. | Not urgent |
| **S-3** | `SyncConfigScreen.kt` | Pre-existing unwired surface (known from MR-1.R). No change in MR-2. | MR-3/4 |

## Open Questions

- `pre-push` hook `EOFException` on `jvmTest`: concurrent Gradle invocations clobber the test binary output stream. Not a code issue — tests pass in isolation. The `check.sh` itself is reliable; only the pre-push hook (which runs all tasks including desktop and mcp-server tests in parallel) hits this. Investigate if `--parallel=false` can be set for the pre-push hook specifically.

## Deferred (Already Tracked)

- Sync config screen unwired — tracked in `2026-09-29-sync-config-screen-has-no-host`
- Phase 2 unwired UI actions — deferred per plan
