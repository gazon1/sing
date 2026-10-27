---
description: "Task list for feature implementation"
---

# Tasks: Plans Timeline and Projects Menu

**Input**: Design documents from `/specs/016-plans-timeline-projects-menu/`

**Prerequisites**: plan.md (required), spec.md (required for user stories), research.md, data-model.md, contracts/

**Tests**: Tests are included — the constitution's principle IX requires each task to close on a scenario that runs the production path, and the OpenSpec rule requires every behaviour-changing task to name the test that proves it. Write the test first, watch it fail, then implement.

**Organization**: Tasks are grouped by user story so each story can be implemented, tested and delivered independently.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependencies)
- **[Story]**: Which user story this task belongs to (e.g., US1, US2, US3)
- Include exact file paths in descriptions

## Paths

All paths are relative to the repository root.

- **Shared code**: `shared/src/commonMain/kotlin/com/singularity/todo/`
- **Shared tests**: `shared/src/commonTest/kotlin/com/singularity/todo/`
- **Desktop UI tests**: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/`
- **Instrumented Android tests**: `androidApp/src/androidTest/kotlin/com/singularity/todo/`
- **Maestro flows**: `Maestro/flows/`

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: The change record, and the module the feature lives in.

- [x] T001 Write the decision record `docs/decisions/2026-10-26-plans-timeline-and-projects-menu.md` — the timeline as a feature of its own, the row vocabulary promoted into the tasks feature, the menu gaining an entry that is not a destination, the Calendar leaving the tab list
- [x] T002 Run `singularity-adr validate` and confirm a zero exit — a non-zero exit is a broken contract
- [x] T003 Write the change record `openspec/changes/plans-timeline-and-projects-menu/` — `proposal.md`, `design.md`, `adr.md`, `tasks.md`, and the two capability specs under `specs/plans-timeline/` and `specs/navigation-and-menu/`
- [x] T004 Import the change into the registry with `python3 scripts/openspec-import-all.py` (writes `.specify/issues/plans-timeline-and-projects-menu.yaml`) and refresh the digest with `./scripts/refresh-decisions-digest.sh`
- [x] T005 Create the module skeleton `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/` with `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/PlansDiModule.kt`, and register the module in `shared/src/commonMain/kotlin/com/singularity/todo/core/di/Modules.kt` by list composition — never `toTypedArray()`
- [x] T006 Add the feature's settings namespace `PLANS` to `shared/src/commonMain/kotlin/com/singularity/todo/core/settings/SettingsNamespace.kt`

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: The vocabulary both screens share, and the value types the timeline is built from. No user story work can begin until this phase is complete.

- [x] T007 Use **one** row vocabulary for the agenda and the timeline. The vocabulary itself is the tasks feature's: the timeline's row is `TimelineTask` (`shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/domain/model/TimelineTask.kt`), carrying the task, the markings and the series size — the same three facts the agenda's row carries
- [x] T008 The vocabulary is a **set of markings**, not a single badge: `TaskMarking` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/model/TaskMarking.kt`. A task that is both blocked and overdue shows both
- [x] T009 The policy that computes the markings is the tasks feature's — `computeTaskMarkings` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/TaskMarkingPolicy.kt`. The timeline calls it, so both surfaces answer "what is this task marked with?" the same way
- [x] T010 Every consumer uses that one policy — the agenda (`AgendaEvaluator`, `AgendaContent`) and the timeline (`TimelineGrouper`) — so a task looks the same wherever the user meets it
- [x] T011 [P] Create `PlansGranularity` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/domain/model/PlansGranularity.kt` — the cases `Day`, `Week`, `Month`, each carrying its number of periods: 30, 12, 12
- [x] T012 [P] Create `TimelinePeriod` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/domain/model/TimelinePeriod.kt` — `id`, `start`, `end`, where `start <= end` always holds
- [x] T013 [P] Create `TimelineSection` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/domain/model/TimelineSection.kt` — `id`, `label`, `period`, `tasks`
- [x] T014 Implement the period builder `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/domain/logic/TimelinePeriods.kt` — the periods a granularity produces from today; a day period is one calendar day, a week period runs Monday to Sunday, a month period is a calendar month; the periods of one granularity are pairwise disjoint and sorted
- [x] T015 Write `shared/src/commonTest/kotlin/com/singularity/todo/feature/plans/domain/PlansGranularityTest.kt` — the horizon of each granularity, and that an unreadable stored value falls back to the day
- [x] T016 Write `shared/src/commonTest/kotlin/com/singularity/todo/feature/plans/domain/TimelinePeriodsTest.kt` — the periods are disjoint, ordered, cover the horizon, and a week starts on Monday

**Checkpoint**: The vocabulary and the value types are in place — user story implementation can now begin.

---

## Phase 3: User Story 1 - Read the upcoming work as a sequence of days (Priority: P1) 🎯 MVP

**Goal**: The Plans tab shows the open work as a chronological list of day sections, each holding the tasks due on that day, with overdue work leading.

**Independent Test**: Seed tasks due today, in six days and on an empty day in between; open the Plans tab and read the sections from top to bottom.

### Tests for User Story 1

- [x] T017 [US1] Write `shared/src/commonTest/kotlin/com/singularity/todo/feature/plans/domain/TimelineGrouperTest.kt` — a task lands in exactly one section; overdue comes first and appears in no dated section; a period with no tasks is not a section; a completed task is in no section; a task without a due date is in no section
- [x] T018 [US1] Write `shared/src/commonTest/kotlin/com/singularity/todo/feature/plans/presentation/viewmodel/PlansViewModelTest.kt` — the sections follow the tasks, and a task whose date changes moves between sections
- [x] T019 [US1] Write the screen test `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/plans/PlansSectionsFlowTest.kt` — the headers read as days in order, and a header with no task is absent

### Implementation for User Story 1

- [x] T020 [US1] Implement the grouper `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/domain/logic/TimelineGrouper.kt` — a pure function of tasks, granularity and today; it assigns each task to the one period containing its due date, puts overdue work first in a section of its own, drops empty periods, and drops completed and undated tasks
- [x] T021 [US1] Implement the ViewModel `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/presentation/viewmodel/PlansViewModel.kt` — the canonical MVI pattern with an injected scope, no `stateIn`, no `viewModelScope`, no `runBlocking`; it reads the tasks from two date ranges, the overdue pool and the horizon
- [x] T022 [US1] Implement the section list `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/presentation/components/PlansSectionList.kt` — keying each row as the section and the task together, so a duplicated task renders twice instead of crashing the list
- [x] T023 [US1] Implement the screen `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/presentation/screen/PlansContent.kt` and `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/presentation/screen/PlansScreen.kt` — the section list, the per-section add control, and the empty state that says there is nothing scheduled
- [x] T024 [US1] Bind the tab — `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AppDestination.kt` renders the timeline for `AppDestination.Plans`, and `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/ScreenFamily.kt` gains a family for it
- [x] T025 [US1] Change the Plans action button in `shared/src/commonMain/kotlin/com/singularity/todo/shell/FabActionResolver.kt` — it creates a task due today, not a project
- [x] T026 [US1] Add the timeline's test tags to `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`, then regenerate the catalogue with `./gw :shared:jvmTest -PupdateGoldens=true`
- [x] T027 [US1] Write the carriers for PLANS-01, PLANS-02, PLANS-05 and PLANS-10 — the flows under `Maestro/flows/plans/` and the desktop UI tests under `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/plans/`, each tagged with `scenario:<id>` so the coverage matrix claims it

**Checkpoint**: User Story 1 is fully functional and testable independently — the Plans tab is a timeline.

---

## Phase 4: User Story 2 - Change the granularity of the timeline (Priority: P1)

**Goal**: The user can switch between days, weeks and months, and the choice is remembered.

**Independent Test**: Switch to week, leave the tab, come back — the screen is still in week granularity.

### Tests for User Story 2

- [x] T028 [US2] Write `shared/src/commonTest/kotlin/com/singularity/todo/feature/plans/domain/PlansSettingsRepositoryTest.kt` — the granularity round-trips through the store, and a stored value that cannot be read back yields the day
- [x] T029 [US2] Extend `shared/src/commonTest/kotlin/com/singularity/todo/feature/plans/presentation/viewmodel/PlansViewModelTest.kt` — a change of granularity re-groups the sections, and the sections the user was looking at are rebuilt from the same tasks
- [x] T030 [US2] Write the screen test `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/plans/PlansGranularityFlowTest.kt` — the control names the granularity in effect, and the headers change with it

### Implementation for User Story 2

- [x] T031 [US2] Create the settings port `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/domain/port/PlansSettingsRepository.kt` and the DataStore implementation `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/data/DataStorePlansSettingsRepository.kt`, keyed in the feature's own namespace
- [x] T032 [US2] Extend the ViewModel — the granularity comes from the profile's settings, and a change of it re-runs the query for the new horizon rather than only re-grouping what was already fetched
- [x] T033 [US2] Add the granularity control to `shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/presentation/screen/PlansContent.kt` — it names the granularity in effect, and choosing another re-groups the list in place
- [x] T034 [US2] Make the choice persist — the selected granularity is written to the profile's settings and restored when the screen is opened again
- [x] T035 [US2] Add the control's test tag to `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`, then regenerate the catalogue with `./gw :shared:jvmTest -PupdateGoldens=true`
- [x] T036 [US2] Write the carriers for PLANS-03, PLANS-04, PLANS-07, PLANS-08 and PLANS-09 — the flows under `Maestro/flows/plans/` and the desktop UI tests under `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/plans/`

**Checkpoint**: User Stories 1 AND 2 both work independently — the timeline is switchable and remembers.

---

## Phase 5: User Story 3 - Reach projects from the menu as a tree (Priority: P1)

**Goal**: The menu carries the projects as an expandable tree, and tapping one opens it.

**Independent Test**: Create a parent project with a child, open the menu, expand the parent, and read the child indented under it.

### Tests for User Story 3

- [x] T037 [US3] Write `shared/src/commonTest/kotlin/com/singularity/todo/feature/projects/domain/logic/ProjectTreeBuilderTest.kt` — the nesting, a project whose parent is gone landing at the top level, a closed project being absent, and a cycle being caught rather than looped over
- [x] T038 [US3] Write `shared/src/commonTest/kotlin/com/singularity/todo/feature/projects/presentation/viewmodel/ProjectsTreeViewModelTest.kt` — the tree follows the repository without the menu being reopened
- [x] T039 [US3] Write the menu test `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/menu/ProjectsMenuFlowTest.kt` — the section is present, the children are indented, and tapping a node opens the project

### Implementation for User Story 3

- [x] T040 [US3] Create `ProjectTreeNode` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/domain/model/ProjectTreeNode.kt` — the project, its children, and its depth
- [x] T041 [US3] Implement the tree builder `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/domain/logic/ProjectTreeBuilder.kt` — it arranges the projects into roots and children using the repository's depth-first traversal in `shared/src/commonMain/kotlin/com/singularity/todo/core/tree/TreeVisitor.kt`, so a cycle is an error rather than a hang
- [x] T042 [US3] Implement the tree ViewModel `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/viewmodel/ProjectsTreeViewModel.kt` — the canonical MVI pattern with an injected scope
- [x] T043 [US3] Implement the shared tree composable `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/components/ProjectTreeSection.kt` — the expander on a node with children, the indent by depth, and the entry into the full project list
- [x] T044 [US3] Give the menu an entry kind that is not a destination in `shared/src/commonMain/kotlin/com/singularity/todo/shell/MenuBottomSheet.kt` — a project in a tree is not a navigation destination, and the destination set is a closed, serialisable type
- [x] T045 [US3] Render the Projects section in the Android bottom sheet `shared/src/commonMain/kotlin/com/singularity/todo/shell/MenuBottomSheet.kt`, using the shared composable from T043
- [x] T046 [US3] Keep the full project list reachable — the entry at the end of the Projects section opens `AppDestination.ProjectsGraph(AppDestination.ProjectsStartRoute.List)`
- [x] T047 [US3] Add the tree's test tags to `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`, then regenerate the catalogue with `./gw :shared:jvmTest -PupdateGoldens=true`
- [x] T048 [US3] Write the carriers for MENU-01, MENU-02, MENU-03, MENU-05 and MENU-06 — the flows under `Maestro/flows/menu/` and the desktop UI tests under `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/menu/`

**Checkpoint**: User Stories 1, 2 AND 3 work independently — the projects are a tree in the menu.

---

## Phase 6: User Story 4 - Reach the Calendar from the menu (Priority: P2)

**Goal**: The Calendar leaves the bottom navigation bar and becomes an entry of the menu on both platforms.

**Independent Test**: Look at the bottom navigation bar — no Calendar; open the menu — the Calendar is there, and it opens the screen.

### Tests for User Story 4

- [x] T049 [US4] Extend `shared/src/commonTest/kotlin/com/singularity/todo/feature/nav/DestinationKindTest.kt` — the tab list no longer holds the Calendar, the menu list does, and the other tabs are still there
- [x] T050 [US4] Write the parity test `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/menu/MenuParityFlowTest.kt` — the Android sheet and the Desktop drawer offer the same entries and the same tree

### Implementation for User Story 4

- [x] T051 [US4] Move the Calendar in `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AppDestination.kt` — out of `DestinationKind.tabs` and into `DestinationKind.menuEntries`; the Calendar screen itself is not touched
- [x] T052 [US4] Add the Calendar entry to the Android sheet's own section list in `shared/src/commonMain/kotlin/com/singularity/todo/shell/MenuBottomSheet.kt` — that list is separate from `DestinationKind.menuEntries` and does not follow it
- [x] T053 [US4] Render the same tree and the same entries in the Desktop drawer `shared/src/jvmMain/kotlin/com/singularity/todo/shell/DesktopShellNav3.kt`, using the shared composable from T043
- [x] T054 [US4] Write the carriers for MENU-04, NAV-01 and NAV-02 — the flows under `Maestro/flows/nav/` and the desktop UI tests under `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/menu/`

**Checkpoint**: All four user stories work independently.

---

## Phase 7: Polish & Cross-Cutting Concerns

- [x] T055 Lower the ratchet floor in `config/docs/traceability-ratchet.json` to the hole count the carriers actually reached, with the reason written next to the number
- [x] T056 Regenerate the committed coverage documents with `PYTHONPATH=scripts/src python3 -m traceability coverage`, and confirm `just trace-scenarios` and `just trace-validate` pass
- [x] T057 Verify the change against the implemented behaviour, and correct the capability specs under `openspec/changes/plans-timeline-and-projects-menu/specs/` where what was built differs from what was specified
- [x] T058 Update the navigation documentation `docs/navigation.md` for the Calendar's move and the new tree
- [x] T059 Run the validation from `specs/016-plans-timeline-projects-menu/quickstart.md` — the common, JVM and host tests, the ADR validation, the traceability checks, and `just gate`. Everything this change owns is green: the plans, projects and menu flows (26 desktop carriers, all green), the shared suite (4164 tests, one failure — upstream's `LineFramerTest`), the ADR validation, the openspec import, the traceability checks and the ratchet. Six things are red on `origin/main` itself and were red before this change: `origin/main` does not compile (the bulk `setDependencyRelations` passed string ids where task ids are expected — fixed in the merge), `a_specification_and_the_tree_agree` (upstream's `specs/015-recurring-series-badges/spec.md` has 37 requirements with no traceability row), `doc links` and `doc dead refs` (upstream's `specs/015-fix-task-logbook`), `maestro keyboard commands have something to hide` (upstream's `TASK-LOG-02.yaml`), `OpenTaskFromAgendaFlowTest.task_editor_shows_correct_priority_from_agenda` (upstream's test asserts a row that sits below the fold) and `LineFramerTest.everySplitPointProducesTheSameLine` (upstream's framer drops the last line of a response that does not end with a newline). Every one of them was verified red on a pristine `origin/main` as well — `origin/main` does not even compile without the one-line fix the merge carries
- [x] T060 Refresh the generated documentation with `just docs-regen`, and archive the change once the implementation and the specs agree

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: No dependencies — can start immediately
- **Foundational (Phase 2)**: Depends on the Setup phase — BLOCKS all user stories
- **User Stories (Phase 3+)**: All depend on the Foundational phase
  - US1 and US2 are one deliverable in the plan (Part A) and are sequential: US2's control switches what US1 renders
  - US3 and US4 are one deliverable in the plan (Part B) and touch the same menu file
- **Polish (Phase 7)**: Depends on all four stories being complete

### User Story Dependencies

- **User Story 1 (P1)**: Can start after the Foundational phase — no dependencies on other stories
- **User Story 2 (P1)**: Can start after US1 — it re-groups what US1 renders; it does not depend on US3 or US4
- **User Story 3 (P1)**: Can start after the Foundational phase — independent of US1 and US2
- **User Story 4 (P2)**: Can start after the Foundational phase — independent of US1 and US2

### Within Each User Story

- The test is written and fails before the implementation
- The pure domain logic before the ViewModel, the ViewModel before the screen
- The screen before the navigation binding
- The carrier before the task is closed

### Parallel Opportunities

- T007, T008, T009, T011, T012 and T013 can run in parallel — different files, no dependencies between them
- T015 and T016 can run in parallel — different test files
- T017 and T018 can run in parallel — different test files
- T020, T021 and T022 can run in parallel once their tests fail — different files
- T028, T029 and T030 can run in parallel — different test files
- T037, T038 and T039 can run in parallel — different test files
- T040 and T041 can run in parallel — different files
- T049 and T050 can run in parallel — different test files

### File contention — these do NOT run in parallel

- T044, T045 and T046 all touch `shared/src/commonMain/kotlin/com/singularity/todo/shell/MenuBottomSheet.kt`
- T052 touches the same file as T045
- T051 and T024 both touch `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AppDestination.kt`
- T026, T035 and T047 all touch `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt`, and the catalogue regeneration must run after the last of them

---

## Parallel Example: User Story 1

```bash
# The pure logic and its test, together with the value types:
Task: "Create PlansGranularity in shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/domain/model/PlansGranularity.kt"
Task: "Create TimelinePeriod in shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/domain/model/TimelinePeriod.kt"
Task: "Create TimelineSection in shared/src/commonMain/kotlin/com/singularity/todo/feature/plans/domain/model/TimelineSection.kt"

# The two tests of the story, together:
Task: "Write shared/src/commonTest/kotlin/com/singularity/todo/feature/plans/domain/TimelineGrouperTest.kt"
Task: "Write shared/src/commonTest/kotlin/com/singularity/todo/feature/plans/presentation/viewmodel/PlansViewModelTest.kt"
```

---

## Implementation Strategy

### MVP First (User Story 1 only)

1. Complete Phase 1: Setup — the change record and the module
2. Complete Phase 2: Foundational — the vocabulary and the value types
3. Complete Phase 3: User Story 1 — the days
4. **STOP and VALIDATE**: open the Plans tab and read the days
5. Ship it as the MVP: the tab is a timeline, the projects are still reachable from the list

### Incremental Delivery

1. Setup + Foundational → the foundation is ready
2. US1 → validate → the timeline is a sequence of days
3. US2 → validate → the timeline is switchable and remembers
4. US3 → validate → the projects are a tree in the menu
5. US4 → validate → the Calendar is in the menu
6. Polish → the holes are closed and the specs match what was built

### Parallel Team Strategy

With multiple developers:

1. Team completes Setup + Foundational together
2. Once the Foundational phase is done:
   - Developer A: User Stories 1 and 2 (the timeline — one screen, one deliverable)
   - Developer B: User Stories 3 and 4 (the menu — one file, one deliverable)
3. The two tracks touch different files until the menu work begins; the menu track owns
   `MenuBottomSheet.kt` outright

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
- The eighteen scenarios declared by this feature are `PLANS-01`…`PLANS-10`,
  `MENU-01`…`MENU-06` and `NAV-01`, `NAV-02`. Their behaviour is written in
  `scripts/src/traceability/scenarios/`, and a carrier claims one with a
  `scenario:<id>` tag.
- Commit after each task or logical group
- Stop at any checkpoint to validate story independently
- Avoid: vague tasks, same file conflicts, cross-story dependencies that break independence

---

## The review of this change

An independent reviewer (a separate agent, given the diff and the decision record rather
than the session that wrote it) read the change and returned **pass**. What it found, and
what was done about it, is here — the full text of the review is the record this section
carries, because the review gate needs a pull request number and this change has none yet
(it is not pushed), so the verdict file cannot be written in the shape the gate reads.

## What was reviewed

Reviewed by the Verifier agent (session mvs_20781668fe244b0d974169af8ee32517) at HEAD
6f7ac892 (contains the feature 9393ca2d and the merge aa5863f2); the tree also carries 15
uncommitted files, including `ProjectsTreeViewModel.kt` and the spec/ADR edits, and those
were read as they stand on disk.

Five checks: (1) `TimelineGrouper.kt` in full, with `TimelinePeriods.kt`, `TimelinePeriod.kt`,
`TimelineLabels.kt`; (2) `PlansViewModel.kt`, `PlansUiState.kt`, `PlansDeps.kt`,
`DataStorePlansSettingsRepository.kt`, the control in `PlansContent.kt`; (3)
`ProjectTreeBuilder.kt` with `core/tree/TreeVisitor.kt`; (4) `AppMenu.kt`, `MenuBottomSheet.kt`,
the drawer block of `DesktopShellNav3.kt`, `DestinationKind.menuEntries`; (5)
`PlansSectionList.kt` with `PlansScreen.kt`, `NavStartRoutes.kt`, `TasksNavGraph.kt`,
`TaskCreateViewModel.kt`, `AgendaContent.kt`.

Checks run: `scripts/find-unwired-surfaces.py` -> "No unwired surfaces found." (it needs
`PYTHONPATH=scripts/src`; without it: `ModuleNotFoundError: No module named 'scanners'`).
`PYTHONPATH=scripts/src python3 -m traceability validate` -> "✓ спееков: 128 / ✓ связей: 63".

## What was found

Nothing that blocks the change. Three notes:

1. **The granularity is not per profile (medium).** FR-014, D-06 and the ADR say per profile;
   the value is one flat key `plans.granularity` in the app-wide DataStore
   (`DataStorePlansSettingsRepository.kt:24`, `SettingsRepository.kt:196`), so two profiles
   share it.
2. **Spec scenario 6 contradicts FR-009 (medium).** `spec.md:71-72` says a task due in 45 days
   shows in month granularity and "in neither day nor week"; FR-009 (`spec.md:293`) and
   `PlansGranularity.kt:15` make week 12 weeks (84 days), so the task does appear in a week
   section. The code follows FR-009; the scenario as written does not hold.
3. **The week/month "+" prefills a past date (low).** `PlansSectionList.kt:80` passes
   `period.start`; for the current week or month that is before today
   (`TimelinePeriods.kt:47,55`), so the new task is created already overdue - the very reason
   the overdue section carries no "+" (`PlansSectionList.kt:50`).

Verified good: one section per task, completed excluded (`TimelineGrouper.kt:58-82`); the
grouping re-runs from the settings flow without resetting state (`PlansViewModel.kt:60-77`);
closed and deleted projects excluded and a cycle caught rather than hung
(`ProjectTreeBuilder.kt:38,106`); sheet and drawer draw the same `AppMenu` entries
(`MenuBottomSheet.kt:185-189`, `DesktopShellNav3.kt:249`); rows are the shared `TaskRowFlat`
(`PlansSectionList.kt:158`) and "+" reaches `TaskCreateScreen` (`PlansScreen.kt:57`).

The two medium findings are fixed: the granularity is now keyed by the profile-scoped user
id, so the choice a profile made is the choice that profile sees (FR-014), and the spec's
scenario 6 now says what FR-009 says — the day horizon is 30 days, the week horizon 12 weeks,
the month horizon 12 months. The low finding is fixed as well: a section's add control
prefills today when the section holds today, so the new task lands in the section it was
added from and is not born overdue (FR-016).
