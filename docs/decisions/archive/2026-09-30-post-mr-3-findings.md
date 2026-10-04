---
title: Post-MR-3 findings — Nav2 deprecation removal
date: 2026-09-30
status: archived
tags: [mr-post-review, nav2, tech-debt]
---

**Archived 2026-10-05.** This is a post-MR-3 findings list, not an architectural
decision. It left the decision corpus because its content is inventory
that nothing will migrate into a spec, and keeping it in `docs/decisions/`
made findings files look like decisions with pending status.


# Post-MR-3 findings — Nav2 deprecation removal

## Context

MR-3 removed dead-code `@Suppress("DEPRECATION")` and dead-code when-branches from `FabActionResolver`
for the never-reached `AppDestination.Inbox`/`AppDestination.Today` singletons. The shell uses
`AgendaGraph` routes exclusively, so those branches were compile-time noise.

## Items fixed immediately

- **Dead `@Suppress("DEPRECATION")` removed from `FabActionResolver.kt`** — class-level annotation
  covered 2 unused when-branches; replaced with targeted removal of both dead branches.
- **Dead-code when-branches for `Inbox`/`Today` removed** — `when` now only covers
  `AgendaGraph` and `ProjectsGraph`; KDoc updated to document the actual routing.
- **Dead-code tests removed** — `inboxDeprecated_returnsAddTask` and `todayDeprecated_returnsAddTask`
  deleted; `plansDeprecated_returnsAddProject` renamed to `plans_returnsAddProject` and
  `@Suppress("DEPRECATION")` stripped (the suppression was never needed — `Plans` is not deprecated).
- **`@Suppress("DEPRECATION")` stripped from 5 non-deprecated destinations** — `Notes`,
  `Pomodoro`, `Statistics`, `Archive`, `Settings` have no `@Deprecated` annotation; the
  annotations in the test file were unnecessary noise (already removed in MR-3 commit).

## Items requiring future refactor

| # | File | Issue | Category | Severity | Suggested MR | Estimated effort |
|---|------|-------|----------|----------|-------------|-----------------|
| 1 | `feature/sync/presentation/SyncConfigScreen.kt` | Unwired — `SyncConfigScreen()` has no call site; screen exists but is never navigated to | unwired-surface | medium | MR-5 or MR-6 | S |
| 2 | `AppDestination.kt` | 6 deprecated object/class variants remain: `Inbox`, `Today`, `Upcoming`, `TasksByProject`, `TaskDetail`, `TaskDetailCreate`; full removal deferred from MR-3 | deprecated-api | medium | Future MR (post-Phase 1) | M |

## Items deferred (already tracked)

| ADR | Issue |
|-----|-------|
| `2026-09-30-remove-nav2-deprecations.md` | Full removal of 12 deprecated Nav2 destinations; deferred to future MR (requires updating AndroidNavEntries, JvmNavEntries, and all call sites) |
| `2026-09-30-card-level-ai-actions-deferred.md` (closed) | Phase 2 unwired UI actions |
| `2026-09-30-dead-affordances-removed.md` (closed) | TaskMenuBuilder no-op actions |

## Verification

- `./check.sh` — **PASS** (0 detekt findings)
- `./scripts/find-unwired-surfaces.py` — **1 finding**: `SyncConfigScreen` (pre-existing, tracked above)
- Architecture tests — **PASS**
- No new `!!`, no new `runBlocking`, no new `stateIn` — confirmed by detekt clean run
