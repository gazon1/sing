---
description: "Task list for pull-to-refresh: synchronisation and moving completed work to the archive"
---

# Tasks: Pull-to-refresh — synchronisation and moving completed work to the archive

**Input**: Design documents from `specs/015-pull-refresh-sync-archive/`
**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md
**Branch**: `015-pull-refresh-sync-archive`

**Tests**: included. The specification and the quickstart define eight validation scenarios that only a test can carry, and the project's own rules require them — every test class carries a JUnit tag from the closed dictionary (`fast`, `slow`, with sub-tags `slow:konsist`, `slow:migration`, `slow:compose`, `slow:data`, `slow:platform`), ViewModel tests run through the `testVm` harness with virtual time, fakes are used instead of mocks, and `SharedHarnessConventionsTest` forbids `stateIn`, `MockK`, `koinInject()`, `runBlocking`, `Thread.sleep`, `delay(N)` and `expectMostRecentItem` in test code.

**Organization**: tasks are grouped by user story so each is independently implementable, testable and mergeable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: can run in parallel — different files, no dependency on an incomplete task
- **[Story]**: the user story this task belongs to
- Every task names exact file paths

## Story ↔ layer mapping

The specification orders stories by user value; the plan orders the work by build dependency. They are not the same order, and this list follows the specification.

| Story | Priority | Delivers | Verified in |
|---|---|---|---|
| US1 — one gesture, fresh data | P1 | the gesture, the indicator, the report, the first screen | `RefreshCoordinatorTest`, `RefreshViewModelTest`, `RefreshableContentTest` |
| US2 — completed work goes to the archive | P1 | the archive step inside the refresh, and the count in the report | `RefreshCoordinatorTest`, `TaskDaoArchiveRepositoryImplTest` |
| US3 — refresh works without a network | P2 | the failure paths and the honest report | `RefreshOutcomeMessageTest`, `RefreshCoordinatorTest` |
| US4 — one refresh does not become two | P3 | the guard against a second run | `RefreshCoordinatorTest` |

**The screen rollout is not a story.** It is breadth: eighteen screens get the same wrapper, and it depends on US1 alone. It is therefore a separate phase that can start as soon as US1 lands, in parallel with US2, US3 and US4.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: the governance artifacts this repo requires before behaviour changes. No scaffolding is needed — the project, the module layout and the DI conventions already exist.

- [X] T001 [P] Run `openspec list --specs` and read `openspec/specs/MODULE-INDEX.md`, and record the spec targets before writing any artifact: the `tasks` capability spec already exists and must be **modified**, not re-created, and `core/ui` has no spec today, so this change is the first to touch it and must author one
- [X] T002 [P] Create the openspec change `openspec/changes/2026-10-25-the-refresh-is-one-run/` with `proposal.md`, `specs/` (a delta for the existing `tasks` capability, a new `core/refresh-gesture` spec), `design.md` (required — this is a cross-cutting concern) and `tasks.md` mirroring this file's phases with module-prefixed task names. The spec text describes observable behaviour only — no Kotlin type, function, class, DAO or Compose component names
- [X] T003 [P] Create the issue-registry record `.specify/issues/the-refresh-is-one-run.yaml` **in the same step** as T002 — the registry gate fails on a change with no record, and the slug is the change slug without its date prefix
- [X] T004 [P] Write `docs/decisions/2026-10-25-the-refresh-is-one-run.md` (status: accepted) recording: the single owner of the refresh run; the archive step running before the sync cycle; the narrow port the core declares instead of taking a feature port; the report belonging to the screen that asked; the reversal of "archiving is an action of one screen". Run `singularity-adr validate` and fix everything it reports

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: the vocabulary and the owner of the run. Every story consumes them, and putting them here is what keeps US2 from depending on US1's implementation.

- [X] T005 [P] Create `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshOutcome.kt`: `archived: Result<Int>` and `sync: SyncOutcome` — the sync verdict travels untouched, and `SyncOutcome` keeps its own vocabulary rather than being re-described
- [X] T006 [P] Create `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshUiState.kt`: `Idle` and `Refreshing` only. The result is a one-shot event, not state
- [X] T007 [P] Create `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshUiEvent.kt`: one variant, `Finished(outcome)`
- [X] T008 Create `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshCoordinator.kt`: the single owner of the run — a conflated channel, a completed-run counter, and `suspend fun request(): RefreshOutcome`. The run executes in the coordinator's own scope, not the caller's, so it survives the caller leaving. The sync step calls the public `SyncRepository.syncOnce()` facade, never the engine
- [X] T009 Create `shared/src/commonMain/kotlin/com/singularity/todo/core/di/RefreshDiModule.kt` exposing `fun refreshModule(): Module = module { … }`, and add `add(refreshModule())` to the `buildList` in `fun domainModule(…)` in `shared/src/commonMain/kotlin/com/singularity/todo/core/di/Modules.kt` — **never `includes()`**, whose child scope is the bug the inlined bindings were there to avoid, and never `toTypedArray()`
- [X] T010 [P] Create `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRefreshDependencies.kt` with `FakeSyncRepository`: it records every `syncOnce()` call and returns a scripted `SyncOutcome`. No mock, no real transport

**Checkpoint**: the coordinator runs one sync and returns an outcome; nothing is on screen yet.

---

## Phase 3: User Story 1 — One gesture, fresh data (Priority: P1) 🎯 MVP

**Goal**: on a screen showing the user's data, pulling the content down runs a synchronisation, shows an indicator while it runs, and reports the outcome.

**Independent Test**: on the agenda, pull the list down — the indicator appears, the fake synchronisation is called once, and a report appears naming the outcome. Mergeable without US2, US3 or US4.

### Tests for User Story 1

- [X] T011 [P] [US1] Create `shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshCoordinatorTest.kt` (`@Tag("fast")`): one request calls `syncOnce()` exactly once and returns the fake's `SyncOutcome`; the run executes on the coordinator's scope, so a caller that goes away does not cancel it
- [X] T012 [P] [US1] Create `shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshViewModelTest.kt` (`@Tag("fast")`) through the `testVm` harness from `shared/src/commonTest/kotlin/com/singularity/todo/test/helpers/RunVmTest.kt`: the state goes `Idle` → `Refreshing` → `Idle` and the outcome arrives as `RefreshUiEvent.Finished`. Use `awaitState { }` — never a bare `advanceUntilIdle()` poll loop
- [X] T013 [P] [US1] Create `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshableContentTest.kt` (`@Tag("slow")`, `@Tag("slow:compose")`) with `runIsolatedComposeTest` from `desktopApp/src/jvmTest/kotlin/com/singularity/todo/test/helpers/IsolatedComposeTest.kt`

### Implementation for User Story 1

- [X] T014 [US1] Create `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshViewModel.kt`: an MVI ViewModel — `StateFlow<RefreshUiState>`, `Flow<RefreshUiEvent>`, a sealed `Refresh` intent. The refreshing flag is cleared in a `finally`, so a failure cannot leave the screen stuck refreshing; the injected scope is registered with `addCloseable`
- [X] T015 [US1] Create `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshableContent.kt`: the shared composable — `PullToRefreshBox` above the screen's scrollable, `PullToRefreshDefaults.Indicator`, the refresh ViewModel through `koinViewModel()`, and the report through `NotificationHost`. The composable takes the ViewModel as a parameter with a `koinViewModel()` default, so previews can pass a fake
- [X] T016 [US1] Add the success row of the message table from `contracts/refresh-outcome.md` to `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshUiEvent.kt`: `Notification.Text` naming the synchronisation, with no count — the archive step does not exist yet
- [X] T017 [US1] Wrap the list in `RefreshableContent` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/AgendaContent.kt` — the seam that proves the feature is wired to something
- [X] T018 [P] [US1] Add automation tags to `shared/src/commonMain/kotlin/com/singularity/todo/core/ui/TestTags.kt` for the refresh indicator and the refresh notification host, then regenerate `Maestro/TAGS.md` — a new constant is unusable until the catalogue is rewritten (`./gw :shared:jvmTest -PupdateGoldens=true`, then re-run without the flag)

**Checkpoint**: US1 is fully functional on its own — the agenda refreshes with one gesture and says so.

---

## Phase 4: User Story 2 — Completed work goes to the archive (Priority: P1)

**Goal**: the same gesture moves every completed task of the active profile into the archive and reports how many moved.

**Independent Test**: complete a task, pull the agenda down, assert the task moved to the archive and the report carries the count — and assert the archived row was queued for synchronisation, which is what carries it to the server inside the same gesture.

### Tests for User Story 2

- [X] T019 [P] [US2] Extend `shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshCoordinatorTest.kt`: the archive step runs **before** the sync step — the recording fakes keep the order, and the order is the reason the archive reaches the server in the same gesture; the returned count is the archiver's own; a failed archive does not prevent the sync
- [X] T020 [P] [US2] Extend `shared/src/jvmTest/kotlin/com/singularity/todo/feature/archive/TaskDaoArchiveRepositoryImplTest.kt` (`@Tag("slow")`, `@Tag("slow:data")`): assert the archived rows are enqueued for synchronisation. Without this, the server keeps the un-archived copy and the next pull restores every task
- [X] T021 [P] [US2] Extend `shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRefreshDependencies.kt` with `FakeCompletedTaskArchiver`: records the calls, returns a scripted count or a scripted failure

### Implementation for User Story 2

- [X] T022 [US2] Create `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/CompletedTaskArchiver.kt`: the narrow port the core needs — `suspend fun archiveCompletedTasks(): Result<Int>`. The core takes no port from a feature; the dependency that closed the cycle once already is ADR `2026-10-06-the-sync-engine-needs-the-repositories-and-the-repositories-need-the-engine`
- [X] T023 [US2] Change `shared/src/commonMain/kotlin/com/singularity/todo/feature/archive/domain/port/ArchiveRepository.kt` to extend `CompletedTaskArchiver` — the implementation `TaskDaoArchiveRepositoryImpl` does not change by a line
- [X] T024 [US2] Change `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshCoordinator.kt` to take the archiver and run the archive step **before** the sync step; the run's `archived` is the archiver's own `Result<Int>`
- [X] T025 [US2] Bind the archiver to the archive repository in `shared/src/commonMain/kotlin/com/singularity/todo/core/di/RefreshDiModule.kt`
- [X] T026 [US2] Add the count to the success message in `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshUiEvent.kt` — the number is the tasks moved by **this** refresh, not the number of completed tasks in the profile

**Checkpoint**: US2 works on its own — the refresh also clears completed work out of the list.

---

## Phase 5: User Story 3 — Refresh works without a network (Priority: P2)

**Goal**: when the server cannot be reached, the completed work still moves to the archive and the report says honestly what did not happen.

**Independent Test**: make the synchronisation fail; assert the tasks still moved, the report names the failure, and it says the archival happened locally.

### Tests for User Story 3

- [X] T027 [P] [US3] Create `shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshOutcomeMessageTest.kt` (`@Tag("fast")`): one case per row of the message table in `contracts/refresh-outcome.md` — moved>0 and synced; moved=0 and synced; moved>0 and failed; moved=0 and failed; not signed in; archive failed
- [X] T028 [P] [US3] Extend `shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshCoordinatorTest.kt`: a failing synchronisation yields an outcome carrying **both** the archive success and the sync failure — the two steps are independent

### Implementation for User Story 3

- [X] T029 [US3] Change `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshCoordinator.kt` so the archive step runs whether or not the synchronisation succeeded, and the sync's verdict travels in the outcome untouched. "No sync scope" (`SyncOutcome.NothingToDo`) must stay distinguishable from a failure — it means the user is not signed in, not that something broke
- [X] T030 [US3] Add the failure rows to `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshUiEvent.kt`: `Notification.Error` for a failed synchronisation, with the reason in plain words and no technical codes, and a message stating that the archival already happened on the device

**Checkpoint**: US3 works on its own — the refresh is honest when the network is gone.

---

## Phase 6: User Story 4 — One refresh does not become two (Priority: P3)

**Goal**: pulling again while a refresh is running joins the running one instead of starting a second, and nothing is sent to the server twice.

**Independent Test**: start a refresh, request a second while it runs; assert one archive call, one sync call, and that both callers receive the same outcome.

### Tests for User Story 4

- [X] T031 [P] [US4] Extend `shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshCoordinatorTest.kt`: a second request during a running refresh joins it — one archive call, one sync call, and both callers receive the same outcome

### Implementation for User Story 4

- [X] T032 [US4] Change `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshViewModel.kt` so the `Refreshing` state rejects a second intent while one is running
- [X] T033 [US4] Change `shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshableContent.kt` so the gesture is inert while the state is `Refreshing` — and the screen still scrolls while the refresh runs

**Checkpoint**: all four stories are independently functional.

---

## Phase 7: Screen rollout (breadth, not a story)

**Purpose**: the same wrapper on the remaining seventeen screens. It depends on US1 alone and may run in parallel with US2–US4.

- [X] T034 [P] Wrap the content in `RefreshableContent` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/SavedAgendaListScreen.kt` and `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/SavedAgendaContent.kt`
- [X] T035 [P] Wrap the content in `RefreshableContent` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/screen/ProjectsScreen.kt` (the "Plans" tab) and `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/screen/ProjectDetailBody.kt`
- [X] T036 [P] Wrap the content in `RefreshableContent` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/screen/NotesListScreen.kt` and `shared/src/commonMain/kotlin/com/singularity/todo/feature/notes/presentation/screen/NotePreviewScreen.kt`
- [X] T037 [P] Wrap the content in `RefreshableContent` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/statistics/StatisticsScreen.kt`, `shared/src/commonMain/kotlin/com/singularity/todo/feature/search/SearchScreen.kt` and `shared/src/commonMain/kotlin/com/singularity/todo/feature/proposals/presentation/ProposalsScreen.kt`
- [X] T038 [P] Wrap the content in `RefreshableContent` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/chat/ChatScreen.kt` and `shared/src/commonMain/kotlin/com/singularity/todo/feature/profile/ProfileSwitcherScreen.kt`
- [X] T039 [P] Wrap the content in `RefreshableContent` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/TagsScreen.kt`, `shared/src/commonMain/kotlin/com/singularity/todo/feature/tags/presentation/screen/TagGroupsScreen.kt` and `shared/src/commonMain/kotlin/com/singularity/todo/feature/archive/ArchiveScreen.kt` — the archive screen keeps its existing explicit "archive all completed" action exactly as it is (FR-019); the refresh is added beside it, not instead of it
- [X] T040 Wrap the content in `RefreshableContent` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar/presentation/screen/CalendarScreen.kt`. The only vertical scrollable on that screen is the hour grid in `shared/src/commonMain/kotlin/com/singularity/todo/feature/calendar/presentation/components/calendar/TimeGridView.kt`, and it is scrolled programmatically to the current hour — so the gesture is reachable only at the very top of the grid. Record that as a known limitation in the pull request, not as a silent surprise
- [X] T041 Wrap the content in `RefreshableContent` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/screen/TaskDetailContent.kt`. The screen's vertical scroll lives in the editor scaffold (`feature/tasks/presentation/components/detail/TaskEditorContent.kt`), so the gesture reaches the coordinator through it — the container does not supply one. Check that the content does not jump when the scroll container appears
- [X] T042 Wrap the content in `RefreshableContent` in `shared/src/commonMain/kotlin/com/singularity/todo/feature/pomodoro/PomodoroScreen.kt`. **This screen has only a horizontal scroll today** (the task-chip row) — the screen's own root grows the vertical one, in the order the codebase uses (`fillMaxSize().padding().verticalScroll()`), because a scroll container measures its content with an unbounded height and would collapse a content that fills the screen
- [X] T043 [P] Run `python3 scripts/find-unwired-surfaces.py` and assert the container is called from every screen in scope. "Implemented but wired to nothing" is the most common defect in this repo: code that compiles, is covered by tests, and is never called
- [X] T044 [P] Create `desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshScrollAnchorTest.kt` (`@Tag("slow")`, `@Tag("slow:compose")`): with the screen's own scroll on its root, the gesture is reachable and the content does not jump when the pull is released

**Checkpoint**: every screen in the specification's scope carries the gesture, and the scan proves each one calls it.

---

## Phase 8: Polish & Cross-Cutting Concerns

- [X] T045 [P] Update `docs/architecture/sync.md` — it describes the current state of sync, and the refresh trigger belongs there. A change to that document updates it in the same commit, as its own header requires
- [X] T046 [P] Regenerate `DIGEST.md` with `./scripts/refresh-decisions-digest.sh` after the ADR from T004 lands
- [X] T047 [P] Run the validation scenarios V-01…V-08 from `specs/015-pull-refresh-sync-archive/quickstart.md` and record the outcome — including the two-device check that the archive reaches the server inside the same gesture. V-01…V-07 passed and are recorded; **V-08 was not run** — there is no emulator or device on this host — and the outcome says so in the quickstart
- [X] T048 [P] Run the full population: `./gw :shared:jvmTest` and `./gw :desktopApp:test`. A targeted green run is not a baseline
- [ ] T049 [P] Run `just lint-changed` and `just gate`, and fix everything they report
- [ ] T050 [P] Commit `specs/015-pull-refresh-sync-archive/` and the code it names in the repository's style: one commit per logical group — the core, the seam, the rollout — and every commit builds and passes the gates on its own, so a bisect points at a place that exists

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: no dependencies — can start immediately
- **Foundational (Phase 2)**: depends on Setup — blocks every user story
- **User Stories (Phases 3–6)**: all depend on Phase 2. US2 depends on US1's message shape only in wording; US3 and US4 depend on nothing but Phase 2
- **Screen rollout (Phase 7)**: depends on **US1 alone** — it may start as soon as US1 lands, in parallel with US2, US3 and US4
- **Polish (Phase 8)**: depends on the phases it touches

### User story dependencies

- **US1 (P1)**: starts after Phase 2 — no dependency on other stories
- **US2 (P1)**: starts after Phase 2 — the archive step is additive to the run and changes nothing about US1's behaviour
- **US3 (P2)**: starts after Phase 2 — the failure paths are additive to the message mapper
- **US4 (P3)**: starts after Phase 2 — the guard is additive to the coordinator and the ViewModel

### Within each user story

- Tests are written before the implementation and must fail first
- The coordinator before the ViewModel before the composable before the screen
- The screen wrapper last: it is the part that proves the feature is wired

### Parallel opportunities

- All Setup tasks marked [P] run in parallel
- All Foundational tasks marked [P] run in parallel
- All tests within a story marked [P] run in parallel
- The screen rollout (Phase 7) runs in parallel with US2, US3 and US4
- US2, US3 and US4 may run in parallel with each other — they touch different files except `RefreshCoordinator.kt` (US2 and US3) and `RefreshUiEvent.kt` (US2 and US3), where the two changes must be sequenced

---

## Parallel example: User Story 1

```bash
# The three tests together, before any implementation exists:
Task: "Create shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshCoordinatorTest.kt"
Task: "Create shared/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshViewModelTest.kt"
Task: "Create desktopApp/src/jvmTest/kotlin/com/singularity/todo/core/refresh/RefreshableContentTest.kt"

# The two independent pieces of the foundation together:
Task: "Create shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshOutcome.kt"
Task: "Create shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshUiState.kt"
Task: "Create shared/src/commonMain/kotlin/com/singularity/todo/core/refresh/RefreshUiEvent.kt"
Task: "Create shared/src/commonMain/kotlin/com/singularity/todo/test/fakes/FakeRefreshDependencies.kt"
```

---

## Implementation strategy

### MVP first (User Story 1 only)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational — blocks every story
3. Complete Phase 3: User Story 1
4. **Stop and validate**: the agenda refreshes with one gesture and reports the outcome
5. Ship or demo if ready — the gesture works, the archive step does not exist yet

### Incremental delivery

1. Setup + Foundational → the foundation is ready
2. US1 → validate independently → the gesture exists on one screen
3. US2 → validate independently → the refresh also clears completed work
4. US3 → validate independently → the refresh is honest offline
5. US4 → validate independently → repeated pulls do not duplicate work
6. Phase 7 → the gesture reaches every screen in scope
7. Each step adds value without breaking the previous ones

### Parallel team strategy

1. The team completes Setup and Foundational together
2. Once Foundational is done:
   - Developer A: US1, then US2
   - Developer B: US3
   - Developer C: US4, then Phase 7 (the rollout needs US1 only)
3. US2 and US3 both touch `RefreshCoordinator.kt` and `RefreshUiEvent.kt` — sequence those two, or take them in the order given

---

## Notes

- [P] tasks = different files, no dependencies
- [Story] label maps a task to its user story for traceability
- Every user story is independently completable and testable
- Verify the tests fail before implementing
- Commit after each task or logical group
- Stop at any checkpoint to validate the story independently
- Avoid: vague tasks, same-file conflicts, cross-story dependencies that break independence
- The eighteen screens are counted from the working tree: the agenda serves three bottom-bar routes as one screen, "Plans" is the projects screen, and `AppDestination.AiUsage` is outside the scope
