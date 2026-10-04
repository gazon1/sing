<!-- Archived from PROGRESS.md on 2026-10-05. This epic is complete:
     every phase is marked merged and its deliverables are on main.
     Kept for history, not read as current state. -->

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
