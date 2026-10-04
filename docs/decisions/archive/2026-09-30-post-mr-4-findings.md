---
title: Post-MR-4 findings — Repository naming and package convention
date: 2026-09-30
status: archived
tags: [mr-post-review, repository, tech-debt]
---

**Archived 2026-10-05.** This is a post-MR-4 findings list, not an architectural
decision. It left the decision corpus because its content is inventory
that nothing will migrate into a spec, and keeping it in `docs/decisions/`
made findings files look like decisions with pending status.


# Post-MR-4 findings — Repository naming and package convention

## Context

MR-4 renamed `Room*` → `*RepositoryImpl` and moved implementations to `.data/` packages.
15 files changed, net −2677 lines (mostly deletion of `Room`-prefixed files).

## Items fixed immediately

- **Konsist rule fixed**: `repository implementations are imported only from core di` updated
  to also allow feature DI modules (`feature.agenda`, `feature.tasks`, `feature.notes`) to import
  `*RepositoryImpl` from their own `.data/` subpackages.
- **`@file:Suppress("TooManyFunctions")` added** to `NotesRepositoryImpl` (28 functions) and
  `ReminderRepositoryImpl` (12 functions) — same suppression already present on original impl files.
- **Duplicate import cleaned** in `NotesDiModule.kt` and `CoreDiModule.kt`.

## Items requiring future refactor

| # | File | Issue | Category | Severity | Suggested MR | Estimated effort |
|---|------|-------|----------|----------|-------------|-----------------|
| 1 | `feature/sync/presentation/SyncConfigScreen.kt` | Unwired — `SyncConfigScreen()` has no call site; screen exists but never navigated to | unwired-surface | medium | MR-5 or MR-6 | S |

## Items deferred (already tracked)

| ADR | Issue |
|-----|-------|
| `2026-09-30-repository-naming-and-package-convention.md` | This MR — naming and package convention |
| `2026-09-30-post-mr-3-findings.md` | `SyncConfigScreen` unwired (same finding, re-listed) |
| `2026-09-30-remove-nav2-deprecations.md` | 6 remaining deprecated Nav2 variants for future MR |

## Verification

- `./check.sh` — **PASS** (0 detekt findings, all ktlint clean)
- `./scripts/find-unwired-surfaces.py` — **1 finding**: `SyncConfigScreen` (pre-existing, tracked above)
- Architecture tests — **PASS**
- No new `!!`, no new `runBlocking`, no new `stateIn` — confirmed by detekt clean run
