# Phase 0 — Research: Repeat as a materialised series, and the row badges

Feature: `specs/015-recurring-series-badges/spec.md`
Date: 2026-10-25

This file records the decisions the technical context could not settle by itself, the
evidence behind each, and the alternatives that were evaluated and rejected. Every
decision here is traceable to code that was read; the file paths are given so a reviewer
can check the claim rather than trust it.

---

## D1 — A recurring task is a series whose members carry the rule

**Decision.** A repeating task is a **series**: several task rows sharing one
`TaskSeriesId`, every row carrying the same `RecurrenceSpec`, and the rows' dates being
exactly the rule's own occurrences. A series is therefore of one of two kinds, and the
rule distinguishes them:

| Kind | Dates | Rule on the rows |
|---|---|---|
| **Multi-date work** — "pay the rent on the 1st, 5th and 10th" | dates the user picked | none |
| **Recurring series** — "every Monday" | the rule's occurrences | the rule, on every row |

The two are never mixed: a series either has picked dates and no rule, or the rule's dates
and the rule on every row. The existing `TaskSeriesPlanner` keeps producing the first
kind; a new planner produces the second.

**Rationale.** `TaskSharedField.Recurrence` is already a *shared* field
(`feature/tasks/domain/model/TaskSeriesPolicy.kt:27-37`), so every member of a series is
already required to agree on the rule — the invariant that a recurring series needs
already exists, and the materialised rows satisfy it by construction because they are
generated from the rule. `TaskSeriesInvariants.alignedIfOneSeries`
(`feature/tasks/domain/logic/TaskSeriesInvariants.kt:169-172`) copies the shared fields
of the first row onto every row, which for a recurring series copies the *same* rule onto
every row — the agreement is enforced, not merely hoped for.

The repeated-badge requirement (FR-022, FR-024) also forces this: the badge must be
derivable from each row's own data, so each row must carry the rule.

**Alternatives considered.**

- *A rule on one "head" row of the series, plain rows elsewhere.* Rejected: it breaks
  `TaskSharedField.Recurrence` being shared, `realignSeries` would collapse the rows onto
  the head's value on the next unrelated write
  (`feature/tasks/data/TaskRepositoryImpl.kt:348-424`), and the badge could not be derived
  per row.
- *A separate occurrences table.* Rejected by ADR
  `2026-10-22-task-series-materialized-copies`: every read path in this codebase reads
  `Task.title` and `Task.dueDate` directly, and an occurrences table would put a JOIN in
  the agenda, the calendar, the search index, the backup export, the Google Calendar push,
  the MCP tools and the AI tools.
- *One row that keeps rolling forward (today's behaviour).* Rejected by the user: this is
  the defect being fixed.

**Consequence — an accepted ADR has to be superseded.** ADR
`2026-10-22-task-series-materialized-copies` states, in its Consequences: *"A task
created with a repeat rule and then edited into a series is not a thing: the planner
drops the rule, so a series never holds a rule."* D1 contradicts that clause. Per
constitution principle IV the ADR is not edited; a new ADR supersedes it and refines the
clause into the two-kind rule above. See ADR
`2026-10-25-a-recurring-series-carries-the-rule-on-every-row.md`.

---

## D2 — The window is anchored at the current occurrence, not at today

**Decision.** The series holds a **window** of future occurrences, computed by a pure
function of *(the rule, the current occurrence's date)*:

```
anchor      = the earliest open occurrence's date
             (or, when the series has no open occurrence, the latest occurrence's date)
horizon     = anchor + 14 days
within      = the rule's dates in (anchor, horizon], nearest first, capped at 10
beyond      = the rule's first date after the horizon, if the cap leaves room
window      = within + beyond
```

The window is therefore a function of the rule and the series' position — **not of the
current date**. It changes only when an occurrence is consumed (closed) or the rule
changes.

**Rationale.** The spec's original wording (FR-007) put the horizon "14 days from
today". Anchoring at *today* makes the window's contents a function of the calendar day,
which means the set of rows changes while the user does nothing, and something has to
notice the day change and write. That "something" would be either a daily background job
— which may not have run before the user opens the app, so the first screen of the day
would show a stale window — or a write on the read path, which makes a read mutate the
database.

Anchoring at the current occurrence removes the day entirely: the window is stable
between user actions, the series converges, and **no scheduler is involved**. The
consequence is that the window is measured from where the series *is*, not from where
the calendar *is* — a series the user has not touched for a month still shows the same
three occurrences it showed a month ago, which is the correct reading of "the schedule
has not advanced because you have not done the work".

**Alternatives considered.**

- *Horizon from today + a daily `BackgroundJob`.* Rejected: the job is armed at a
  wall-clock minute (`core/work/BackgroundWorkBootstrapper.kt:39-43` arms the prune job
  at 04:20) and on a suspended desktop or a phone in Doze it may not run before the user
  opens the app. The first screen of the day would show a stale window — the exact class
  of defect this feature exists to remove. It also adds a scheduler dependency and a
  failure mode for a rule that is perfectly well computable.
- *A fixed count of the next N dates with no horizon.* Rejected: a yearly rule would
  materialise rows ten years ahead, which the user can never browse in the agenda and
  which the calendar would export as ten events.
- *No window at all — the rule's dates are computed on read.* Rejected: the user chose
  materialisation; a computed projection would not be a task the user can open, complete
  or edit.

**Amendment to the spec.** FR-007 is re-worded from "14 days from today" to "14 days
from the current occurrence", and FR-006 is re-worded to name the anchor. Both changes
are recorded in the spec's change log.

---

## D3 — The closed occurrence stays in the series; the series is pruned at the ceiling

**Decision.** Closing an occurrence leaves that occurrence **in the series, on its own
date, closed** (FR-004). The series therefore grows by one row per close. When the
series reaches the existing ceiling of thirty rows, the **oldest closed occurrence is
detached** from the series — its `series_id` is cleared — and kept as an ordinary
completed task. No user data is destroyed.

**Rationale.** The ceiling already exists and is a refusal, not a hint
(`TaskSeriesPlanner.MAX_DATES = 30`, `feature/tasks/domain/logic/TaskSeriesPlanner.kt:44`).
For a recurring series the ceiling cannot be a refusal: a daily task would stop
recurring after thirty completions, which is a broken recurrence, not a policy. So the
ceiling has to become a *rotation* for the recurring kind: the oldest closed occurrence
leaves the series but stays a task.

The mechanism already exists: `DetachTaskFromSeriesUseCase` does exactly
`repo.update(task.copy(seriesId = null))`
(`feature/tasks/domain/usecase/DetachTaskFromSeries.kt:47-53`), and the whole-row
`@Upsert` path is the only way `series_id` is written at all.

**The rotated occurrence loses the rule with the series id.** The rotation and the
user's own detach are the same mechanism, so they are the same rule (FR-049): a row
that leaves a recurring series carries no rule. It also has to be so — a closed row
that keeps its rule and loses its series id is *exactly* the shape the launch repair
adopts into a new series (D5, D13). Keeping the rule would make the ceiling a loop:
the repair would take the rotated row and grow a new series around it at the next
launch.

**Alternatives considered.**

- *Detach the closed occurrence immediately.* Rejected: the series card would lose the
  run's history the moment the user completes one, and "reopen" would have to decide
  whether the reopened row rejoins the series — an ambiguity the current code does not
  have to answer.
- *Refuse to grow past thirty.* Rejected: it breaks the recurrence (see above).
- *Grow without bound.* Rejected: unbounded rows in the user's database, and the sync
  and backup paths would carry them.

---

## D4 — The three recurrence bases become uniform

**Decision.** Every base now does the same two things: it **closes the occurrence** and
it **materialises the next occurrence as a new row**. The bases differ only in *which
date* the next occurrence lands on:

| Base | Anchor for the next date |
|---|---|
| `FROM_DUE` | the occurrence's due date |
| `FROM_COMPLETION` | the completion date |
| `CATCH_UP` | the occurrence's due date, with the missed occurrences materialised as closed rows first |

**Rationale.** Today's code has three different shapes for the same action
(`feature/tasks/domain/usecase/CompleteRecurringTaskUseCase.kt:169-211`): `FROM_DUE` and
`CATCH_UP` close the row *and* move its date; `FROM_COMPLETION` reopens the row *and*
moves its date. The difference is an artefact of the one-row model — "rolling forward"
was the only way to have a next occurrence. With materialisation the special case
disappears: the next occurrence is a different row, so the current one is simply closed,
whatever the base.

**Consequence.** The `moveDueDate` call leaves the close path entirely, and so does the
defect it carried: `setDueDateForUser` writes `due_date` and `updated_at` and never
touches `completed_at` (`core/database/Daos.kt:267-271`), so the old roll-forward left a
row that was **closed and dated in the future**. That row is what the user saw in
Upcoming as a struck-through task, and why no future occurrence ever appeared as open.

**Amendment to the spec.** FR-036 is re-worded from "a row carrying a rule and a closed
state *with a future date* is reopened" to "a row carrying a rule and a closed state is
reopened **only when the repair finds no open occurrence to serve as the current one**"
— a closed row with a past date is legitimate history and must not be reopened. The
distinction matters: reopening every closed recurring task would resurrect work the user
finished.

---

## D5 — The legacy repair is a startup pass, not a schema change

**Decision.** The repair runs in Kotlin at application launch, next to the bootstrappers
that already run there, and it is idempotent.

**Rationale.** The storage already holds everything the repair needs: `tasks.series_id`
was added by the hand-written migration 45 → 46, and `tasks.recurrence_rule` has been
there all along (`core/database/Mappers.kt:142-145` decodes it). **No schema change is
required**, so no Room migration and no exported-schema bump is involved.

A Room migration cannot do the work: the repair has to compute the rule's occurrence
dates, and that arithmetic lives in `RecurrenceCalculator`, not in SQL. The repository's
own precedent for this is `Migration31To32`, which is registered as a manual migration
precisely because `AutoMigrationSpec.onPostMigrate` cannot express data movement
(`core/database/AppDatabaseFactory.kt:43-46`).

Both entry points already run startup work in the same place: `desktopApp/src/main/kotlin/com/singularity/todo/main.kt:121-126` and
`androidApp/src/main/kotlin/com/singularity/todo/SingularityApp.kt:174-180` both launch
`ProfileBootstrapper` and `BackgroundWorkBootstrapper`. The repair joins them there, so
the policy is written once and both platforms inherit it.

**Alternatives considered.**

- *A Room migration.* Rejected: the recurrence arithmetic is not SQL, and the migration
  discipline in this repository forbids a schema rewrite of `tasks` for this reason
  (`Migration45To46` exists because dropping the table fires the foreign key that points
  the notes at a task).
- *Repair on read.* Rejected: a read that writes is a write path nobody can find, and it
  makes the agenda's read flow non-idempotent.
- *Leave legacy tasks alone and repair them when the user touches one.* Rejected: the
  user's complaint is about tasks they already have; a repair that waits for an edit does
  not answer it.

---

## D6 — The project badge's data is resolved once per list build

**Decision.** The agenda observes the projects repository alongside the tasks and
resolves each project row's **name and colour once**, then carries them on the row
item. The row itself never looks a project up.

**Rationale.** The colour has to travel five hops, none of which exist today:

1. `TaskUi` carries `project: String?` — a name and nothing else
   (`feature/tasks/presentation/model/TaskUi.kt:47`). It needs a colour field.
2. `AgendaRowItem` carries `task`, `badge`, `isBlocked`, `seriesDates` and no project
   (`feature/agenda/domain/model/AgendaUiState.kt:88-95`).
3. `AgendaViewModel` observes only `taskRepo.observeByFilter(TaskFilter.All)` and does
   not `combine` anything (`feature/agenda/presentation/viewmodel/AgendaViewModel.kt:193-207`).
4. `AgendaDeps` has no projects repository
   (`feature/agenda/presentation/viewmodel/AgendaDeps.kt:27-55`), although
   `ProjectsRepository` is bound in the same Koin graph
   (`core/di/ProjectsDiModule.kt:27`).
5. `AgendaTaskRow` builds the `TaskUi` by hand with `project = null` and
   `isRecurring = false` (`feature/agenda/presentation/screen/AgendaContent.kt:404,408`).

The precedent for the combine exists in the calendar feature, which combines a
reminder-derived set with the tasks
(`feature/calendar/presentation/viewmodel/CalendarViewModel.kt:96-113`).

The colour itself is an ARGB `Int` (`feature/projects/domain/model/Project.kt:30-61`) and
the Compose conversion precedent is `Color(project.color)`
(`feature/projects/presentation/components/ProjectCard.kt:59`).

**A hole worth closing.** `ProjectsRepository.observeAll()` reads
`WHERE user_id = :userId AND is_deleted = 0 AND closed_at IS NULL`
(`core/database/Daos.kt:843-847`), so a task whose project was closed or soft-deleted
resolves to no name and no colour — the badge would silently vanish. The plan adds a
lookup that includes closed projects, so the badge keeps naming the project. A query on
an existing table is not a schema change and needs no migration.

**Alternatives considered.**

- *Resolve the project inside the composable* (`koinInject<ProjectsRepository>()`,
  as `ProjectPickerSheet.kt:42-43` does). Rejected: a repository lookup per row, and it
  makes the row unpreviewable and untestable.
- *Keep the project name as text and add only an icon.* Rejected: the request was for a
  badge, and the project's colour is the part that makes projects distinguishable at a
  glance — FR-028.

---

## D7 — The repeat badge is derived, and the meta-row gates are opened

**Decision.** The repeat badge is derived from `task.recurrence != null` at the point
where the row is built, exactly as `AgendaBadgePolicy` already derives
`AgendaBadge.Recurring` (`feature/agenda/domain/logic/AgendaBadgePolicy.kt:52-60`). No
new flag is introduced.

**Rationale.** The icon already exists and is already drawn —
`Icons.Default.Repeat` with the content description "Повторяющаяся задача"
(`feature/tasks/presentation/components/list/TaskMetaRow.kt:65-73`). It has simply never
been reachable, because the row is built with `isRecurring = false` hardcoded
(`AgendaContent.kt:408`).

Two gates also have to open, or the badge still will not show on a row that has nothing
else to say: `hasMeta` in `TaskRowContent.kt:81` and `hasSomethingToShow` in
`TaskMetaRow.kt:48` both omit `isRecurring`. A recurring task with no due date, no
project, not blocked and not in a series currently draws no meta row at all.

**A second, adjacent defect.** `TaskRowContent.kt:86` renders
`task.project ?: "Без проекта"` — and because the agenda passes `project = null`, every
agenda row that has a due date currently shows the literal text "Без проекта" next to
it. FR-030 (the badge replaces the plain text) removes that string from the row.

---

## D8 — The calendar export keeps its one-row-one-event rule

**Decision.** The calendar export is **not** special-cased for recurring series. Every
open occurrence is exported as its own event, exactly as every row of a multi-date
series is exported today.

**Rationale.** The original FR-042 ("the calendar export must not produce more than one
event per series") was written before the existing series behaviour was checked, and it
contradicts it: ADR `2026-10-22-task-series-materialized-copies` states that a series is
visible "in the calendar as three events", and both export paths do map every row to its
own event — `CalendarSyncWorker.kt:70-88` and `GoogleTaskApplier.kt:215-218` — with no
grouping by series id anywhere in `feature/calendarSync`.

Honouring FR-042 would have meant inventing a grouping rule that no other surface has,
and would have made the calendar disagree with the agenda: the agenda would show three
occurrences and the calendar one. The consistent rule is the one the codebase already
has.

The task's own rule is not exported as an RRULE either way: the calendar's recurrence
comes from a *reminder's* pattern (`CalendarEventMapper.kt:69-74`), and the Google path
sets `recurrenceRule = null` deliberately — "Never from the task"
(`GoogleTaskApplier.kt:243-246`).

**Amendment to the spec.** FR-042 and SC-010 are re-worded from "one event per series"
to "one event per open occurrence, no special-casing". The scope of the change is
recorded so the reviewer can see it.

---

## D9 — The tests that encode the old behaviour

`CompleteRecurringTaskUseCaseTest` asserts the defect directly:

- `a confirmed recurring close goes through` (`:89`) asserts the **same row** is closed
  (`:98`) *and* rolled forward (`:99`).
- `a recurring close with no prerequisite rolls forward` (`:103`) asserts the rolled-forward
  row stays closed (`:109`).

Both expectations are superseded by FR-004 and D4 and must be rewritten to assert the
new shape: the occurrence closed on its own date, and a **new** row for the next date.

The UI side has no coverage at all: no test exercises `TaskMetaRow`, `TaskRowContent`,
`TaskRowFlat` or `AgendaTaskRow` in any source set, and the recurring-badge UI test in
`desktopApp/src/jvmTest/.../AgendaBadgePolicyFlowTest.kt:61-64` is commented out. The
badge work therefore needs **new** desktop Compose tests, not updated ones.

---

## D10 — The anchor is the date the rule is applied from, and the base decides which

**Decision.** The window function takes the rule and an **anchor**, and the caller
supplies the anchor. The base decides what the anchor is:

| Base | The anchor |
|---|---|
| `FROM_DUE` | the occurrence's due date |
| `FROM_COMPLETION` | the occurrence's **completion** date — or its due date while it is still open, which is the best estimate available |
| `CATCH_UP` | the occurrence's **due date** — the schedule, not the close |

**Rationale.** `RecurrenceSpec` has three bases, and two of them measure the next
occurrence from when the user *acted*, not from when the task was due
(`RecurrenceSpec.kt:25-28`). `RecurrenceCalculator.nextOccurrence(spec, anchor)` takes
the anchor as a parameter precisely for this reason. A window function that hard-coded
"the occurrence's date" would silently compute the wrong dates for the default base —
and `FROM_COMPLETION` **is** the default: the picker builds it that way
(`RecurrencePickerSheet.kt:105`), and the formatter calls it "the most common mode"
(`RecurrenceFormatters.kt:42`).

`CATCH_UP` is the exception, and it is anchored on the **due date**. That is what makes
it catch-up: the gaps it fills are the occurrences that were *scheduled* and missed,
and they are dated from the schedule. The current implementation anchors it there
(`CompleteRecurringTaskUseCase.kt:97`) and this design keeps that behaviour.

The rule's own KDoc calls `CATCH_UP` "like FROM_COMPLETION"
(`RecurrenceSpec.kt:27-28`), which is looser than the implementation — it is a comment
that reads as a promise about the anchor, and the next reader would take it for one.
Correcting that comment is in the OpenSpec task list; the behaviour does not change.

**Consequence — the plan can shift, and only for one base.** Under `FROM_DUE` the next
occurrence is due on the date the rule gives, whether the user closed it on time or
late, so the plan never moves. Under `FROM_COMPLETION` the anchor is the completion
date, so closing late moves the plan by exactly how late the close was — which is the
base's meaning, not a defect: "every week from when I did it".

The shift is applied as a **date update on the rows that survive the re-plan**, not as
a delete and a recreate. A row keeps its identity and its per-occurrence state — a
reminder, a checklist — and only its date moves. A row is removed only when the new
plan has no place for it, which happens when the rule changes (the user said something
new) or when the cap trims the tail.

**Alternatives considered.**

- *Hard-code the occurrence's date as the anchor.* Rejected: it computes the wrong
  dates for the default base.
- *Re-plan from `today`.* Rejected: that reintroduces the day into the rule and the
  scheduler with it (research D2).
- *Delete and recreate the window on every close.* Rejected: it loses the per-occurrence
  state of every future row on every close, for no gain — a shift is a shift, whether
  the row is rebuilt or moved.

---

## D11 — The create screen's rule never reached the row

**Decision.** The create path becomes a trigger of the materialisation, alongside the
edit path. A draft with a rule and no extra dates materialises a series; a draft with a
rule **and** extra dates drops the rule, which is what the create screen already tells
the user.

**Rationale — this is a third defect, found while tracing the trigger.** The create
path is `TaskCreateViewModel` → `CreateTaskFromDraftUseCase` → `CreateTaskFromDraft` →
`TaskSeriesPlanner.plan` (`CreateTaskFromDraft.kt:126`). The planner sets
`recurrence = null` on every row it writes (`TaskSeriesPlanner.kt:102`) — including
the single row of a one-date draft. So a task created with "repeat every week" came out
with **no rule at all**, silently: the draft carried it
(`TaskCreateViewModel.kt:165`), and the planner dropped it on the way to the row.

That drop is right for a *multi-date* series — the planner's own reason is that a row
with a rule would generate occurrences nobody asked for — and wrong for a single dated
row, where there is no series and no occurrence to generate. The edit path does not go
through the planner: `TaskEntitySlot.SetRecurrence` copies the rule onto the row
(`TaskEntitySlot.kt:127`), which is why a repeating task set from the task screen
repeats at all, and one created from the create screen does not.

**The rule-versus-picked-dates conflict has one answer: the rule loses** (FR-048).
The create screen already says so — `TaskCreateViewModel.kt:231-234` drops the
recurrence when extra dates exist and shows `RECURRENCE_DROPPED_MESSAGE`. The design
keeps that behaviour rather than inventing a second answer.

**Alternatives considered.**

- *Keep the rule on a multi-date series.* Rejected: it contradicts the ADR this change
  supersedes and produces occurrences nobody asked for.
- *Keep the rule on a one-date draft and let the materialisation handle it.* Chosen —
  that is the decision this section records.

---

## D12 — The materialisation is two writes in one transaction

**Decision.** A materialisation that creates members **and** detaches one is written as
two calls inside one transaction:

```kotlin
unitOfWork.write {
    repo.createSeries(newRows)     // the members the plan created
    repo.update(detachedRow)       // the row that leaves the series at the ceiling
}
```

**Rationale.** The repository's API does not offer one call that does both, and the
reason is written down in the code:

- `createSeries` upserts, so it can create and update — but it **refuses** a batch
  carrying two series ids (`TaskRepositoryImpl.kt:253`), and a detached row
  (`seriesId = null`) beside a member (`seriesId = <id>`) is exactly such a batch.
- `updateAll` updates only and refuses to invent a member
  (`TaskRepositoryImpl.kt:292-293`).

`UnitOfWork.write` nests — a nested call joins the outer transaction rather than opening
its own (`core/database/UnitOfWork.kt:40-42`) — so the two writes commit together.

**The order does not matter, and the reason is worth stating because it was first
stated wrongly.** `repo.update` does trigger `realignSeries` (`TaskRepositoryImpl.kt:230`),
but the row it is handed is the *detached* one, whose `seriesId` is null — and
`realignSeries` returns at once when the row carries no series id
(`TaskRepositoryImpl.kt:349`). It never re-reads the members here. The constraint that
decides the shape is the batch refusal above, not an ordering.

**What this rules out.** A single call that both creates and detaches would be tidier,
and the refusal in `createSeries` is a good rule; the shape above respects it instead of
loosening it.

---

## D13 — The repair finds its rows with a query of its own

**Decision.** The repair reads its candidates through a new query — "tasks of this
profile that carry a rule and belong to no series" — rather than reading every active
task and filtering in memory.

**Rationale.** The condition the repair needs is `recurrence_rule IS NOT NULL AND
series_id IS NULL`, and **no such query exists**: `Daos.kt` holds only the two
`series_id` queries, both `SELECT` by series id. The repair could read
`observeAll()` and filter in memory (`TaskRepositoryImpl.kt:70-72`), which would be
correct but would pull every active task of the profile — including the completed ones,
because `watchActive` filters `archived_at IS NULL` and not the completion state — into
memory on every launch, to find the handful that repeat.

A query is cheap here: `tasks.recurrence_rule` is indexed (`Entities.kt:29`, `:53`), and
the query is a new statement on an existing table — not a schema change, so no
migration and no exported-schema bump.

**The repair is per-profile.** It runs at launch against the **active** profile, because
`observeAll()` is scoped to `currentUser.scopedUserId`, which comes from the active
profile id (`ProfileAwareCurrentUser.kt:84`). A second profile's legacy repeating tasks
are therefore repaired at the next launch with that profile active, not before. The
requirement says so (FR-034) rather than claiming a repair that does not happen.

**The DAO can reopen a row.** The repair needs to clear a closed state, and the write
already exists: `UPDATE tasks SET completed_at = NULL` (`Daos.kt:222-223`), reached
through `TaskRepositoryImpl.kt:703-711`.

---

## D14 — A series has two kinds, and the upstream capability says which

**Decision.** A series is of exactly one kind, and the kind is decided by **where its
dates come from**:

| Kind | The dates are | The rule on the rows |
|---|---|---|
| Picked-dates series | the user picked them | none |
| Recurring series | the rule's occurrences | the rule, on every row |

The two never mix. The rule loses against picked dates (FR-048), and the picked dates
lose against the rule.

**Rationale.** The rebased base brought `openspec/specs/task-series/spec.md`, the
capability that owns the model of the series. Three of its requirements contradict this
design literally:

- **REQ-TS-001** — "a single date is an ordinary task and must stay one";
- **REQ-TS-005** — "the system shall refuse a series of more than thirty dates";
- **REQ-TS-007** — "a repeat rule and a set of dates shall not be in force on the same
  task".

The contradiction is not in the design — it is in the **kind** the upstream capability
describes. It describes a picked-dates series, where all three statements are right. A
recurring series is the other kind: its dates *are* the rule's occurrences, so the rule
is not in addition to the dates, it is what they are.

So the two kinds are separated, and each upstream requirement is restated to hold for
the kind it was written about:

- REQ-TS-001 keeps "a single **picked** date stays one" and states the exception: a
  single date **with a rule** is the current occurrence of a recurring series.
- REQ-TS-005 keeps the refusal for picked dates — thirty is what the user asked for —
  and states that a recurring series **rotates** at the ceiling instead of refusing.
- REQ-TS-007 keeps "the rule loses against **picked** dates" and states that a
  recurring series is not in conflict: its dates are the rule's own.

**Alternatives considered.**

- *Change the upstream capability instead of a delta.* Rejected: an accepted capability
  is merged, not rewritten by a sibling change; the delta is the mechanism the corpus
  provides, and it leaves the archive with one coherent text.
- *Make the recurring series a separate capability.* Rejected: a series is a series. The
  kinds differ in where the dates come from, not in what a series is, and a second
  capability would put the shared-field rules (REQ-TS-002, REQ-TS-003) in two places.
- *Leave the conflict to the archive.* Rejected: the archive refuses a delta that drops
  a scenario and merges a modified requirement whole; the conflict has to be settled in
  the delta, and a delta that quietly contradicts the capability it modifies is a change
  nobody can review.

**The join of two series is bounded by the kinds.** The app can join two series into one
(`feature/tasks/domain/usecase/MergeTaskSeries.kt:40-56`), and the join takes the
target's id and its shared fields onto the other series' rows. Under the two-kind rule
that is a way to mix the kinds: joining a recurring series with a series of picked dates
would produce a series whose members carry a rule and a date the user picked by hand,
and joining two recurring series with different rules would produce members that
disagree about the work — which is the one thing a series may not do. So the join
**refuses** a mixed pair, and it refuses two recurring series whose rules differ; the
refusal names the reason (FR-050). A join of two series of the same kind — two
picked-dates series, or two recurring series carrying the same rule — is unchanged, and
the dates of both series survive it.

---

## D15 — The occurrence that leaves a recurring series loses the rule

**Decision.** When an occurrence is taken out of its series, it loses the series id —
and if the series is a **recurring** one, it loses the **rule** with it (FR-049). The
remaining members keep the rule, and the series continues from the members that remain.

**Rationale.** The operation exists today: `DetachTaskFromSeriesUseCase` clears the
series id and keeps the occurrence's own date, time, completion state, checklist and
attachments (`feature/tasks/domain/usecase/DetachTaskFromSeries.kt:47-53`). Under
materialisation it acquires a second meaning, and the old behaviour would leave a row
that carries the rule — and therefore carries the repeat badge — while nothing will ever
repeat it. A badge that promises a repetition that never comes is worse than no badge.

The rule belongs to the series, not to the occurrence: the other members keep it, so
taking one date out does not end the recurrence.

**Alternatives considered.**

- *Keep the rule on the detached row and let it repeat alone.* Rejected: a rule with no
  series behind it has no window and no position; the row would say "repeats" and stay
  put.
- *End the whole series when one occurrence leaves.* Rejected: the operation is "this
  date is not this job" — one date, not the schedule.
- *Hide the badge on a detached occurrence.* Rejected: the badge is derived from the
  rule, and a row that keeps a rule it will never honour should not carry it.

**The row that stays behind when the series ends loses the rule too.** The detach path
has a second write: when the occurrence that leaves was the second of two, the one that
stays also stands alone
(`feature/tasks/domain/usecase/DetachTaskFromSeries.kt:52-54`). That write clears the
series id and keeps the rule, and a rule on a row that stands alone is the same shape the
repair adopts into a new series — so the detach would undo itself at the next launch. The
row that stays behind loses the rule with the series id, exactly like the row that left.

**The claim on the wire follows the row.** The sync format does not change: the detach
writes the row's own fields, so the document carries `seriesId: null` together with
`recurrence: null`. The sync capability reads a null value as the claim it is — "the
task stands alone, and it has no recurrence" — so the two claims travel together, and
the device on the other side clears both. A row that kept its rule would arrive on the
other device still carrying it, and that device would show a repeat badge for a task
that never repeats.

---

## Open questions

None. Every item the technical context raised was settled above, and the two decisions
the user was asked for (materialise rather than roll forward; badges in the list row
only) were taken before the specification was written.

One question is deferred rather than open: whether the recurring series should ever be
exported to the calendar as a single event with an RRULE. That is a calendar-policy
decision, it is explicitly out of scope in the specification, and it is recorded in the
ADR's Consequences so it is not lost.
