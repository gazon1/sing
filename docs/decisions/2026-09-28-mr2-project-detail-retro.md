---
title: "MR-2 retro — the god-VM split was rejected once already, and the real defect was a subscription"
date: 2026-09-28
tags: [retro, tech-debt, viewmodel, coroutines, deprecation]
status: accepted
epic: refactor/tech-debt-roadmap-v3
---

Retro after MR-2 of the tech-debt roadmap (`mr-2-project-detail-cleanup`).

## Inventory

| Metric | Value |
|---|---|
| Production files changed | 1 (`ProjectDetailViewModel.kt`, +19/−21) |
| Test files changed | 1 (`ProjectDetailViewModelTest.kt`, +64) |
| `:shared:jvmTest` | 1135 tests, 0 failed, 0 skipped |
| detekt (shared) | 0 findings |

## The roadmap asked for a split that had already been rejected

MR-2 was scoped to split `ProjectDetailViewModel` (319 LOC, "23 intents") into a
coordinator plus five `FeatureSlot`s. Reading the code first:

- **The split was already considered and declined.** `2026-09-26-pr24-rescope` (PR 2.4b)
  weighed `ProjectTasksViewModel` + `ProjectMetadataViewModel` and rejected them: *"A
  split would duplicate the project subscription and force a merge layer for the single
  `ProjectDetailUiState`."* The "done instead" work — one Room observer, side effects out
  of `combine` — is in the file. Re-deciding it needed new evidence, not a redo.
- **The intent count was wrong.** 23 is the number of variants in
  `ProjectDetailIntent.kt`: **13 `Domain`** reach `onIntent` and **10 `Routing`** are
  screen-handled sheet openers and navigation. Two ADRs cited "23 intents" for this VM.
- **None of TaskDetail's motivating bugs were present.** The state combine was already
  pure, and both side flows (`parentOptionsFlow`, `availableTasksFlow`) already entered
  the second `combine`, so there was no "flow outside the combine, state never recomputes"
  defect — the one that made the TaskDetail split worth its indirection.

## The real defect: a subscription restarted on every field write

Two of the three `flatMapLatest` triggers in the state combine guarded only on
`project == null`, but were attached to the whole `Project` object. `flatMapLatest` cancels
and re-collects its upstream on every trigger emission, so any write to the project —
rename, colour pick, archive toggle — tore down and recreated the
`observeByFilter(ByProject)` and `observeChildrenOf` subscriptions behind them.

Measured, not reasoned: a counting `TaskRepository` decorator recorded the project
task-stream subscription count going **2 → 3** across a single `UpdateColor`. Both streams
now derive from `_projectFlow.map { it != null }.distinctUntilChanged()`; the parent stream
stays on the object because it genuinely reads `parentId`. The test asserts the count is
stable and was confirmed to fail against the pre-fix implementation.

The `single observer, not five` KDoc above the collector was true about
`projectRepo.observe(projectId)` and misleading about everything downstream of it.

## CRITICAL — dead state with two comments that lied about it

`_latestProject` was written on every project emission (`:103`), declared (`:223`), and
**never read**. Two comments asserted the opposite:

> `// Name debounce — mutate reads _latestProject inside the launched block to avoid TOCTOU.`
> `* Reads from [_latestProject] inside the launched block to avoid TOCTOU.`

`mutate` calls `updateProject(projectId, transform)`, and `UpdateProjectUseCase`'s
read-modify-write overload does `repo.get(id)` itself — that is what prevents the
stale-snapshot write, and it is why both call sites were already on the safe form. The
TOCTOU two ADRs recorded as open was a guard the use case had already replaced.

**Rule:** a cache that nothing reads is not "defence in depth", it is a comment that
eventually becomes false. Delete it, or make the read exist and test it.

## MEDIUM — 81 deprecation warnings, none tracked

`:shared:compileKotlinJvm` emits **81** `w:` deprecation warnings in production code, in
30 files, and no ADR in the corpus mentions them. Concentrations:

| Warnings | File | Deprecated API |
|---|---|---|
| 12 | `feature/nav/AppDestination.kt` | Nav2-era `Inbox`/`Today`/`Upcoming`/`TaskDetail`/`TaskDetailCreate` |
| 8 | `NoteEditorScreen.kt` | (compose/runtime) |
| 7 | `CalendarEventMapper.kt` | `kotlinx.datetime` — `Instant` typealias, `LocalDateTime(y,m,d,...)`, `monthNumber` |
| 5 | `NoteEditor.kt` | — |
| 4 | `SettingsScreen.kt`, `JvmNavEntries.kt` | — |
| 3 × 6 | `slot/Task*Slot.kt` | — |

Not fixed here: 81 sites is its own MR, and the `CalendarEventMapper` cluster is entangled
with the deferred `Instant` migration (R26), which should not be done twice. What matters
is that the count is now measured, so a regression is visible and the work can be scoped.

The deprecated Nav2 `AppDestination` variants are the notable subset — `2026-09-16-nav3-migration`
replaced the graph, and these are the leftovers kept for compatibility. Whether they are
still reachable is a question worth answering before removing them.

## LOW

- `UpdateProjectUseCase` returns `Result<Unit>` from both overloads; `UpdateTaskUseCase`
  returns `Result<Task>`. Callers of the project one discard the updated entity, so the
  asymmetry is currently harmless, but the contract differs for no stated reason. Its
  full-entity `invoke(project)` overload has no callers and is not deprecated, unlike its
  Task counterpart.
- **R7 in `2026-09-28-mr2-retro-findings` is closed.** That finding said the deprecated
  `UpdateTaskUseCase.invoke(task)` was "still used". A clean production compile now emits
  no warning for it, and every call site is on `invoke(id) { … }`.
- `ProjectDetailViewModelTest` covers 5 of 13 domain intents. `UpdateName`,
  `UpdateDescription` (both debounced), `UpdateIcon`, `UpdateParent`, `UpdateDueDate`,
  `MoveTaskToProject`, `ToggleTaskPin` and `DeleteTask` are untested. The two debounced
  ones matter most: they are the paths the deleted TOCTOU cache used to serve.
- ktlint in this project requires a **single-line** parameter list when it fits; the
  multi-line-with-trailing-comma form used elsewhere in the repo is only correct once the
  line is too long. Cost three detekt round-trips to learn.

## Rules

- **Read the file before implementing a roadmap item derived from an ADR.** Both the
  rejected split and the "23 intents" were settled facts in the tree, four days old.
- **When an ADR counts something, recount it.** "23 intents" counted `Routing` variants
  the VM never sees.
- **A KDoc that describes a mechanism is a testable claim.** Two of them described a cache
  read that did not exist, and no test could have caught it because the cache was dead.

## Links

- `2026-09-26-pr24-rescope` — the rejected split, and the work that replaced it
- `2026-09-27-feature-slot-pattern` — the TaskDetail split this MR was modelled on
- `2026-09-28-mr2-retro-findings` — the earlier retro; R7 closed here
- `2026-09-27-mvi-single-state-entry-and-vm-sweep` — `updateState { it }`, the same class
  of "rewrite that is not equivalent" defect
