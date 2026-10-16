# tasks — split `ProjectDetailViewModel`

Grouped by technical phase. Nothing here is user-visible.

Legend: `[P]` = genuinely independent of the other marked tasks (disjoint files).

---

## Phase 1 — Foundation: pure task-list derivation

- [x] **T001** [P] Create `ProjectTaskListDeriver` with `blockedIds`, `visible`,
      `children` and the `take(5)` + sort projection, as pure functions with no
      coroutines and no repository dependency.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/viewmodel/ProjectTaskListDeriver.kt`
  - Depends on: none
  - Verification: new `ProjectTaskListDeriverTest` — `./gradlew :shared:jvmTest --tests 'com.singularity.todo.feature.projects.presentation.viewmodel.ProjectTaskListDeriverTest'`
  - Acceptance: AC-05
  - Note: move the logic verbatim from `ProjectDetailViewModel.kt:135` and
    `:269-277`. Do not reformat or "improve" while moving — a diff that mixes a
    move with an edit cannot be reviewed.

- [x] **T002** [P] Write `ProjectTaskListDeriverTest` covering hide-completed,
      hide-blocked, dependency-blocking, the `take(5)` cap, and sort order.
  - Files: `shared/src/commonTest/kotlin/com/singularity/todo/feature/projects/presentation/viewmodel/ProjectTaskListDeriverTest.kt`
  - Depends on: T001
  - Verification: `ProjectTaskListDeriverTest` — same command as T001
  - Acceptance: AC-05
  - Tag the class `@Tag("fast")`; without it `-Ptest.tags` silently excludes it.

---

## Phase 2 — Core: reminder coordinator

- [x] **T003** [P] Create `ProjectReminderCoordinator` taking
      `ProjectRemindersRepository`, `NotificationsSettingsRepository`,
      `projectId` and a project `Flow`, exposing `offsets(): Flow<ReminderOffsets>`
      and `suspend fun set(offsetMinutes: Int?)`.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/viewmodel/ProjectReminderCoordinator.kt`
  - Depends on: none
  - Verification: new `ProjectReminderCoordinatorTest` — `./gradlew :shared:jvmTest --tests 'com.singularity.todo.feature.projects.presentation.viewmodel.ProjectReminderCoordinatorTest'`
  - Acceptance: AC-04
  - The `fireAt = dueDate − offset` rule must be a **named function** here, not an
    inline lambda in a collector. That naming is the point of the extraction.

- [x] **T004** [P] Write `ProjectReminderCoordinatorTest` for the four reminder
      rules: `fireAt = dueDate − offset`, default preselects without creating,
      `null` removes, and no-due-date rejects.
  - Files: `shared/src/commonTest/kotlin/com/singularity/todo/feature/projects/presentation/viewmodel/ProjectReminderCoordinatorTest.kt`
  - Depends on: T003
  - Verification: `ProjectReminderCoordinatorTest` — same command as T003
  - Acceptance: AC-04
  - Use `FakeProjectReminders` and the existing notification-settings fake; no
    MockK, no `delay`.

---

## Phase 3 — Integration: rewire the ViewModel

- [x] **T005** Replace the inline reminder collectors (`:168-199`, `:232-249`)
      and `setReminder` (`:531-559`) with the coordinator, keeping
      `reminderOffsetsFlow` as the ViewModel's slot for the combine.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/viewmodel/ProjectDetailViewModel.kt`
  - Depends on: T003
  - Verification: `ProjectDetailViewModelTest` — `./gradlew :shared:jvmTest --tests 'com.singularity.todo.feature.projects.ProjectDetailViewModelTest'`
  - Acceptance: AC-01, AC-04
  - All six reminder tests must pass **unmodified**.

- [x] **T006** Replace the inline task-list derivation at `:135` and `:269-279`
      with calls into `ProjectTaskListDeriver`.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/viewmodel/ProjectDetailViewModel.kt`
  - Depends on: T001
  - Verification: `ProjectDetailViewModelTest` — same command as T005
  - Acceptance: AC-01, AC-05
  - T005 and T006 both edit `ProjectDetailViewModel.kt`, so they are **not**
    marked `[P]` and must be serialised.

- [x] **T007** Confirm the screen contract is untouched: `stateFlow`,
      `lastEditedAt`, `draftState.state`, `clock`, `events`, `onIntent` all keep
      their types, and no new `collectAsStateWithLifecycle` was added.
  - Files: `shared/src/commonMain/kotlin/com/singularity/todo/feature/projects/presentation/screen/ProjectDetailContent.kt` (read-only check)
  - Depends on: T005, T006
  - Verification: `./gradlew :desktopApp:assembleDebug` and grep for added
    collectors
  - Acceptance: AC-02, AC-03

---

## Phase 4 — Cleanup

- [x] **T008** Remove the unused `log: Logger` constructor parameter and the
      matching `log = Logger.withTag("ProjectDetail")` DI argument.
  - Files: `ProjectDetailViewModel.kt`,
      `core/di/ProjectsDiModule.kt`
  - Depends on: T005, T006
  - Verification: `./gradlew :shared:jvmTest --tests 'com.singularity.todo.feature.projects.ProjectDetailViewModelTest'`
    plus `grep -rn "log" <VM file>` returning no parameter
  - Acceptance: AC-07
  - Must confirm the koin-compiler graph still verifies (AC-06).

- [x] **T009** Run the full gate suite and record the real outcome.
  - Files: none
  - Depends on: T008
  - Verification: `./check.sh` and
    `python3 scripts/find-unwired-surfaces.py`
  - Acceptance: AC-06
  - This is where an extracted collaborator that nothing calls gets caught. Two
    new public classes are exactly the shape `find-unwired-surfaces.py` exists
    for.

---

## Coverage check

| AC | Covered by |
|---|---|
| AC-01 emitted state | T005, T006 — full suite unmodified |
| AC-02 one atomic snapshot | T007 |
| AC-03 public contract | T007 |
| AC-04 reminder semantics | T003, T004, T005 |
| AC-05 task-list derivation | T001, T002, T006 |
| AC-06 DI graph | T008, T009 |
| AC-07 dead dependency | T008 |

Every criterion is claimed by at least one task. No task lacks an acceptance
criterion or a named test id.