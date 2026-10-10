---
title: "Sync Config Screen Unwired"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04. `SyncConfigScreen.kt` is gone (`58c82f80`), and `find-unwired-surfaces.py` runs blocking in `ci.yml:226` with no `continue-on-error`. **Closed #27's sibling bookkeeping at the same time** — the gate is the deliverable here and it is enforced.

**Found in:** MR-1 retrospective, `scripts/find-unwired-surfaces.py`. Pre-existing;
not a regression from the desktop UI work.

`feature/sync/presentation/SyncConfigScreen.kt` was a public `@Composable` with no
call site. `SyncViewModel` was fully built and registered.

**Status: RESOLVED.** `SyncConfigScreen.kt` was deleted. The `find-unwired-surfaces`
gate is now blocking in CI (Phase 1.1, PR-2).

---
