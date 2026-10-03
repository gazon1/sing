# Progress Journal

## Epic: docs-and-skills-hygiene

**Start date:** 2026-09-26
**Status:** in progress
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
### Phase 7 — AI proposal data layer (MR-7) ✅ DONE
**Commit:** `ab6404bc` (27 files, 6780 insertions)

**What was done:**
- `ai_proposal` + `ai_proposal_item` Room tables with `user_id` ownership and `@Embedded sync: SyncColumns`
- `ProposalRepository`: CAS confirm/reject via `claimAndRead@Transaction`, fingerprint deduplication via `rejectedFingerprints` query
- `ApplyProposalItemUseCase`: `plan() → claim() → dispatch(plan, userId) → refreshStatus()` — all validation before any claim
- `ProposalFingerprint`: FNV-1a 64-bit hash, 16 hex chars, stable across JVM and Android
- `ProposalItemKind`: single sealed dispatch point — `when` on `ProposalItemKind` is the only one in the entire codebase
- DAO split: `ProposalDao` (proposal aggregate, 8 methods) + `ProposalItemDao` (item aggregate, 8 methods) — satisfies `TooManyFunctions` threshold
- Ownership via SQL subquery: `WHERE proposal_id IN (SELECT id FROM ai_proposal WHERE user_id = :userId)`
- `claimAndRead@Transaction`: re-read after CAS to confirm what was actually claimed (no stale read between decision and dispatch)
- `FakeProposalRepository` + `FakeAppDatabase` faithfully reproducing ownership subquery logic
- `ApplyProposalItemUseCaseTest`: 17 cases — double-tap, parse-before-claim, dispatch (tags/tasks/subtasks/time entries/checklist), batch partial success, rejection fingerprints

**What went well:**
- `fingerprintTarget` as extension property cleanly sidesteps interface property dispatch limitation in Kotlin
- CAS claim in `@Transaction` DAO method means double-tap protection is database-enforced, not application logic
- Splitting DAO early kept detekt `TooManyFunctions` violation from becoming a last-minute blocker

**What didn't go well:**
- Agent's first MR-7 attempt produced incomplete code that had to be fully deleted and rewritten
- `Section.id` non-nullable caused backward-compat risk for saved agendas — needed emergency fix between MR-6 and MR-7
- Two-pass Room schema generation (v28 → generate → v29 → generate) was non-obvious

**Critical fixes (MR-0穿插):**
- `AgendaDefinition.Section.id` → `String? = null` with `effectiveId` computed property — fixes `MissingFieldException` for legacy saved agendas
- `AndroidPomodoroTimer` + `JvmPomodoroTimer`: `source = TimeEntrySource.Pomodoro` (was defaulting to `Manual`)
- `CalendarSyncWorker`: stale imports (`domain.repository` → `domain.port`, `reminders.ReminderRepository` → `reminders.domain.port.ReminderRepository`)
- Duplicate `TimeTrackingRepository` in `data/` deleted — domain version is correct; Koin bound the right one but 3 consumers used the wrong one

**Findings for ADR:**
- `fingerprintTarget` must be an extension property, not an interface property — Kotlin's interface dispatch doesn't support `when(this)`
- `encodeToByteArray()` explicitly named (not `encodeToByte()`) — Kotlin String has both overloads
- Room `@Transaction` on a `suspend fun` that calls another `@Transaction` internally is valid — Kotlin coroutines preserve transaction semantics across suspend boundaries
### Phase 8 — Checklist sovereignty + tag suppression (MR-8) ✅ DONE

**What was done:**
- `ChecklistItemEntity`: added `checked_by TEXT?`, `checked_at INTEGER?`, `row_version INTEGER DEFAULT 1`; all toggle via `ChecklistDao.toggleItem` (targeted UPDATE preserving new columns)
- `ChecklistRepositoryImpl`: `toggleItem` now writes `actor`/`checkedAt` and increments `row_version`; `addItem` seeds `row_version = 1`; `createBatch` preserves existing `checkedBy`/`checkedAt`/`rowVersion`
- `ChecklistRepository.toggleItem(taskId, itemId, actor: String = "user")` — default `"user"` for backward compat with existing UI call sites
- `ChecklistItem` domain model gains `checkedBy: String?` and `checkedAt: Long?`
- `TagEditActor` enum (`User | AiProposal`) — distinguishes UI tag edits from AI proposal tag dispatches
- `Task.aiSuppressedTagIds: Set<TagId>` added to domain model
- `TaskEntity` gains `ai_suppressed_tag_ids TEXT DEFAULT '[]'` (JSON array, auto-migration safe)
- `TaskRepository.setTags(taskId, tagIds, actor: TagEditActor = User)` — full suppression logic:
  - User removes tag → records in `aiSuppressedTagIds`
  - User adds tag → clears from `aiSuppressedTagIds`
  - AiProposal → suppressions unchanged
- `ApplyProposalItemUseCase.dispatch`: `AddTags`/`RemoveTags` now pass `TagEditActor.AiProposal`
- `Migration29To30` (AutoMigrationSpec), schema v29 → v30

**What went well:**
- Default parameter `actor = "user"` on `ChecklistRepository.toggleItem` avoided updating 4 call sites in the UI layer
- `Checked_by`/`checked_at`/`row_version` targeted UPDATE preserves columns added in future — no read-reconstruct-write
- JSON array for `aiSuppressedTagIds` (following `outgoing_links` precedent) avoids schema migration complexity

**What didn't go well:**
- Detekt ran AFTER the first Kotlin compile, so `ColumnInfo(defaultValue = "1")` was not set initially — KSP rejected the migration for a NOT NULL column with no default
- `MutableStateFlow<Map<...>>` type in `FakeChecklistDao` caused type inference failures in `setTags` block — had to separate `existingTagStrings: Set<String>` from `existingTagIds: Set<TagId>` explicitly

**Critical fixes:**
- `ChecklistItem.checkedBy` nullable (`String?`) — pre-existing items have no actor, no crash on read
- `StableJson.SetSerializer(String.serializer())` — requires explicit `serializer()` import, not `decodeFromString<Set<String>>`
- `FakeAppDatabase.FakeChecklistDao.toggleItem` — implemented the new `toggleItem` signature (was only `updateCompletionStatus`)

**Findings for ADR:**
- Room auto-migration requires `@ColumnInfo(defaultValue = "N")` on any added NOT NULL column — KSP validates this at compile time, not at migration time
### Phase 9 — ProposalCard + AI redirect (MR-9) ✅ DONE

**What was done:**
- `TaskAiSlot` now creates `AiProposal` items instead of writing directly to tasks
  - `RefineTitle` → `ProposalItemKind.SetTaskField(Title, value)`
  - `GenerateDescription` → `ProposalItemKind.SetTaskField(Description, value)`
  - `GenerateChecklist` → `ProposalItemKind.AddChecklistItems(steps)` (single item, exploded on confirm)
  - `Decompose` → `ProposalItemKind.AddSubtasks(titles)` (single item)
  - `SuggestTime` → `ProposalItemKind.AddTimeEntries` (note-only, no actual time range)
- `TaskDetailDeps` gains `proposals: ProposalRepository?` and `applyProposal: ApplyProposalItemUseCase?`
- 4 new intents: `ConfirmProposalItem`, `RejectProposalItem(itemId, reason?)`, `ConfirmAllProposalItems(proposalId)`, `DismissProposal(proposalId)`
- `TaskDetailCoordinator` routes all 4 intents — `apply.confirm`/`reject`/`confirmAll` and `proposals.retract`
- `TasksDiModule` wires `proposals = get()` and `applyProposal = get()` into `TaskDetailDeps`
- `TaskAiSlot` generates `ProposalFingerprint` for each item (stable identity for rejection suppression)
- `TaskProposalsCollector` watches `ProposalRepository.watchProposalsForTask`, filtering to proposals with pending items
- `TaskDetailExtras.Ready` gains `proposals: List<AiProposal>`
- `combineStates` gains a 9-input overload; coordinator's extras combine updated to include proposals
- `ProposalSection` + `ProposalCard` + `ProposalItemRow` composables in `TaskDetailViewScreen`:
  per-item confirm/reject (with optional reason field, shown inline on ✗ tap)
  and proposal-level "Accept all" / "Dismiss"
- `AiActionButton` wired on three list screens:
  - `SearchScreen` → navigates to task detail on AI tap
  - `ArchiveScreen` → navigates to task detail on AI tap
  - `ProjectDetailContent` → navigates to task detail on AI tap

**What went well:**
- Redirecting TaskAiSlot to proposals was clean: `execute()` now builds a proposal and saves it instead of applying writes
- `ConfirmAllProposalItems` reports failure count (not just success) — partial batch success is normal, not an error
- Extracting `ProposalItemRow` sub-composable kept `ProposalCard` within the 80-line limit

**What didn't go well:**
- `scope.launch` in `onIntent` required using `vmScope`, not a local `scope` variable — had to check how other slots reference the scope
- `ConfirmAllProposalItems` returns `BatchResult(applied, failed)` — had to add `.failed.size` reporting since it's a normal result, not an exception
- `extrasState` needed both adding proposals to its combine AND including `proposalsCollector?.state` in the top-level 9-input `combineStates` — two separate places

**Critical fixes:**
- `ApplyProposalItemUseCase` is already injected into the tasks feature via `TasksDiModule` — no circular dependency issues
- Proposals are wired as nullable in `TaskDetailDeps` (`= null`) matching the existing AI use case pattern for test omission
- `TaskAiSlot` was missing `ProposalItemStatus` import (caused compile failure)
- `combineStates` 9-input overload required adding the `pack` extension and overload alongside existing 6/7/8-input helpers

**Findings for ADR:**
- `TaskAiSlot` creates proposals with `ProposalSource.Detail` — Card-level proposals from list screens would use `ProposalSource.Card` (separate wiring, deferred)
- The 9-input `combineStates` was a minimal addition — proposals slot naturally belong in `extrasState` alongside `timeSlotState` and `firstRun`

### Merge into main — three regressions fixed (2026-10-03) ✅ DONE

**What was done:**
Merged `refactor/time-hub-ai-proposals` (MR-0..MR-9) into `main` and fixed the three
regressions that made every desktop flow test fail or hang on the feature branch
(clean `origin/main` passed the same suite in the same environment).

1. **Boot rendered Settings instead of the agenda** — `Nav3State.getTopLevelRoutesInUse()`
   was changed to "all non-empty stacks", but every back stack is seeded with its key,
   so NavDisplay always rendered the LAST entry. Restored the targeted
   `[startRoute]` / `[startRoute, topLevelRoute]` shape.
2. **Task detail screen hung forever on its loading spinner** — `TaskDetailCoordinator`
   declared `extrasState` AFTER the `init` block that launches a combine reading it.
   Kotlin initialises properties in declaration order; an `init` block sees a
   not-yet-assigned property as null (no intrinsic check inside the same class), so the
   combine coroutine died with `NullPointerException: parameter f8 is null` and the VM
   stayed `Loading` forever. Moved the declaration before `init`.
3. **Desktop test harness could not build TaskDetailCoordinator** — `testPlatformModule()`
   never bound the new DAOs (`TimeEntryDao`, `ProposalDao`, `ProposalItemDao`), so Koin
   threw `NoDefinitionFoundException` inside composition, which Compose retried every
   frame (endless redraw). Bound all four missing DAOs.
4. **Android build broken on main itself (pre-existing)** — `WrappingDriver.android`
   extended `BundledSQLiteDriver`, which current androidx.sqlite makes final (and the
   file also missed the `SQLiteDriver` import). Rewrote it with the same composition
   shape as the JVM actual. The 112 phantom errors across the notes editor were pure
   cascade: once the root failed, the android-variant compiler surfaced unrelated
   unresolved references.

**What went well:**
- The diagnostics infrastructure paid for itself: `FailureBundle` screenshot +
  thread dumps + a plain-Koin VM probe (no Compose) reproduced the spinner headlessly,
  and `DebugProbes.dumpCoroutines()` surfaced the NPE that the silent coroutine death
  had been hiding.
- Clean `origin/main` desktop run as a control run — it ruled out environment causes
  immediately.

**What didn't go well:**
- The silent NPE masked itself three ways: no events, no error state, just a spinner.
  Coroutines that die before their first emission leave no trace without DebugProbes.
- `-Dsingularity.*` forwarding is snapshotted by the configuration cache, so CLI flags
  were silently ignored unless `-Dorg.gradle.configuration-cache=false` was also passed.

**Critical fixes:**
- `FailureBundle.capture` now honours `-Dsingularity.test.screenshot=false`:
  `captureToImage` blocks on `EventQueue.invokeAndWait`, which a stuck redraw loop never
  releases — a coroutine timeout cannot cancel a blocking EDT wait. Without the skip
  flag, the diagnostic path itself hung and masked the real failure.

**Findings for ADR:**
- Kotlin property-initialisation order vs `init` blocks is the "unwired surface" of
  constructors: it compiles, it passes VM-level tests that never touch the property,
  and only a screen that renders the loading shell catches it. Recorded in
  `docs/decisions/2026-10-03-merge-regression-fixes.md`; the follow-up sweep's
  deferred items live in `docs/decisions/2026-10-03-post-merge-debt.md` (scopedUserId
  snapshot at construction, DIGEST line budget, frozen-frame screenshot caveat).

---

## Epic: openspec-adoption

**Start date:** 2026-10-03
**Status:** in progress
**Worktree:** `~/work/singularity-openspec` (`refactor/openspec-adoption`)

### Phase 0 — Decision record ✅ DONE
`docs/decisions/2026-10-03-openspec-adoption.md` — ownership matrix, `skip_specs` table,
decision tree, ADR/spec relationship rules, consequences.

### Phase 1 — Gate sweep (PR-2) 🔄 IN PROGRESS
CI gates + unwired-surface detectors + quick fixes. All done in isolated worktree;
main checkout stays clean.

- Phase 1.1: `check-doc-sizes.py` + `check-doc-dead-refs.py` wired in CI as blocking ✅
- Phase 1.2: detector 7 `dead-symbol` + baseline ✅
- Phase 1.3: detector 8 `skill-dangling-symbol` + baseline ✅
- Phase 1.4: `PlatformModule.android.kt` / `.jvm.kt` parity (18 shared ports, 2 intentional
  platform-only: `CoroutineScope` and `PomodoroScheduler`) ✅
- Phase 1.5: two detekt bugs fixed (`NoDirectDispatchersRule` dead branch,
  `NoDirectClockSystemRule` KDoc) ✅
- Phase 1.6: PROGRESS + deferred-backlog + DIGEST budget reconciliation 🔄 in progress
- Phase 1.7: skill clusters (~13 skills with broken symbol refs → backlog)

**Retro step (Phase 1):** blocker count = 0; non-blocking items → `deferred-backlog.md`.

### Phase 2 — OpenSpec CLI surface (PR-3)
Install `openspec`, run `init`, verify CLI structure. Stop if assumptions don't match.
Baseline PR-3 only if CLI validates.

### Phase 3 — `openspec/config.yaml`
Write config from actual CLI surface (not assumptions). KMP context, artifact rules,
operation guidance.

### Phase 4 — `singularity-todo-openspec-workflow` skill (PR-3)
Decision tree, `skip_specs`, mandatory read order, `verify` gate. Wire into `AGENTS.md`,
`wayfinder`, `docs/doc-maintenance.md`.

### Phase 5 — Baseline change: `baseline-write-pipeline` (PR-4)
Write pipeline spec from existing code + tests. No behavior change — proof OpenSpec
can describe current state. No design document (baseline specs omit it).

### Phase 6 — Real feature: log export (PR-5 + PR-6)
`FileSharePort` + `LogBundleExporter`. Known gaps documented in the Phase 6 design
document (RedactingLogWriter redacts only message body, not `cause` chain or `tag`;
`initLogging` runs before Koin; `beginShutdown` never called on Android).

### Phase 7 — CI integration (advisory, PR-7)
`openspec validate || true`, stale-check script, skill integration. No blocking gate.
