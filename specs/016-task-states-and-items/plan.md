# Implementation Plan: Task States and Inline Task Items

| | |
|---|---|
| **Branch** | `016-task-states-and-items` — the **feature identifier**, not a git branch |
| **Date** | 2026-10-26 |
| **Spec** | [spec.md](spec.md) |

**On the branch.** The identifier above is derived from the feature directory — no git
branch of that name exists, because the `before_specify` and `before_plan` hooks that
would create one are not registered in `.specify/extensions.yml`. The checkout is on
`gazon1/skimmer`; the implementation lands on a branch and a worktree created at
implementation time, per the repository's worktree convention.

**Input**: Feature specification from `specs/016-task-states-and-items/spec.md`

## Summary

Make a task's state legible and movable, and put the task's parts on the screen where the
work happens.

Three things are delivered together, because they share one idea — *a task should say
what it is*:

1. **A stored workflow state.** A task carries a state the user moves deliberately:
   *Open*, *Next up*, *In progress*. It is a new column on `tasks`, written by hand
   because the derived auto-migration would rewrite the table and null the notes that
   point at a task.
2. **A set of markings.** What a task is marked with — blocked, follow-up, in progress,
   next up, overdue, no date, pinned, repeating, completed — becomes one vocabulary and one
   policy, replacing the agenda's first-non-null-wins badge (which is never rendered today)
   and the task row's parallel booleans.
3. **The task's parts, inline.** Subtasks and checklist items get real checkboxes, a
   finished-of-total count, and an inline add-row on the task screen, and the tag picker
   can create a tag that does not exist yet.

On top of that, agenda views gain sections filtered by workflow state, by blocked and by
follow-up, so the markings are a working tool rather than decoration.

**The technical shape**: a domain enum and a pure policy function in the tasks feature; a
new column, a manual migration, a backup DTO field and a server allowlist row; a set-valued
row model that both the task list and the agenda render through one composable; two new
`Selector` variants added to the five places the serializer registry requires; and three
UI surfaces rewritten against a single shared checkbox row.

**Phase order** and the reasoning behind each decision: [research.md](research.md).

## Technical Context

**Language/Version**: Kotlin 2.4.10 (multiplatform), JDK 17 toolchain

**Primary Dependencies**: Compose Multiplatform 1.12.0 (Material3), Room 3.0.0, Koin 4.2.2
(pure DSL, `koin-compiler-plugin` 1.2.1), kotlinx-serialization 1.11.0, kotlinx-coroutines,
kotlinx-datetime, kotlinx-collections-immutable

**Storage**: Room 3 over SQLite (schema version 46 → **47**), one database file per profile;
Supabase/Postgres as the sync server (`sync_field_allowlist` decides what the server
accepts); JSON backup files written whole

**Testing**: JUnit 5 with `@Tag("fast")` / `@Tag("slow")`; JVM tests in `:shared:jvmTest`
run inside `:shared:jvmTest`; Compose UI tests on Desktop in `desktopApp/src/jvmTest`;
fakes in `shared/src/commonTest/.../test/fakes/` (no mocks); Maestro flows on Android

**Target Platform**: Android + JVM Desktop only — **no iOS** (constitution I)

**Project Type**: Kotlin Multiplatform application (Android shell + Desktop Compose entry),
with a shared KMP library, an MCP server and a CLI

**Performance Goals**: The agenda evaluates selectors over the task list it has already
loaded; the marking policy must not add a per-task query. A list row must render its
markings without a layout pass that changes the row height for the common case.

**Constraints**: Offline-capable; the sync server is not required for the feature to work
locally; no migration may rewrite the `tasks` table (that nulls `notes.task_id`); the
backup format must round-trip; a saved view must not take the saved-views list down when
one selector type is unknown to the reading build

**Scale/Scope**: One user profile per database file; typical task lists in the hundreds,
tested to thousands; 6 workstreams (D19) touching ~45 source files across 5 modules

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Verdict | Evidence / required action |
|---|---|---|---|
| **I. KMP Architecture** | Android + JVM Desktop only, no iOS | **PASS** | Everything lives in `commonMain`. The Desktop Compose target is affected by one fact that *supports* the design: a sheet on Desktop resolves to a `Dialog` in a separate window, so a JVM Compose test cannot address anything inside one (`scripts/src/traceability/scenarios/tasks/checklist/TASK-CHECK-01.yaml:9-14`). Inline rendering is the testable one. |
| **II. ADR Before Code** | ADR for an architectural pattern change or a new cross-cutting concern | **PASS, with two ADRs required** | (a) *A task carries a stored workflow state* — a data-layer change with a migration and a wire format. (b) *One marking vocabulary* — the replacement of `AgendaBadge` and the row booleans with one set. The dependency-verb work **extends** `2026-10-23-the-dependency-graph-has-two-projections.md` and does not need a new record. |
| **III. Validate Early and Often** | `singularity-adr validate` after every ADR change | **PASS** | Run after each ADR is written; the phases in `tasks.md` each carry it. |
| **IV. Immutable Historical Record** | Accepted ADRs are never edited | **PASS** | The two new ADRs are new files. The follow-ups ADR is extended by a superseding record, never edited. |
| **V. CI Gates** | `singularity-adr validate` + `openspec validate --changes` | **PASS** | Both are in the per-phase gate list below. |
| **VI. MVI ViewModels** | Sealed intent + single state; never `stateIn`, `viewModelScope` or `runBlocking` | **PASS** | Two new intents (`SetWorkflowState`, and the already-present `AddChecklistItem` gets its first UI caller). **No new ViewModel is introduced** — the tag creation is deliberately sheet-local (D13), which is what keeps `TaskDetailDeps` unchanged. |
| **VII. Test Fakes** | Fakes over mocks; virtual time | **PASS** | New tests use the fakes in `shared/src/commonTest/.../test/fakes/`; no `delay(N)`, only `advanceUntilIdle()`. |
| **VIII. OpenSpec Workflow** | proposal → specs → design → adr → tasks → implement → converge | **PASS** | Satisfied through the spec-kit path this repository also runs (`docs/spec-kit-guide.md`): `specs/016-…/spec.md` → this plan → `tasks.md` → implement → converge. The OpenSpec `openspec/changes/` path is the parallel route and is not required for the same work. |
| **IX. The Acceptance Scenario Runs the Production Path** | A scenario must exercise the production wiring, not a test double | **PASS** | [quickstart.md](quickstart.md) drives the real coordinator, repository and DI graph. The scenarios assert on state that only the production wiring produces — a saved view decoded from the database, a task written by the real repository, a tag applied through `setTags`. |

**Re-check after Phase 1 design** — the design introduces no new principle and no exception.
The one place the design bends a house rule is recorded under *Complexity Tracking* below.

**Gates that run for this feature** (from `scripts/ci/static-gates.sh`, in the order
`docs/architecture/verification.md` prescribes):

```text
./gw :shared:compileKotlinJvm              # compile right after the connected change
./gw :shared:jvmTest -Ptest.population=fast
bash scripts/ci/static-gates.sh            # the whole static gate suite
python3 scripts/check-test-tags-catalog.py --check
python3 scripts/check-room-schema-integrity.py
python3 scripts/check-sync-allowlist-regenerated.py
singularity-adr validate
openspec validate --changes
```

## Project Structure

### Documentation (this feature)

```text
specs/016-task-states-and-items/
├── plan.md              # This file (/speckit-plan command output)
├── research.md          # Phase 0 output — 19 decisions with rationale and rejected alternatives
├── data-model.md        # Phase 1 output — entities, fields, validation, state transitions
├── quickstart.md        # Phase 1 output — runnable validation scenarios
├── contracts/           # Phase 1 output — the three formats this feature changes
│   ├── sync-wire.md            # the task document on the wire
│   ├── backup-format.md        # the backup file's task record
│   └── agenda-view-json.md     # the saved view's selector document
├── checklists/
│   └── requirements.md  # the spec quality checklist
└── tasks.md             # Phase 2 output (/speckit-tasks command)
```

**The scenario matrix** is not a document in this directory — it is the pair
`scripts/src/traceability/scenarios/<area>/<group>/<ID>.yaml` (the declaration) and its
carrier (a `@DisplayName("ID …")` in `desktopApp/src/jvmTest/kotlin`, or a
`tags: [scenario:ID]` in a Maestro flow header). Both land in the **same commit**, and
the declaration is echoed in the `## Scenario Matrix` section of `spec.md`. That ordering
is required: a scenario with a claim and no carrier is a hole, and a *growing* hole
count fails `check-traceability-ratchet.py`.

### Source Code (repository root)

```text
shared/src/commonMain/kotlin/com/singularity/todo/
├── feature/tasks/
│   ├── domain/
│   │   ├── model/
│   │   │   ├── TaskWorkflowState.kt        # NEW — the stored state enum
│   │   │   ├── TaskMarking.kt              # NEW — the one marking vocabulary
│   │   │   └── Task.kt                     # + workflowState field
│   │   └── logic/
│   │       └── TaskMarkingPolicy.kt        # NEW — the pure set-valued policy
│   ├── data/
│   │   └── TaskRepositoryImpl.kt           # + setWorkflowState, + verb-aware dependency write
│   └── presentation/
│       ├── state/
│       │   ├── TaskDetailIntent.kt         # + SetWorkflowState
│       │   └── TaskDetailUiState.kt        # + markings on the row model
│       ├── viewmodel/
│       │   ├── TaskDetailCoordinator.kt    # + state intent routing
│       │   └── slot/
│       │       ├── TaskEntitySlot.kt       # + workflow-state write
│       │       └── TaskChildrenSlot.kt     # + checklist add-row wiring
│       ├── components/
│       │   ├── list/
│       │   │   ├── TaskMarkingsRow.kt      # NEW — the shared markings renderer
│       │   │   ├── TaskRowContent.kt        # + markings slot
│       │   │   └── TaskMetaRow.kt           # booleans → markings
│       │   └── detail/
│       │       ├── TaskEditorContent.kt     # + the workflow-state row
│       │       ├── TaskEditorModel.kt       # + workflowState
│       │       ├── TaskEditorCallbacks.kt   # + the state row callbacks
│       │       ├── TaskDetailExtraSections.kt  # checklist rows + always-on section
│       │       ├── SubtasksSection.kt       # checkbox rows
│       │       └── TaskEditorStateSheet.kt  # NEW — the state picker sheet
│       ├── screen/
│       │   ├── TaskDetailContent.kt        # + state row, + checklist add wiring
│       │   └── TaskCreateScreen.kt          # the checklist sheet path
│       └── model/
│           └── TaskUi.kt                   # booleans → markings set
├── feature/agenda/
│   ├── domain/
│   │   ├── model/
│   │   │   ├── Selector.kt                 # + WorkflowState, + FollowUp
│   │   │   ├── SelectorSerializer.kt       # + 2 registry entries, + count 14→16, + tags 17→19
│   │   │   └── AgendaUiState.kt            # AgendaBadge → TaskMarking
│   │   ├── selector/
│   │   │   ├── SelectorMatcher.kt          # + 2 match branches, + followUpIds in the context
│   │   │   ├── SelectorTemplate.kt         # + catalogue entries
│   │   │   ├── SelectorBuilder.kt          # + builder entries
│   │   │   └── SelectorDescriptor.kt       # + descriptor entries
│   │   └── logic/
│   │       ├── AgendaBadgePolicy.kt        # first-wins → a set
│   │       └── AgendaEvaluator.kt          # + followUpIds, + the shared policy
│   ├── data/
│   │   └── SavedAgendaViewsRepositoryImpl.kt   # + the undecodable-view guard
│   └── presentation/screen/
│       └── AgendaContent.kt                # renders the marking set
├── feature/checklist/
│   ├── components/ChecklistItemRow.kt      # the single shared row widget
│   ├── data/ChecklistRepositoryImpl.kt     # + max(sortOrder)+1
│   └── domain/port/ChecklistRepository.kt  # unchanged — the API is already right
├── feature/tags/
│   ├── components/TagsPickerSheet.kt       # + the create affordance
│   ├── domain/TagDomain.kt                 # + the duplicate rule
│   └── domain/usecase/TagsUseCase.kt       # unchanged — CreateTagUseCase is reused as-is
├── core/
│   ├── database/
│   │   ├── AppDatabase.kt                  # SCHEMA_VERSION 46 → 47
│   │   ├── Entities.kt                     # + the workflow_state column
│   │   ├── Mappers.kt                      # + the entity ↔ domain mapping
│   │   ├── Daos.kt                         # + the typed-dependency read
│   │   ├── AppDatabaseFactory.kt           # + the manual migration registration
│   │   └── Migration46To47.kt              # NEW — the hand-written migration
│   ├── backup/BackupTaskDtos.kt            # + the workflowState field
│   ├── sync/SyncContract.kt                # unchanged — the wire list is derived
│   └── ui/TestTags.kt                      # + the new tags
└── core/di/
    ├── TagsDiModule.kt                     # unchanged — CreateTagUseCase is already bound
    └── TasksDiModule.kt                    # unchanged — ChecklistRepository is already bound

shared/schemas/com.singularity.todo.core.database.AppDatabase/
└── 47.json                                 # NEW — the regenerated export

shared/src/jvmTest/kotlin/com/singularity/todo/core/database/
└── Migration46To47Test.kt                  # NEW — the round trip, generated from the export

supabase/migrations/
└── <date>-sync-workflow-state.sql          # NEW — the server allowlist row

config/docs/sync-field-allowlist.json       # + task.workflowState (wire + serverWritable)
supabase/sync_field_allowlist_seed.sql      # regenerated by infra/gen_sync_allowlist_sql.py
```

**Structure Decision**: the feature keeps the repository's existing module boundaries. The
stored state lives with the task in `feature/tasks`; the marking vocabulary lives with the
task too, **not** in the agenda, because the task list must not depend on the agenda (the
agenda already depends on the tasks) and because a marking is a fact about a task rather
than about a view. The agenda imports the vocabulary. The checklist row widget is promoted
to the one shared row component and used by both lists, which is why it keeps its home in
`feature/checklist` — the tasks feature already depends on the checklist feature for the
`ChecklistItem` model.

**Nothing in this plan adds a new module, a new Koin module, or a new ViewModel.** The
three new use-case-free seams are: a domain enum, a pure policy function, and a set on the
row model.

## Complexity Tracking

> No constitution principle is violated. The two entries below are **deliberate deviations
> from the house default**, recorded because a reviewer will notice them.

| Deviation | Why Needed | Simpler Alternative Rejected Because |
|-----------|------------|-------------------------------------|
| **A hand-written migration instead of `AutoMigration(46 → 47)`** | The derived auto-migration for a changed `tasks` table rewrites the whole table; dropping it fires the foreign key that points `notes.task_id` at a task, and the notes lose their task. `Migration45To46` exists for exactly this reason (`AppDatabaseFactory.kt:51-53`). | `AutoMigration` — it nulls `notes.task_id` on every upgrade. The one-line `ALTER TABLE … ADD COLUMN` is *simpler* than the auto-migration and safer; the extra code is the hand-registration, which is the price of not losing notes. |
| **A repository-level guard that drops an undecodable saved view** | `SelectorSerializer.deserialize` does `registry.allEntries.getValue(type)`, so an unknown `_type` throws — and because a saved view is one JSON blob, one unknown selector takes the **whole view** down. This feature adds two type tags, so it makes that path reachable for the first time. | Letting the decode fail — a device that has the new selectors and then loses them would no longer be able to show **any** saved view. Making the *decoder* skip unknown sections is not possible: it decodes one selector to one selector, with no list level at which it could skip. |
