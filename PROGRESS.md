# Progress Journal

## Epic: docs-and-skills-hygiene

**Start date:** 2026-09-26
**Status:** completed
**Epic branch:** `docs-and-skills-hygiene` (git worktree at `~/worktrees/singularity-docs-hygiene/`)

---

### Phase 0 — Hygiene (PR-0.0 to PR-0.4) ✅ COMPLETED

| PR | Description | Status | Merged |
|---|---|---|---|
| PR-0.0 | detekt-fix-wiring: register 5 rule sets | ✅ merged | 2026-09-26 |
| PR-0.1 | detekt-baseline + warningsAsErrors | ✅ merged | 2026-09-26 |
| PR-0.2 | KDoc enforcement rules | ✅ merged | 2026-09-26 |
| PR-0.3 | KDoc fix batch + ADR supersede | ✅ merged | 2026-09-26 |
| PR-0.4 | production BAN fixes | ✅ merged | 2026-09-26 |

---

### Phase 1 — Meta-skills + Docs Infrastructure

#### PR-1.1 — docs-infrastructure ADRs ✅ MERGED
5 ADRs: docs-lifecycle, adr-supersede-process, domain-glossary-policy, progress-journal-policy, skill-authoring-policy

| PR | Description | Status | Merged |
|---|---|---|---|
| PR-1.1 | docs-infrastructure ADRs | ✅ merged | 2026-09-26 |

#### PR-1.2 — docs-infrastructure FILES 🔄 IN PROGRESS
CONTEXT.md, PROGRESS.md, domain-glossary skill, progress-journal skill

| PR | Description | Status | Merged |
|---|---|---|---|
| PR-1.2 | CONTEXT.md + PROGRESS.md + 2 skills | 🔄 in progress | — |

#### PR-1.3 — process ADRs
writer-reviewer-pattern, code-review-process, four-phases-gate, code-review-pr-workflow skill

| PR | Description | Status | Merged |
|---|---|---|---|
| PR-1.3 | process ADRs | ⏳ not started | — |

#### PR-1.4 — skills reorg
vm-migration-playbook merge, feature-scaffold TOC, AGENTS.md update

| PR | Description | Status | Merged |
|---|---|---|---|
| PR-1.4 | skills reorg | ⏳ not started | — |

---

### Phase 2 — Meta-skills + Workflow Evals + ADR Backlog

| PR | Description | Status | Merged |
|---|---|---|---|
| PR-2.1 | debugging-investigation, observability-production, security-review | ⏳ not started | — |
| PR-2.2 | performance-profiling, ux-a11y-review, internationalization | ⏳ not started | — |
| PR-2.3 | Workflow Evals + ADR backlog R21-R30 | ⏳ not started | — |

---

### Retro findings

#### PR-0.3 retro (2026-09-26)
- DIGEST oversized (1651/1500) — deferred to PR-1.2
- AppVersionGateViewModel KDoc false positive — baseline suppress is correct workaround
- `stateIn` in production only found in AndroidPomodoroTaskListProvider

#### PR-0.4 retro (2026-09-26)
- Production BAN: `stateIn` in AndroidPomodoroTaskListProvider, `runBlocking` in PlatformModule.jvm.kt
- Detekt auto-correct reformatted 10 unrelated files

#### Post-P0 retro (2026-09-26)
- Consolidated retro for all 4 PRs
- 4 UI-testing ADRs superseded via `2026-09-26-ui-testing-deferred`
- 23 TODO comments cleaned up in production code

#### PR-1.1 retro (2026-09-26)
- ADR files created in wrong directory (main checkout instead of worktree)
- Worktree had pre-existing build errors: missing UserMessage.kt + TagGroupsUiState.Empty("")
- Android SDK unavailable in worktree — quality gates run as jvmTest + detekt only
- DIGEST remains oversized (1681/1500)

### Blockers / Deferred to ADR

- DIGEST oversized (1681 lines, limit 1500) — deferred to PR-1.2 (CONTEXT.md + PROGRESS.md work)
- androidApp and mcp-server baselines — deferred to follow-up cleanup PR
- Skills YAML migration (5 skills) — deferred to PR-1.4
- ARCHITECTURE.md §1 source tree sync — deferred to final retro

---

## Epic: doc-and-skills-hygiene-v2

**Start date:** 2026-09-27
**Status:** in progress
**Epic branch:** `sprint/doc-skills-v2` (git worktree at `~/worktrees/sprint-doc-skills-v2/`)

### Phase A — stale-ref cascades ✅ COMMITTED (`55acab73`)

13 cascades batched by root cause (Clock removal, MVI flatten, `ConflictResolver.merge()`,
`SharedEventBus`, `isDesktop`, `ChecklistUseCase`, `NoteEditor`/`NotePreview` renames,
`BackupViewModel` path, orgmode ADR path, DI narrative, dead-file refs).

- AGENTS.md 387 → 210 lines
- 3 ADRs with body text swallowed by frontmatter — rebuilt (they broke digest parsing)
- 2 ADRs missing H1 — added
- 6 ADRs got supersedure banners; new DI-narrative ADR written

#### Phase A retro (2026-09-27)
- The pre-sprint audit was produced against a different branch (`fix/repair-broken-build`)
  than the worktree base (`main`); 2 of 13 cascades had to be re-verified against real code
  and one turned out to be a false positive (`TaskDetailViewModel.kt` still exists)
- `StatefulViewModel.kt` and `StateStrategy.kt` were never written despite the ADR
  describing them — the ADR now says so explicitly
- `ModelPricing` / `UsageExtractor` are documented in a skill but never implemented; AI
  Usage cost column is therefore always empty
- Chasing "why is this ref documented?" repeatedly turned up *design* that was never built
- Triage backlog recorded in `docs/decisions/2026-09-27-doc-and-skills-sprint-findings.md`

---

## Epic: time-hub-ai-proposals

**Start date:** 2026-10-02
**Status:** in progress
**Worktree:** `~/work/singularity-todo-time-hub` (`refactor/time-hub-ai-proposals`)

### Phase 0 — Pre-work (MR-0-A/B/C/D) ✅ COMPLETED
See commit history: `67fed68f` (MR-0-A), `dbbf1d15` (MR-0-B), `70678dd2` (MR-0-C), `21d8cc80` (MR-0-D)

### Phase 1 — Time Tracking data layer (MR-1) ✅ COMPLETED
See `eb85ce54` and `9a4bfd70`

### Phase 2 — Timer, Pomodoro, manual entry (MR-2) ✅ COMPLETED
See `eb85ce54`

### Phase 3 — Task Detail as hub (MR-3) 🔄 IN PROGRESS
`f1e292f7` (rebase fix), `419371dd` (detekt cleanup)

**What was done:**
- `TaskDetailExtras` partition: `timeSlotState` + `firstRun` combined in one flow, keeping coordinator at 8 inputs
- `FirstRunResolver`: pure clock-injected logic, 8 test cases covering all resolution paths
- `SubtasksSection`: renders direct child tasks with ExtraSectionCard, self-hides when empty
- `FirstRunSection`: three action chips ("Write note", "Add checklist", "Ask AI"), sealed state machine
- `TaskDetailCoordinator`: removed `stateIn(WhileSubscribed)` per BAN-list → canonical `MutableStateFlow + collect`
- All wired in `TaskDetailViewScreen.kt` in correct block order

**What went well:**
- Partition pattern works: one new flow instead of growing combine arity
- FirstRunResolver is pure and trivially testable
- `stateIn` removal was correct per BAN-list

**What didn't go well:**
- Rebase introduced 120+ detekt violations from reformatting (FakeRepositories, etc.)
- Import paths shifted after rebase — `TimeEntry` package move needed cascading import fixes
- Desktop boot test was already failing on main (app boots to Settings tab) — pre-existing issue

**Critical fixes:**
- `JvmPomodoroTimer` needed 6 params (added `timeTrackingRepo` + `currentUser`)
- `FakeRepositories.kt` restored from main to fix indentation cascade
- `TimeEntry.kt` moved to `feature/timetracking/domain/` (package = domain)

**Findings for ADR:**
- Detekt `FunctionSignature` rule rejects multi-line parameter lists — code must use `param: Type,` style even for 4+ parameters
- `LongMethod` suppression works but needs `@Suppress` annotation, not baseline

### Phase 4 — Logbook merge (MR-4) ✅ DONE
`b3ae74b9` (LogbookEntry sealed interface + time entry rendering)
Time entries + notes in one chronological stream

**What was done:**
- `LogbookEntry` sealed interface (`NoteEntry | TimeEntryRow`) in `TaskLogbookState.kt`
- `TaskLogbookCollector` now takes `timeTrackingRepo`, combines notes + time entries via `combine()`, sorted by timestamp descending
- `LogbookSection` rewritten: receives `List<LogbookEntry>`, renders both `LogbookNoteRow` and `LogbookTimeEntryRow` in day groups
- `formatElapsed()` inlined locally (was `internal` in `TimeTrackingSection.kt`, can't cross module boundary)
- `TaskDetailViewScreen` updated: `logbookNotes` → `logbookEntries` parameter

**What went well:**
- Sealed interface cleanly models the two entry types with pattern matching in `when`
- Day grouping logic reused cleanly, extracted timestamp via `when` on sealed type

**What didn't go well:**
- `formatElapsed` was `internal` in a different module — had to duplicate the helper

**Critical fixes:**
- `LogbookSection` signature: `entries: List<LogbookEntry>` — was passing `notes = ui.logbookNotes`
- `TaskDetailCoordinator` already assigned `logbookEntries = logState.allEntries` from prior MR-3

**Findings for ADR:**
- `internal` functions in Compose UI modules can't be imported across feature boundaries — consider extracting shared UI helpers into `core/ui/` to avoid duplication

### Phase 5 — Insights tab (MR-5) ✅ DONE
`da77c365` (Insights tab with time bucketing)
"Time" tab in Statistics with stacked bars by day and project breakdown

**What was done:**
- `InsightsViewModel`: combines `timeTrackingRepo.watchEntriesInRange`, `taskRepository.observeByFilter`, `projectsRepo.observeProjectsWithCounts`; uses `mergeIntervalsWithOpen` + `splitAtMidnight` for accurate day bucketing
- Added `watchEntriesInRange` to `TimeTrackingRepository` interface (existed in DAO and impl but not in domain interface)
- `DayInsightsBucket` and `bucketByDay` added to `TimeBucketing.kt`; `bucketByProject` added for project-level aggregation
- `InsightsUiState`, `ProjectInsightsBucket` data classes; `InsightsViewModel` wired in `TasksDiModule`
- `StatisticsScreen` refactored: `TasksTabContent` extracted from main screen, `PrimaryTabRow` with Tasks/Time tabs, `InsightsStackedBarChart` and `ProjectTimeRow` composables
- `TimeTrackingRepositoryImpl` had wrong method names (`watchForTask`/`watchForUserInRange` vs interface's `watchEntries`/`watchEntriesInRange`) — fixed
- `FakeTimeTrackingRepository` updated to implement the new interface method
- Duplicate `TimeTrackingRepository.kt` in `data/` package caused interface mismatch — removed `data/` copy, impl now imports `domain.TimeTrackingRepository`

**What went well:**
- Adding the interface method first and then wiring through was clean
- Tab architecture follows existing patterns (`PrimaryTabRow`, two content functions)

**What didn't go well:**
- ktlint auto-correct badly mangled `FakeRepositories.kt` indentation cascade (927-line diff) — had to restore from HEAD
- The `data/TimeTrackingRepository.kt` duplicate file was a pre-existing issue

**Critical fixes:**
- `watchEntriesInRange` in impl was named `watchForUserInRange` — added interface method and renamed impl
- `ProjectWithCountRow.project.id` is a `ProjectId` value class — needed `.value` for `String` map key

**Findings for ADR:**
- Duplicate interface files (`data/` vs `domain/`) cause subtle type resolution bugs — worth an architecture rule
### Phase 6 — Agenda section headers '+' (MR-6) ✅ DONE
**Commits:** 1 (this merge commit)

**What was done:**
- `Section.id` (nullable, with `effectiveId` derivation) + `SectionPrefill` + `SectionEditorCard` prefill editor
- `AgendaIntent.CreateInSection` + `AgendaUiEvent.CreateInSection`
- `AgendaViewModel.handleCreateInSection`: saves `TaskDraft` under `section_create_draft_$sectionId` key
- `AgendaNavigator.openCreateInSection(sectionId)`: navigates to `TasksGraph(TasksStartRoute.Create(sectionPrefillKey=sectionId))`
- `TasksRoute.Create` gains `sectionPrefillKey: String?` parameter
- `TasksNavGraph.android.kt` + `tasksEntryProvider()` pass `sectionPrefillKey` through to `TaskCreateScreen`
- `TaskCreateScreen` + `TaskCreateViewModel` accept and restore from section draft key
- `AgendaContent`: '+' button on section headers with `Icons.Default.Add`

**What went well:**
- Navigation wiring was systematic — followed the established `AppDestination → TasksNavGraph → TasksRoute` pattern cleanly
- `Section.id` nullable + `effectiveId` computed property cleanly solves the backwards-compat problem

**What didn't go well:**
- Agent produced broken MR-7 changes that polluted the branch and had to be manually cleaned up
- `Section.id` non-nullable was added before verifying backwards-compat impact — caused a runtime crash risk for saved agenda views

**Critical fixes:**
- `Section.id` changed to `String? = null` with `effectiveId: String get() = id ?: name.lowercase()` — fixes saved-agenda deserialization
- Agent's `proposals/` package deleted (incomplete), `AppDatabase` + `PlatformModule.jvm.kt` reverted
- `FakeTimeTrackingRepository.createManualEntry` updated to match `TimeTrackingRepository` interface (added `source` parameter)
- `AgendaEvaluator` + `AgendaViewModel` updated to use `effectiveId` instead of `id`

**Findings for ADR:**
- `2026-10-02-mr6-mr7-breakage-post-mortem.md` — documents the full post-mortem including agent rules and MR-0-B prerequisite for MR-7
### Phase 7 — AI proposal data layer (MR-7) ⏳ NOT STARTED
### Phase 8 — Checklist sovereignty + tag suppression (MR-8) ⏳ NOT STARTED
### Phase 9 — ProposalCard + AI redirect (MR-9) ⏳ NOT STARTED
