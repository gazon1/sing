---
description: "Task list for feature implementation"
---

# Tasks: Task States and Inline Task Items

**Input**: Design documents from `/specs/016-task-states-and-items/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Included. The spec's ten success criteria are measurable outcomes, the
quickstart defines ten validation scenarios, and constitution IX holds that a task closes
only when its acceptance scenario runs on the production path.

**Organization**: Tasks are grouped by user story to enable independent implementation
and testing of each story.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Path Conventions

All paths are relative to the repository root. Source lives in
`shared/src/commonMain/kotlin/com/singularity/todo/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: The decision records and the working branch.

- [X] T001 Create the feature branch and worktree per the repository's worktree convention: `git worktree add /home/max/work/singularity-todo-task-states -b feature/task-states-and-inline-items`
- [X] T002 Write ADR "A task carries a stored workflow state" in `docs/decisions/2026-10-26-a-task-carries-a-stored-workflow-state.md` — the three values (`Open`, `NextUp`, `InProgress`), the independence from completion, and the deliberate absence of a stored "waiting" (research.md D4, D5)
- [X] T003 Write ADR "One marking vocabulary, not one badge" in `docs/decisions/2026-10-26-one-marking-vocabulary.md` — the replacement of `AgendaBadge` and the row booleans with a single set (research.md D6, D7)
- [X] T004 Run `singularity-adr validate` and `./scripts/refresh-decisions-digest.sh` after T002 and T003
- [x] T005 [P] Verify the working tree is clean and the branch builds before any feature work: `./gw :shared:compileKotlinJvm`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The stored workflow state, end to end. Every user story that mentions a
state depends on it, and it is the only part of this feature that touches the database,
the backup format and the server.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### The domain and the column

- [X] T006 Create the `TaskWorkflowState` enum in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/TaskWorkflowState.kt` with exactly three values — `Open`, `NextUp`, `InProgress` — and `Open` as the default
- [X] T007 Add `workflowState: TaskWorkflowState = TaskWorkflowState.Open` to `Task` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/Task.kt`
- [X] T008 Add `@ColumnInfo("workflow_state") val workflowState: String = "Open"` to `TaskEntity` in `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Entities.kt` — stored as the enum entry **name**, per data-model.md
- [X] T009 Map the column in both directions in `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Mappers.kt` — an unknown name reads as `Open` rather than throwing, so a row written by a build that knew a fourth state cannot make the task list unreadable

### The migration — hand-written, not auto

- [X] T010 Write `Migration46To47` in `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Migration46To47.kt`: `ALTER TABLE tasks ADD COLUMN workflow_state TEXT NOT NULL DEFAULT 'Open'`. **Do not use `AutoMigration`** — the derived auto-migration rewrites the whole `tasks` table, and dropping it fires the foreign key that points `notes.task_id` at a task, nulling those notes. `Migration45To46` is manual for exactly this reason (`AppDatabaseFactory.kt:51-53`)
- [X] T011 Bump `SCHEMA_VERSION` to `47` in `shared/src/commonMain/kotlin/com/singularity/todo/core/database/AppDatabase.kt` and register `Migration46To47()` in `AppDatabaseFactory.addMigrations` beside `Migration45To46()`, with the same comment explaining why it is manual
- [X] T012 Regenerate the exported schema `shared/schemas/com.singularity.todo.core.database.AppDatabase/47.json` and the round-trip test `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/Migration46To47Test.kt` from it
- [X] T013 Add no index on `workflow_state` — the agenda matches in memory over the task list it has already loaded, exactly as `Selector.Blocked` matches against `blockedIds` (research.md D3)

### The backup — the shape changed, so the version changes

- [X] T014 Add `val workflowState: String = "Open"` to `TaskDto` in `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupDtos.kt` and map it in both directions
- [X] T015 Bump `BackupFormat.SCHEMA_VERSION` from `5` to `6` in `shared/src/commonMain/kotlin/com/singularity/todo/core/backup/BackupFormat.kt` — a record whose shape changed must change the version, so an older client refuses it outright rather than restoring tasks that all claim to be `Open`. `FORMAT_VERSION` stays `1`: the zip layout does not change

### The wire — the server must accept the field

- [X] T016 Add `workflowState` to the `task` entry's `wire` and `serverWritable` lists in `config/docs/sync-field-allowlist.json`, then regenerate with `./gw :shared:exportSyncFieldAllowlist` and `python3 infra/gen_sync_allowlist_sql.py`
- [X] T017 Write the dated migration `supabase/migrations/2026-10-26-sync-workflow-state.sql` inserting the `task.workflowState` row into `sync_field_allowlist`. **This is not optional** — the seed file builds a fresh database, the dated migration is what an existing one gets, and without it the server silently drops the field (open issue #1050 is exactly this debt for `task.seriesId`)

### The write path

- [X] T018 Add `WorkflowState` to `TaskSharedField` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/TaskSeriesPolicy.kt` and map `SetWorkflowState` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskSharedFieldScope.kt` — the state describes *the work*, so it is shared across a series, exactly as priority is
- [X] T019 Handle `SetWorkflowState` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskEntitySlot.kt` through the `mutate` helper, which writes through `updateTask` — or through `updateSeries`, for a task that belongs to a series. **No new repository method**: `TaskCoreDeps` states that the coordinator subscribes through `taskRepo` and *everything else writes through `updateTask`*, and a second write path for one field is what the `PassThroughUseCase` rule exists to prevent
- [X] T020 Add `data class SetWorkflowState(val state: TaskWorkflowState) : Domain, TaskEntityIntent` to `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/state/TaskDetailIntent.kt`, route it in `TaskDetailCoordinator.kt` and handle it in `presentation/viewmodel/slot/TaskEntitySlot.kt`

### Verification

- [X] T021 Add the migration round-trip assertion to `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/Migration46To47Test.kt` — a database at version 46 is opened by the new build and **a note that pointed at a task still points at it**
- [X] T022 Add the backup round-trip assertion to `shared/src/jvmTest/kotlin/com/singularity/todo/feature/backup/BackupRoundTripTest.kt` — a task's state survives a write and a restore
- [X] T023 Run `python3 scripts/check-room-schema-integrity.py`, `python3 scripts/check-sync-allowlist-regenerated.py` and `./gw :shared:compileKotlinJvm`

**Checkpoint**: The state is stored, migrated, backed up and synced. User story
implementation can now begin in parallel.

---

## Phase 3: User Story 1 - Read a task's state from the list (Priority: P1) 🎯 MVP

**Goal**: A task's markings — blocked, follow-up, in progress, next up, overdue, no date,
pinned, repeating, completed — are visible in a list, all at once, and one never hides
another.

**Independent Test**: Prepare a task that is blocked *and* overdue, and one that follows
up on another task, and show them in the list. The first carries both markings; the second
carries the follow-up marking and not the blocked one.

### Tests for User Story 1

- [x] T024 [P] [US1] Domain test for the marking policy in `shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/` — a blocked task that is also overdue carries **both** markings; a task that owns a `FOLLOWS_UP` edge carries the follow-up marking and **not** the blocked one
- [x] T025 [P] [US1] Replace `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/agenda/AgendaBadgePolicyFlowTest.kt` with the set-based equivalent — the old file asserts the first-non-null-wins behaviour this story removes, so it is rewritten, not deleted

### Implementation for User Story 1

- [x] T026 [P] [US1] Create the `TaskMarking` enum in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/TaskMarking.kt` with exactly nine values — `Blocked`, `FollowUp`, `NextUp`, `InProgress`, `Overdue`, `NoDate`, `Pinned`, `Recurring`, `Completed`
- [x] T027 [P] [US1] Create the pure policy in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskMarkingPolicy.kt` — `compute(task, context): ImmutableSet<TaskMarking>` over a `TaskMarkingContext(today, blockedIds, followUpIds)`. The derivation rules are tabulated in data-model.md §2; a `FOLLOWS_UP` edge never makes a task wait, so blocked and follow-up are mutually exclusive by construction
- [x] T028 [US1] Replace `isBlocked`, `isOverdue` and `isRecurring` on `TaskUi` with `markings: ImmutableSet<TaskMarking>` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/model/TaskUi.kt`. Keep `blockedBy` — it is the set of prerequisite ids, not a marking
- [x] T029 [P] [US1] Create the shared renderer `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/list/TaskMarkingsRow.kt` — a static label, following the `SubtaskChip` precedent in `TaskCard.kt:182-202` (a plain `Text` on a `secondaryContainer` background, not an `AssistChip`), in the display order `InProgress → NextUp → Blocked → FollowUp → Overdue → NoDate → Pinned → Recurring → Completed`, stating the number of markings it did not show rather than dropping one
- [x] T030 [US1] Render the markings from `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/list/TaskRowContent.kt`, replacing the three ad-hoc booleans in `TaskMetaRow.kt` with the set
- [x] T031 [US1] Compute the marking set where the row model is built — `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/AgendaContent.kt:404` — and add the set to the `remember` key at `:403` so a change of marking re-reads the row
- [x] T032 [US1] Retire `AgendaBadge` and `computeAgendaBadge` in the `AgendaBadgePolicy` file and `domain/model/AgendaUiState.kt`, replacing them with the shared policy, and fold the duplicated rules in `SelectorBadge` into it

### The follow-up relation — it has to be creatable and visible first

- [x] T033 [US1] Widen the read path so `FOLLOWS_UP` edges are visible — `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/TaskExtras.kt` filters to `BLOCKS` at lines 57, 89 and 134. The ordering projection is `getOrderingEdgeIdsForUser` (`Daos.kt:369`), and the blocking one is `getBlockingEdgeIdsForUser(taskId, userId, verb)` (`Daos.kt:386`) — the two are named apart by the ADR of 2026-10-23
- [x] T034 [US1] Make the write path verb-aware in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/data/TaskRepositoryImpl.kt` — every write currently hard-codes `DependencyVerb.BLOCKS.name` (lines 801, 813); `TaskRepository.setDependency(from, to, verb, enabled)` already exists and has no UI caller
- [x] T035 [US1] Add a verb control to `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/DependencyPickerSheet.kt` — it is multi-select only today, with no verb control of any kind
- [x] T036 [US1] Make the dependency picker reachable with zero dependencies — `TaskEditorContent.kt:194` renders the card only when `blockedBy.isNotEmpty() && availableTasks.isNotEmpty()`, so a task with no dependencies has no way in at all
- [x] T037 [US1] Add the follow-up marking to the marking set and the follow-up id set to the agenda evaluation, in `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaEvaluator.kt`

### The scenario and its carrier

- [x] T038 [US1] Create the scenario spec `scripts/src/traceability/scenarios/tasks/mark/TASK-MARK-01.yaml` — id `TASK-MARK-01`, area `feature.tasks`, `targets: [desktop]`, with `preconditions`, `steps` and `expected` — **and** its carrier `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/agenda/AgendaBadgePolicyFlowTest.kt` carrying `@DisplayName("TASK-MARK-01 …")`. The id prefix must match the path: `TASK-MARK` in `tasks/mark/`
- [ ] T039 [US1] Declare `TASK-MARK-01` in the `## Scenario Matrix` section of `specs/016-task-states-and-items/spec.md`, and run `python3 scripts/check-traceability-ratchet.py` — the scenario file and its carrier land in the same commit, so the hole count does not move

**Checkpoint**: At this point, User Story 1 should be fully functional and testable
independently.

---

## Phase 4: User Story 2 - Move a task between states deliberately (Priority: P1)

**Goal**: The user sees a task's state and the states it may move to, and moves it in
two actions from the task's own screen.

**Independent Test**: Open a task, change its state, and confirm the change is visible
in the list and in the detail screen, and survives leaving and re-entering the screen.

### Tests for User Story 2

- [x] T040 [P] [US2] Repository test in `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/` — a task set to `InProgress` reads back as `InProgress` from a fresh repository read, not from an in-memory copy
- [x] T041 [P] [US2] Compose flow test in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/TaskStateScenarioTest.kt` — the state control shows the current state and the states it may move to, and the chosen state is reflected on the screen

### Implementation for User Story 2

- [x] T042 [P] [US2] Add `workflowState: TaskWorkflowState = TaskWorkflowState.Open` to `TaskEditorModel` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorModel.kt`
- [x] T043 [P] [US2] Add the state row's callbacks to `TaskEditorCallbacks` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorCallbacks.kt` — the same `RowCallbacks` shape the priority and estimate rows use
- [x] T044 [US2] Render the state row in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorContent.kt`, as a `TaskAttributeCard` like the priority row — there is no segmented-control precedent on this screen
- [x] T045 [P] [US2] Create the state picker sheet `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorStateSheet.kt`, following `TaskEditorPrioritySheet.kt`
- [x] T046 [US2] Register `WorkflowState` in the `TaskEditorSheet` enum and add the branch in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorSheetsHost.kt` — one branch per sheet, no registry
- [x] T047 [US2] Freeze the state control on a completed task in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskEditorContent.kt` and `TaskEditorStateSheet.kt` — a completed task's state is shown but cannot be changed, and completing a task does not change its state and reopening does not reset it
- [x] T048 [US2] Create the scenario specs `scripts/src/traceability/scenarios/tasks/state/TASK-STATE-01.yaml`, `TASK-STATE-02.yaml` and `TASK-STATE-03.yaml` **and** their carriers in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/TaskStateScenarioTest.kt` — the id prefix must match the path: `TASK-STATE` in `tasks/state/`
- [ ] T049 [US2] Declare the three scenarios in the `## Scenario Matrix` of `specs/016-task-states-and-items/spec.md` and run `python3 scripts/check-traceability-ratchet.py`

**Checkpoint**: At this point, User Stories 1 AND 2 should both work independently.

---

## Phase 5: User Story 3 - Build an agenda view from task states (Priority: P1)

**Goal**: The user can build and save an agenda view filtered by workflow state, by
blocked, or by follow-up, and the view keeps applying that filter.

**Independent Test**: Build a view filtered to `InProgress`, save it, move a task into
that state, and confirm the saved view's contents follow without re-saving it.

### Tests for User Story 3

- [x] T050 [P] [US3] Registry round-trip test in `shared/src/jvmTest/kotlin/com/singularity/todo/feature/agenda/` — `Selector.WorkflowState` and `Selector.FollowUp` survive an encode and a decode, and the registry's size equals the expected type-tag count
- [x] T051 [P] [US3] Compose flow test in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/agenda/SavedAgendaSelectorConfiguratorFlowTest.kt` — the editor offers the state, blocked and follow-up sections, and the saved view shows only matching tasks with a count

### Implementation for User Story 3

- [x] T052 [P] [US3] Add `WorkflowState(state: TaskWorkflowState)` and `FollowUp` to the sealed hierarchy in `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/Selector.kt`, with `@SerialName("WorkflowState")` and `@SerialName("FollowUp")`
- [x] T053 [US3] Register both in `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/SelectorSerializer.kt` — the `typeTag` `when`, one entry each in `RegistryHolder.leafEntries`, **`require(it.size == 14)` becomes `require(it.size == 16)`**, and `expectedTypeTags` goes from 17 entries to 19. The `init` block cannot catch a variant missing from both lists, which is how `TimeOfDay` slipped through once
- [x] T054 [US3] Add the two match branches in `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/selector/SelectorMatcher.kt` and add `followUpIds: Set<TaskId> = emptySet()` to `SelectorContext` — the workflow-state selector needs no context, the follow-up one does
- [x] T055 [P] [US3] Add the catalogue entries in `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/selector/SelectorTemplate.kt` — including the entry that finally offers the existing `Selector.Blocked`, which has been in the hierarchy since before this feature with no way to reach it
- [x] T056 [P] [US3] Add the matching entries in `SelectorDescriptor.kt`, `SelectorBuilder.kt` and `SelectorBadge`'s `typeDescription`
- [x] T057 [P] [US3] Add the built-in views in `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaPresets.kt` — one for the tasks in progress, one for the blocked tasks
- [x] T058 [US3] Guard the saved-view read in `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/data/SavedAgendaViewsRepositoryImpl.kt` — an unknown `_type` makes `registry.allEntries.getValue(type)` throw, and because a saved view is one JSON blob, one unknown selector takes the whole view down. Drop the undecodable view; keep the rest of the list
- [x] T059 [US3] Create the scenario specs `scripts/src/traceability/scenarios/agenda/state/AGENDA-STATE-01.yaml` and `AGENDA-STATE-02.yaml` **and** their carriers in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/agenda/` — the id prefix must match the path: `AGENDA-STATE` in `agenda/state/`
- [ ] T060 [US3] Declare the two scenarios in the `## Scenario Matrix` of `specs/016-task-states-and-items/spec.md` and run `python3 scripts/check-traceability-ratchet.py`

**Checkpoint**: At this point, User Stories 1, 2 AND 3 should all work independently.

---

## Phase 6: User Story 4 - Add a tag that does not exist yet (Priority: P2)

**Goal**: The user creates a tag from the place where they choose tags, and it is
applied to the task they are working on.

**Independent Test**: Open the tag picker on a task, create a tag, and confirm it exists
and is applied to that task.

### Tests for User Story 4

- [x] T061 [P] [US4] Domain test in `shared/src/commonTest/kotlin/com/singularity/todo/feature/tags/` — a tag whose trimmed name matches an existing tag, compared case-insensitively, is found by `findByName`; a blank name is rejected with "Tag name cannot be blank"; a name over 100 code points is rejected with "Tag name too long (max 100 characters)"
- [x] T062 [P] [US4] Compose flow test in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/TaskTagScenarioTest.kt` — the picker offers a create action, the new tag is applied to the task, a duplicate is not created, and a blank name is refused with a stated reason

### Implementation for User Story 4

- [x] T063 [P] [US4] Add `findByName(tags: List<Tag>, name: String): Tag?` to `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/domain/TagDomain.kt` — the trimmed name compared case-insensitively. There is **no** uniqueness anywhere today: no unique index on `tags.name` (`Entities.kt:184-187`), no rule in `TagDomain`, no check in `TagsRepositoryImpl.create`
- [x] T064 [US4] Add the create affordance to `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/components/TagsPickerSheet.kt`, reusing `AddTagDialog` from `feature/tags/components/AddTagDialog.kt`. Inject `CreateTagUseCase` and `ProfileAwareCurrentUser` with `koinInject()` — both are services, and `CreateTagUseCase` is already bound as a `factory` in `core/di/TagsDiModule.kt:53-55`
- [x] T065 [US4] Apply the new tag to the task in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/components/TagsPickerSheet.kt` by adding its id to the sheet's own `selected` list — the existing "Done" button already carries the selection out through `SetTags`, so neither the sheet host nor the coordinator changes
- [x] T066 [US4] Surface the reason a name was refused in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/components/TagsPickerSheet.kt` — `CreateTagUseCase` returns a failed `Result` and `AppError.message` wins over any fallback, so the sheet renders `exceptionOrNull()?.toMessage()` next to the input
- [x] T067 [US4] Create the scenario spec `scripts/src/traceability/scenarios/tasks/tag/TASK-TAG-01.yaml` **and** its carrier in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/TaskTagScenarioTest.kt` — the id prefix must match the path: `TASK-TAG` in `tasks/tag/`
- [ ] T068 [US4] Declare `TASK-TAG-01` in the `## Scenario Matrix` of `specs/016-task-states-and-items/spec.md` and run `python3 scripts/check-traceability-ratchet.py`

**Checkpoint**: At this point, User Stories 1, 2, 3 AND 4 should all work independently.

---

## Phase 7: User Story 5 - Work through subtasks and checklist items in place (Priority: P2)

**Goal**: The task's subtasks and checklist items are on the task screen with real
checkboxes, a finished-of-total count, and an inline add-row.

**Independent Test**: Open a task with subtasks and checklist items, tick one of each,
and confirm both counts update and neither list touched the other.

### Tests for User Story 5

- [x] T069 [P] [US5] Compose flow test in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/TaskItemScenarioTest.kt` — both lists render with checkboxes, the counts update, and an item can be added without a modal opening

### Implementation for User Story 5

- [x] T070 [P] [US5] Promote `shared/src/commonMain/kotlin/com/singularity/todo/feature/checklist/components/ChecklistItemRow.kt` to the single shared row widget — it already takes `text`, `checked`, `onToggle` and `onDelete`; add an optional `onOpen` and remove its "Convert to task" menu item
- [x] T071 [US5] Delete the private duplicate `ChecklistItemRow` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/checklist/components/ChecklistEditorSheet.kt:120-163` — two house styles for one row is how they drifted
- [x] T072 [P] [US5] Add the test tags to `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` and regenerate the catalogue with `./gw :shared:jvmTest -PupdateGoldens=true --tests "*TestTagsCatalogJvmTest*"` — `TestTags.subtaskCheckbox(title)` already exists and is unused; it is the tag for exactly this checkbox
- [x] T073 [US5] Give the subtask rows real checkboxes in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/SubtasksSection.kt` — the rows are text today (`"✓ ${task.title}"` at line 147), and the layout itself already matches the reference
- [x] T074 [US5] Make the checklist section always render in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskDetailExtraSections.kt` — it returns early when the list is empty (line 61), which would leave no way to add the first item; the count reads "0 of 0" when empty
- [x] T075 [US5] Give the checklist rows real checkboxes in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/TaskDetailExtraSections.kt` — `"✓ ${item.title}"` at line 76 is a text prefix, not a checkbox
- [x] T076 [US5] Add the inline add-row to the checklist section and fire `TaskDetailIntent.Domain.AddChecklistItem` — the intent exists and is routed (`TaskChildrenSlot.kt:69,98-103`) but no UI on the detail screen emits it
- [x] T077 [US5] Drop the ten-item cap in both lists — `checklist.take(10)` with a `"+N more"` line, and the same in `SubtasksSection.kt`, silently truncate without saying what was left
- [x] T078 [US5] Fix the ordering in `shared/src/commonMain/kotlin/com/singularity/todo/feature/checklist/data/ChecklistRepositoryImpl.kt` — `addItem` numbers a new item by the **count** of existing rows (line 39), so a delete followed by an add collides and the tie is broken by rowid. Use `max(sortOrder) + 1`
- [x] T079 [US5] Re-point the Maestro flow `Maestro/flows/tasks/checklist/TASK-CHECK-01.yaml` at the inline section — the detail screen never opened the checklist sheet (it passes `checklist = null`), and after this story the items are inline. Its scenario file `scripts/src/traceability/scenarios/tasks/checklist/TASK-CHECK-01.yaml` moves with it
- [x] T080 [US5] Create the scenario spec `scripts/src/traceability/scenarios/tasks/item/TASK-ITEM-01.yaml` **and** its carrier in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/TaskItemScenarioTest.kt` — the id prefix must match the path: `TASK-ITEM` in `tasks/item/`
- [ ] T081 [US5] Declare `TASK-ITEM-01` in the `## Scenario Matrix` of `specs/016-task-states-and-items/spec.md` and run `python3 scripts/check-traceability-ratchet.py`

**Checkpoint**: At this point, User Stories 1–5 should all work independently.

---

## Phase 8: User Story 6 - Keep subtasks and checklist items apart (Priority: P3)

**Goal**: A subtask is a task and opens as one; a checklist item is not. Ticking one kind
never touches the other, and never touches the task.

**Independent Test**: Give one task both kinds of item, tick each in turn, and confirm
that the task's completion, the subtask list and the checklist list are each unaffected
by the other.

### Tests for User Story 6

- [x] T082 [P] [US6] Compose flow test in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/TaskItemScenarioTest.kt` — ticking a checklist item leaves the task and the subtasks untouched, ticking a subtask leaves the checklist untouched, and only a subtask opens as a task

### Implementation for User Story 6

- [x] T083 [US6] Make the shared row's "open as a task" affordance apply to subtasks only — in `shared/src/commonMain/kotlin/com/singularity/todo/feature/checklist/components/ChecklistItemRow.kt` and `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/components/detail/SubtasksSection.kt`. A checklist item is not presented as something that opens as a task of its own, while a subtask still opens through `onOpen` → `navigator.openDetail(it.id)`
- [x] T084 [US6] Confirm the two writes stay separate in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskChildrenSlot.kt` — a subtask toggle is `taskRepo.complete(subtask.id)` with its prerequisite guard, a checklist toggle is `checklistRepository.toggleItem(taskId, itemId, "user")` with no task-lifecycle semantics at all
- [x] T085 [US6] Create the scenario spec `scripts/src/traceability/scenarios/tasks/item/TASK-ITEM-02.yaml` **and** its carrier in `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/TaskItemScenarioTest.kt`
- [ ] T086 [US6] Declare `TASK-ITEM-02` in the `## Scenario Matrix` of `specs/016-task-states-and-items/spec.md` and run `python3 scripts/check-traceability-ratchet.py`

**Checkpoint**: All user stories should now be independently functional.

---

## Phase 9: Polish & Cross-Cutting Concerns

- [ ] T087 [P] Update `docs/architecture/` where the change makes a document wrong — in particular the checklist surface described for the task detail screen, and the two dependency projections
- [ ] T088 [P] Run `./scripts/refresh-decisions-digest.sh` and confirm `DIGEST.md` carries both new ADRs
- [ ] T089 Run the whole gate suite: `./gw :shared:compileKotlinJvm && ./gw :shared:jvmTest -Ptest.population=fast && bash scripts/ci/static-gates.sh`
- [ ] T090 Run the ten scenarios in `specs/016-task-states-and-items/quickstart.md` end to end, and record the outcome in the feature's `checklists/requirements.md`
- [ ] T091 Run `singularity-adr validate` and `openspec validate --changes`
- [ ] T092 Commit — one commit per story phase, each compiling and passing the gates on its own

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on Setup completion — BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on Foundational phase completion
  - User stories can then proceed in parallel (if staffed)
  - Or sequentially in priority order (P1 → P2 → P3)
- **Polish (Final Phase)**: Depends on all desired user stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after Foundational — no dependency on other stories
- **User Story 2 (P1)**: Can start after Foundational — no dependency on other stories
- **User Story 3 (P1)**: Can start after Foundational — depends on US1's widened dependency read path (T033) for the follow-up selector's id set; the rest of the story is independent
- **User Story 4 (P2)**: Can start after Foundational — no dependency on other stories
- **User Story 5 (P2)**: Can start after Foundational — no dependency on other stories
- **User Story 6 (P3)**: Depends on US5's shared row component (T070)

### Within Each User Story

- Tests (if included) MUST be written and FAIL before implementation
- Models before services
- Services before endpoints
- Core implementation before integration
- Story complete before moving to next priority

### Parallel Opportunities

- All Setup tasks marked [P] can run in parallel
- All Foundational tasks marked [P] can run in parallel (within Phase 2)
- Once Foundational phase completes, US2, US4 and US5 can start in parallel
- All tests for a user story marked [P] can run in parallel
- Models within a story marked [P] can run in parallel
- Different user stories can be worked on in parallel by different team members

---

## Parallel Example: User Story 1

```bash
# Launch the two test tasks together:
Task: "Domain test for the marking policy in shared/src/commonTest/.../feature/tasks/"
Task: "Replace AgendaBadgePolicyFlowTest.kt with the set-based equivalent"

# Launch the two model tasks together:
Task: "Create the TaskMarking enum in feature/tasks/domain/model/TaskMarking.kt"
Task: "Create the pure policy in feature/tasks/domain/logic/TaskMarkingPolicy.kt"
```

---

## Parallel Example: User Story 5

```bash
# The shared row, the test tags and the scenario spec are three different files:
Task: "Promote ChecklistItemRow.kt to the single shared row widget"
Task: "Add the test tags to core/ui/TestTags.kt and regenerate the catalogue"
Task: "Create the scenario spec scripts/src/traceability/scenarios/tasks/item/TASK-ITEM-01.yaml"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (CRITICAL — blocks all stories)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: Test User Story 1 independently
5. Deploy/demo if ready

### Incremental Delivery

1. Complete Setup + Foundational → Foundation ready
2. Add User Story 1 → Test independently → Deploy/Demo (MVP!)
3. Add User Story 2 → Test independently → Deploy/Demo
4. Add User Story 3 → Test independently → Deploy/Demo
5. Add User Story 4 → Test independently → Deploy/Demo
6. Add User Story 5 → Test independently → Deploy/Demo
7. Add User Story 6 → Test independently → Deploy/Demo
8. Each story adds value without breaking previous stories

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once Foundational is done:
   - Developer A: User Story 1
   - Developer B: User Story 2
   - Developer C: User Story 4
3. User Story 3 starts once T033 lands; User Story 6 starts once T070 lands
4. Stories complete and integrate independently

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps task to specific user story for traceability
- Each user story should be independently completable and testable
- Verify tests fail before implementing
- **A task closes only when its acceptance scenario runs on the production path** —
  the wiring the application actually uses, not a fake or a test-only seam
  (constitution IX). A path no scenario can reach is a path that does not
  deliver, and the task stays open until it does.
- **A scenario file and its carrier land in the same commit.** A scenario with a
  claim and no carrier is a hole, and a *growing* hole count fails
  `check-traceability-ratchet.py`. Creating the file and the carrier together
  keeps the count where it is; creating the file alone opens a hole that has to
  be paid for in `config/docs/traceability-ratchet.json`.
- **A carrier is a `@DisplayName("ID …")` in `desktopApp/src/jvmTest/kotlin` or
  `androidApp/src/androidTest/kotlin`, or a `tags: [scenario:ID]` in a Maestro
  flow header.** A test in `shared/src/jvmTest` is not a carrier — the scanner
  does not read it.
- **The id prefix must match the directory.** `TASK-MARK-01` belongs in
  `scenarios/tasks/mark/`, `AGENDA-STATE-01` in `scenarios/agenda/state/`.
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence
