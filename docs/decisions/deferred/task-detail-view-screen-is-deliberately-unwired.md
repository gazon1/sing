---
title: "Task Detail View Screen Is Deliberately Unwired"
date: 2000-01-01
status: closed
tags: ["deferred"]
---

**Found in:** the auth-and-sync plan, when `find-unwired-surfaces.py` reported
`TaskDetailViewScreen()` as having no call site. It is the last such finding on
`origin/main`, and it has been there through three separate commits.

**Status: CLOSED — the file was deleted on 2026-10-07.** The time-tracking
refactor (TASK-TIME-01) merged `TaskDetailViewScreen`'s content into
`TaskDetailContent` and pointed both platform graphs at `TaskDetailScreen`.
`TaskDetailViewScreen.kt` no longer exists; the baseline entry has been removed.

**Tracking:** #216 (closed — deleted).

**History:** The screen was kept unwired through three commits:

- `dad11e6b` — "one task detail screen, because a merge had quietly made two". A merge
  had left the project with two detail screens, and the decision was to keep one.
- `f8e38643` — "the three screens read the wall clock, and one of them is not wired".
  This re-added the screen as a clock-suppression carrier.

The clock-suppression behaviour was moved into `TaskDetailContent` as part of the
time-tracking merge, after which the screen was deleted.

**Resolution:** File deleted 2026-10-07 as part of TASK-TIME-01. No further action.

---
