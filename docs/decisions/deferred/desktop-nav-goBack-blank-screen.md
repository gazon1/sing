---
title: "Desktop Nav Goback Blank Screen"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status (re-verified 2026-10-04):** CLOSED and verified 2026-10-04; **#27 closed with the evidence.** `JvmNavEntries.kt:51-67` hoists the per-feature stacks into the shell composition and passes them as `backStack =` into the graphs. `SavedAgendaCreateFlowTest` and `OpenSavedViewShowsMatchingTasksFlowTest` both report 1 test, 0 failures.

**Status:** ✅ RESOLVED (2026-10-04) — fixed by ADR `2026-10-04-navigation-policy`, issue #27 closed.

**Found in:** MR-11, while verifying `OpenSavedViewShowsMatchingTasksFlowTest`.

**Tracked as:** #27

**Symptom:** after tapping the save button in `SavedAgendaScreen` (or `TaskCreateScreen`) and then tapping the back button, the entire desktop app UI goes blank — `SemanticsTree` reports 0 nodes, every `testTag` lookup fails. Navigation itself completes (kermit log shows "Scheduled sync stopped" from clean `onEnd` path), but the compose tree is empty.

**Root cause:** the JVM top-level graph stacks were created with an entry-local
`remember { NavBackStack(...) }` inside each graph composable. That `remember` is scoped to the
entry, and an entry that leaves `NavDisplay`'s visible set (a tab switch, or an outer push) has
its composition disposed — so the back stack object the shell later mutated was no longer the
one the graph rendered from. The back navigation therefore popped an entry that was no longer
backed by a live composition, leaving `NavDisplay` with nothing to show.

**Fix:** the six top-level graph stacks are now created in `createJvmEntryProvider` (shell
composition, outliving any single entry) and passed into the graphs through their `backStack`
parameter. See ADR `2026-10-04-navigation-policy`, §B1 companion.

**Verification:** `SavedAgendaCreateFlowTest` and `OpenSavedViewShowsMatchingTasksFlowTest` — the
two tests this bug blocked — now pass, as does
`CreateTaskFlowTest.a_saved_task_without_a_due_date_appears_under_inbox_no_date`. This is also
what unblocked the Agenda epic's desktop matrix (issue #26).

**Try next (historical):** add a `NavDisplay` debug modifier (e.g., a `Box` with a visible red border when `entries.isEmpty()`) to distinguish "NavDisplay receives empty list" from "compose tree fails below NavDisplay". No longer needed — the cause was above `NavDisplay`, in stack *ownership*, exactly the branch the investigation notes pointed at.

---
