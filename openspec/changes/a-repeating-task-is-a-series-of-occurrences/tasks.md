# Tasks — a repeating task is a series of occurrences

**status:** accepted

---

## Phase 1 — The ADR

- [ ] `docs/decisions/2026-10-25-a-recurring-series-carries-the-rule-on-every-row.md`:
      the superseding ADR, with the two kinds of series, the window, the close
      semantics, the ceiling rotation and the repair
- [ ] `docs/decisions/2026-10-22-task-series-materialized-copies.md`: frontmatter
      carries `status: superseded` and `superseded-by:` — the content is untouched
- [ ] `singularity-adr validate` passes
- [ ] `./scripts/refresh-decisions-digest.sh` — the digest does not repeat the
      superseded ADR's bullets

## Phase 2 — The window (pure, testable first)

- [ ] `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/logic/RecurringSeriesWindow.kt`:
      `plan(rule, anchor, horizonDays, maxFuture)` — pure, no clock, no repository
- [ ] The window reuses `RecurrenceCalculator.nextOccurrence` rather than a second date
      arithmetic
- [ ] The window checks the rule's end condition itself, through
      `RecurrenceSpec.isExhaustedBy` — `nextOccurrence` does not read `termination`
- [ ] The anchor is the caller's: the due date for `FROM_DUE` and `CATCH_UP`, the
      completion date for `FROM_COMPLETION`
- [ ] Tests: weekly, daily (the cap binds), yearly (the `beyond` clause binds), a rule
      that has run out (nothing), the anchor excluded from the window, and the same rule
      at the same anchor twice giving the same dates
- [ ] A test that the window does not change when the calendar day changes

## Phase 3 — The recurring series

- [ ] `feature/tasks/domain/logic/RecurringSeriesPlanner.kt` — the plan of a recurring
      series: the rows to create, the rows to remove when the rule changes, the row
      that becomes the current occurrence, the row that leaves the series at the ceiling
- [ ] `feature/tasks/domain/logic/TaskSeriesInvariants.kt` — the two-kind rule; a series
      is either picked dates without a rule, or the rule's dates with the rule on
      every row
- [ ] `MaterializeRecurringSeriesUseCase` — the write that fills the window, in one
      transaction through the existing `UnitOfWork`: `createSeries(newRows)` and
      `update(detachedRow)` as two calls, because `createSeries` refuses a batch with
      two series ids. Either order is safe
- [ ] `feature/tasks/domain/usecase/CompleteRecurringTaskUseCase.kt` — close and
      materialise; `moveDueDate` leaves the close path
- [ ] The three bases differ only in the anchor date
- [ ] A rule that has run out closes the occurrence and stops the series; **nothing is
      archived** — the `terminate()` soft-delete leaves the close path
- [ ] The series rotates at the ceiling: the oldest closed row leaves the series and
      stays a task — **and loses the rule with the series id**, or the launch repair
      adopts it back into a new series and the ceiling becomes a loop
- [ ] `feature/tasks/domain/usecase/RepairRecurringSeriesUseCase.kt` — the launch
      repair, idempotent, reopening a row only when the series has no open occurrence
- [ ] `core/database/Daos.kt` — a query for the repair's own rows: this profile's
      tasks that carry a rule and belong to no series. No such query exists today
- [ ] `feature/tasks/domain/usecase/DetachTaskFromSeries.kt` — an occurrence that leaves
      a recurring series loses the rule with the series id; the rest of the series keeps
      the rule and continues from the members that remain. Without this the row keeps the
      repeat badge and never repeats. The row that stays behind when the series ends
      loses the rule as well — a rule on a row that stands alone is the shape the repair
      adopts back into a series
- [ ] `feature/tasks/domain/usecase/MergeTaskSeries.kt` — a join refuses a mixed pair: a
      recurring series with a series of picked dates, and two recurring series whose rules
      differ. The refusal names the reason; a join of two series of the same kind is
      unchanged
- [ ] The repair is wired into the launch hooks beside `ProfileBootstrapper` and
      `BackgroundWorkBootstrapper` — in `desktopApp/src/main/kotlin/com/singularity/todo/main.kt`
      and `androidApp/src/main/kotlin/com/singularity/todo/SingularityApp.kt`
- [ ] The repair's scope is the active profile; another profile is repaired at the
      next launch with that profile active
- [ ] Tests: the window is filled; closing does not move the date and does not shrink
      the future; a refusal changes nothing; changing the rule re-derives the future and
      keeps the closed rows; the ceiling rotates and the rotated row carries no rule; the
      repair is idempotent; the occurrence that leaves a recurring series carries no rule
      and the rest of the series still does; the row that stays behind when the series
      ends carries no rule either; a mixed join is refused with a reason

## Phase 3b — The two doors that set a rule

- [ ] `feature/tasks/domain/usecase/CreateTaskFromDraft.kt` — a draft with a rule and
      no extra dates materialises a series. **Today the rule is dropped**: the planner
      clears `recurrence` on every row it writes, including a single-date draft
- [ ] `feature/tasks/presentation/viewmodel/slot/TaskEntitySlot.kt` — setting a rule on
      a task turns it into a series with a materialised window
- [ ] A rule together with picked dates: the rule is dropped, which is what the create
      screen already tells the user
- [ ] `RecurrenceSpec.kt` — the KDoc calls `CATCH_UP` "like FROM_COMPLETION", while the
      implementation anchors it on the due date; the comment is corrected to match the
      behaviour
- [ ] Tests: a task created with a rule carries it; a draft with a rule and a second
      date carries no rule

## Phase 4 — The repeat badge

- [ ] `feature/agenda/presentation/screen/AgendaContent.kt` — the row is built with
      `isRecurring` derived from the task's own rule, not hardcoded
- [ ] `feature/tasks/presentation/components/list/TaskRowContent.kt` — `hasMeta` opens
      for `isRecurring`
- [ ] `feature/tasks/presentation/components/list/TaskMetaRow.kt` — `hasSomethingToShow`
      opens for `isRecurring`
- [ ] The badge carries the content description "Повторяющаяся задача"
- [ ] Desktop Compose UI test: a repeating task with no due date, no project, not
      blocked and not in a series still shows the meta row and the badge

## Phase 5 — The project badge

- [ ] `core/database/Daos.kt` — a projects lookup that includes closed projects; no
      schema change
- [ ] `feature/projects/domain/port/ProjectsRepository.kt` — the lookup is exposed
- [ ] `feature/agenda/presentation/viewmodel/AgendaDeps.kt` — the projects repository
- [ ] `feature/agenda/presentation/viewmodel/AgendaViewModel.kt` — the projects are
      observed alongside the tasks, in the existing `combine` chain
- [ ] `feature/agenda/domain/model/AgendaUiState.kt` — the row item carries the
      resolved project
- [ ] `feature/tasks/presentation/model/TaskUi.kt` — `projectColor` is added
- [ ] `feature/tasks/presentation/components/list/TaskRowContent.kt` — the
      "Без проекта" placeholder leaves the row
- [ ] `feature/tasks/presentation/components/list/TaskMetaRow.kt` — the project badge is
      drawn, with the project's colour, truncated with an ellipsis
- [ ] Desktop Compose UI test: the badge shows the name and the colour; a task without a
      project has no badge; a task in a **closed** project still has one; the row never
      prints "Без проекта"

## Phase 6 — The calendar and the sync

- [ ] The export keeps mapping every row to its own event — a recurring series appears
      as one event per open occurrence
- [ ] The task's own rule is still never exported as a calendar rule
- [ ] A test that the number of exported events for a recurring series equals the
      number of its open occurrences

## Phase 7 — The gates

- [ ] `./gw :shared:detekt`
- [ ] `./gw :shared:jvmTest`
- [ ] `./gw :desktopApp:test`
- [ ] `singularity-adr validate`
- [ ] `openspec validate --changes`
- [ ] `scripts/find-unwired-surfaces.py` — the new use cases are called by something
