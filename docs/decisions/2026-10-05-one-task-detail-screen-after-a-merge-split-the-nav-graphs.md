---
title: A merge put the two platform nav graphs on different screens, and the coverage matrix called it a hole
date: 2026-10-05
status: accepted
tags: [tasks, navigation, testing, traceability, kotlin-multiplatform]
---

## Context

`TASK-TIME-01` claimed `android` only, and the spec carried a careful comment
explaining why: the time-tracking UI is hosted by `TaskDetailViewScreen`, that
screen is wired in `TasksNavGraph.android.kt`, and the JVM graph routes the same
`TasksRoute.Detail` to a different screen that has no time-tracking section. A
reachability probe had measured it — opening a task on desktop composes five
tagged nodes, and neither the start nor the stop chip is among them.

Measured, specific, and **wrong**. The chain:

1. `b6394207` (2026-09-30) adds `TaskDetailViewScreen` and points the Android
   graph at it.
2. `adc95585` (2026-10-02, "Task detail Screen/Content split") extracts
   `TaskDetailScreen` (navigation shell) plus `TaskDetailContent`, and switches
   **both** graphs to it. Its commit message says so. The refactored content
   renders `extraSections = null` — the time section, subtasks, first-run and AI
   proposals are not in it, and neither are the `isPinned`, project and
   start-date fields that the refactor had just added. `TaskDetailViewScreen`
   survives as an orphan, unreachable.
3. `f2a87c7c` (2026-10-03) merges `refactor/time-hub-ai-proposals` and resolves
   the Android navigation graph by taking the **branch's** version — the
   pre-refactor one. So the split is undone on Android only.

The result is two screens under one route and one ViewModel, and the divergence
runs in **both** directions: Android kept time tracking and lost `isPinned`,
project and start-date; the JVM graph kept those and lost four sections. The
whole `timetracking` feature is in `commonMain` — ten files including the
repository and its fake — so there was never a platform in it to be "only".

## Idea

Two readings were available and the framing chose wrongly. "Two screens, one
name" reads as an undocumented architectural split: rename, record, ship. The
measurement says otherwise — one platform was missing a feature and nobody knew,
and a matrix that renders that as `○` cannot tell "not automated" from "not
built".

## Decision

Merge the two compositions into `TaskDetailContent`, point both graphs at
`TaskDetailScreen`, and delete `TaskDetailViewScreen`. The phase-4 *structure* was
right and is kept; its *content* was incomplete, and the incomplete half is what
the merge then made permanent.

Restored into the shared composition, in this order: first-run nudge, AI
proposal cards (lifted out of the old file's `private` scope into
`TaskDetailProposalSection`, because a `private` composable is how a section
ends up rendering on one platform and not the other), tags, recurrence,
checklist, subtasks, attachments, linked backlinks, time tracking, logbook — plus
the manual time-entry sheet and the `estimateMinutes` field. Android gains
`isPinned`, project and start-date as a side effect, which is the point: one
screen, the union of both.

`TASK-TIME-01` now claims both targets, and both cells are holes.

## Rationale

The lesson is about probes, and it is written into the kiwi skill next to the
recipe: **a failed reachability probe measures the code in front of it, not the
platform.** The probe was correct — the section genuinely was not in the tree.
The inference was not. Before narrowing a spec to one target, the question to ask
is whether the feature lives in `commonMain`; if it does, a missing node is a
hole in the code, and narrowing the spec makes the spec correct against code that
is wrong. That is the second time an irreversible-looking tidy-up was the wrong
move: the first was the `testTag`s added for a desktop carrier the probe "ruled
out" and then removed. Those tags are back, and their removal is what would have
made the whole thing invisible.

The matrix is a derived artefact and it was **right about its own inputs and
useless about the world**: the coverage was correct, the claim was correct, the
result was `○`, and all three together said "nobody wrote the test yet" about a
feature that did not exist on that platform. A hole count cannot tell those apart,
so the guard against this is a test, not a floor. `TaskDetailTimeTrackingSectionTest`
renders the section and clicks the chip; it is deliberately **not** a scenario
carrier, and the reason is in its KDoc.

## Consequences

Desktop users get a timer back, and Android gets pinned/project/start-date. The
screen that used to be two is one, so the two graphs cannot drift apart again
without a compile error in at least one of them.

`TASK-TIME-01`'s desktop cell is a hole and stays one. The timer will not start
under the anonymous desktop harness session, and `TaskTimeSlot.start()` discards
the failure with an empty `onFailure { }` — so the failure is indistinguishable
from a dead control, and no honest assertion can be written until that is routed
into an error surface. Filed as #196. Claiming the cell anyway is right: the
feature *is* on desktop now, and a true claim with an unsupplied carrier is a
hole, which is the state the system is built to represent.

The ratchet moved 31 -> 32 for this and the floor was raised with the reason
written into `config/docs/traceability-ratchet.json`, because a raised floor is
only defensible if the reason is in the repository.

## Links

- `infra/kiwi/scenarios/tasks/timetracking/TASK-TIME-01.yaml` — the spec and its history
- `shared/.../screen/TaskDetailContent.kt` — the merged composition
- `shared/.../components/detail/TaskDetailProposalSection.kt` — lifted out of private scope
- #196 — the swallowed time-tracking failure
- #187 — the split, as originally filed
- `2026-10-05-scenario-coverage-ratchets-one-directionally.md` — the floor this moved
