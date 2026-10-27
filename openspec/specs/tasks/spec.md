# tasks Specification

## Purpose
Manage the task lifecycle: create, edit, complete, delete, archive, and track
time against individual tasks. The agenda organises tasks by date bucket; the
editor exposes attributes (priority, due date, start date, project, tags,
recurrence, pin) as rows.

## Scenario Matrix

<!-- MACHINE-READ. `python -m traceability scenarios --check` сверяет этот
     блок с `scripts/src/traceability/scenarios/**`: объявление без YAML, расхождение по
     title/priority/status/targets и незаполненный TODO — ошибки.
     Поведение (steps/expected) здесь объявлять нельзя — оно принадлежит
     YAML-сценарию. -->

```yaml
scenarios:
- id: TASK-01
  title: Create a task from the FAB and see it in the Inbox
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-02
  title: Tapping the checkbox toggles the task's completion state
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-03
  title: Tapping a task row opens its detail screen with the correct title
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-04
  title: Delete a task from its detail screen overflow menu
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-05
  title: Deleting a task shows an Undo snackbar and Undo restores it
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-06
  title: Long-press opens context menu; archive removes the task
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-07
  title: Set task priority from the editor attribute row
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-08
  title: Set task due date from the editor attribute row
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-09
  title: Cancelling the date picker leaves the task undated
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-10
  title: Empty title is rejected; original title remains intact
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-11
  title: Cyrillic task title is created, saved, and displayed correctly
  area: feature.tasks
  priority: P2
  status: confirmed
  targets:
  - android
- id: TASK-12
  title: Create a task with all attribute fields filled
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-13
  title: Tasks appear in the correct agenda date-bucket sections
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-14
  title: FAB from Today tab pre-fills the due date to Today
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-15
  title: FAB from Inbox tab does NOT pre-fill a due date
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-16
  title: Creating from a tab returns to that tab after save
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
- id: TASK-17
  title: Start and stop a time-tracking session on a task
  area: feature.tasks
  priority: P2
  status: confirmed
  targets:
  - android
- id: TASK-CHECK-01
  title: Add a checklist item to a task
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
  group: checklist
- id: TASK-REC-01
  title: Create a daily recurring task
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
  - desktop
  group: recurrence
- id: TASK-SUB-01
  title: Subtask lifecycle — add, toggle, delete
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
  group: subtasks
- id: TASK-TIME-01
  title: Start a timer on a task from its detail view
  area: feature.tasks
  priority: P1
  status: confirmed
  targets:
  - android
  - desktop
  group: timetracking
```

## Requirements

The scenarios above are the source of truth for behaviour: their steps and
expectations live in `scripts/src/traceability/scenarios/**`, and this section
only states the requirement each group of them belongs to. The ids are repeated
here so a reader arriving from a requirement can find the scenario without
reading the matrix.

### Requirement: A task can be created and reached

The system SHALL create a task from the FAB and SHALL place the new task in the
Inbox when no due date was given.

#### Scenario: Create a task from the FAB and see it in the Inbox
- **WHEN** the user creates a task from the FAB without choosing a due date
- **THEN** the task appears in the Inbox

#### Scenario: Create a task with all attribute fields filled
- **WHEN** the user fills every attribute field and saves
- **THEN** the task is created with those attributes

#### Scenario: Create a daily recurring task
- **WHEN** the user creates a task with a daily recurrence
- **THEN** the task repeats daily

#### Scenario: Cyrillic task title is created, saved, and displayed correctly
- **WHEN** the user enters a non-Latin title
- **THEN** it is stored and rendered unchanged

### Requirement: A task opens, and its title is protected

The system SHALL open a task's detail screen when its row is tapped, and SHALL
reject an empty title without modifying the existing one.

#### Scenario: Tapping a task row opens its detail screen with the correct title
- **WHEN** the user taps a task row
- **THEN** the detail screen opens showing that task's title

#### Scenario: Empty title is rejected; original title remains intact
- **WHEN** the user clears the title and tries to save
- **THEN** the save is rejected and the title is unchanged

### Requirement: Completion, deletion and archiving each have an undo where the user expects one

The system SHALL toggle completion from the row checkbox, SHALL delete from the
detail screen's overflow menu, SHALL offer Undo after a delete, and SHALL archive
from the long-press context menu.

#### Scenario: Tapping the checkbox toggles the task's completion state
- **WHEN** the user taps a task's checkbox
- **THEN** the task's completion state toggles

#### Scenario: Delete a task from its detail screen overflow menu
- **WHEN** the user deletes a task from its overflow menu
- **THEN** the task is removed

#### Scenario: Deleting a task shows an Undo snackbar and Undo restores it
- **WHEN** the user deletes a task
- **THEN** Undo is offered and restores the task when used

#### Scenario: Long-press opens context menu; archive removes the task
- **WHEN** the user long-presses a task and archives it
- **THEN** the task is archived

### Requirement: Task attributes are editable from the editor

The system SHALL let the user set priority and due date from the editor's

attribute rows, SHALL leave a task undated when the date picker is cancelled, and

SHALL pre-fill a due date only where a tab says it should.

Every attribute row SHALL open the picker it names, in every editor — the one that

creates a task and the one that opens an existing one. A row that opens nothing SHALL

NOT be presented as a row the user can press.

A screen that opens a picker from an editor's row SHALL hand that editor the state the

picker is opened through. An editor SHALL NOT fall back to a state of its own: a row

that opens a picker nobody renders is a row that does nothing, and it does nothing

quietly.

A picker SHALL be given the values of the task being edited, and SHALL write back to

that task. A picker SHALL NOT be given an empty task, and SHALL NOT write back to

nothing.

An editor that is given a checklist or attachments SHALL draw the row that opens them,

and the row SHALL say how many items there are.

A sheet that holds a choice the user has not committed yet SHALL present one way to

commit that choice, and the control that commits it SHALL be addressable. A sheet that

commits its choice the moment it is made SHALL apply it that way, and SHALL NOT also

ask for a confirmation.

#### Scenario: Set task priority from the editor attribute row
- **WHEN** the user picks a priority
- **THEN** the task carries it

#### Scenario: Set task due date from the editor attribute row
- **WHEN** the user picks a due date
- **THEN** the task carries it

#### Scenario: Cancelling the date picker leaves the task undated
- **WHEN** the user cancels the date picker
- **THEN** the task has no due date

#### Scenario: FAB from Today tab pre-fills the due date to Today
- **WHEN** the user creates a task from the Today tab
- **THEN** the due date defaults to today

#### Scenario: FAB from Inbox tab does NOT pre-fill a due date
- **WHEN** the user creates a task from the Inbox tab
- **THEN** no due date is set

#### Scenario: An attribute row in the editor that creates a task
- **WHEN** the user taps an attribute row
- **THEN** the picker it names opens

#### Scenario: A picker in the editor that opens an existing task
- **WHEN** the user opens a picker that needs the task being edited
- **THEN** the picker is given that task, not an empty one

#### Scenario: A screen that opens a picker from an editor's row
- **WHEN** a screen opens a picker from an editor's row
- **THEN** it hands the editor the state the picker is opened through, and the picker
  opens

#### Scenario: A checklist in the editor that creates a task
- **WHEN** the user opens the checklist of a task being created
- **THEN** the items of the draft are there, and an item the user adds shows up in the
  row that opened them

#### Scenario: A row that says how many items there are
- **WHEN** the editor draws a checklist or attachments row
- **THEN** the row says how many items there are

#### Scenario: A recurrence the user chose is committed
- **WHEN** the user chooses a daily recurrence and confirms the sheet
- **THEN** the task carries it, and the editor's recurrence row reports that it repeats

#### Scenario: A sheet that holds an uncommitted choice
- **WHEN** the user opens the recurrence sheet
- **THEN** the sheet shows one header and one control that commits the chosen rule

### Requirement: Agenda placement follows the due date

The system SHALL place each task in the agenda bucket its due date belongs to,
and SHALL return to the originating tab after a task is saved.

#### Scenario: Tasks appear in the correct agenda date-bucket sections
- **WHEN** tasks with different due dates are shown
- **THEN** each appears under its own bucket

#### Scenario: Creating from a tab returns to that tab after save
- **WHEN** the user saves a task created from a tab
- **THEN** that tab is shown again

### Requirement: A task carries its own child work

The system SHALL support checklist items and subtasks on a task, each with its
own lifecycle.

#### Scenario: Add a checklist item to a task
- **WHEN** the user adds a checklist item
- **THEN** it appears on the task

#### Scenario: Subtask lifecycle — add, toggle, delete
- **WHEN** the user adds, toggles and deletes a subtask
- **THEN** each operation is reflected on the task

### Requirement: Time can be tracked against a task

The system SHALL let the user start a time-tracking session from a task and stop
it again.

#### Scenario: Start and stop a time-tracking session on a task
- **WHEN** the user starts and then stops a session
- **THEN** the elapsed time is recorded against the task

#### Scenario: Start a timer on a task from its detail view
- **WHEN** the user starts a timer from the task's detail view
- **THEN** the timer runs for that task

### Requirement: A setting whose heading names a set is modelled as that set

A setting whose heading names a *set* — a working week, a set of working days —
SHALL be modelled as that set, and the section's controls SHALL cover every
member of it. The model SHALL be able to express every value the section
offers, so that no member of the set needs a special case elsewhere in the app.

The screen that offers the set and the screen that consumes it SHALL ask the
same question of the same value, and the decision SHALL live in one pure
function that both the production caller and the tests use.

When a set replaces a previous model, the values a user already chose SHALL
survive the change: the old values SHALL be read the way the *screen* read them,
not the way the old field names suggest, and the first write of the new model
SHALL make the old values inert. A value the user never chose SHALL be
distinguishable from a value the user chose to be empty.

**Rationale:** the `WorkSchedule.weekendSat` and `WorkSchedule.weekendSun`
switches — "Saturday" and "Sunday" under a section headed "Working Days" — were
persisted, rendered, and read by nobody. Two switches named after the weekend
can only say something about the weekend: the section promised a working week
and delivered a weekend. The dead-settings baseline then waived the two fields,
which was honest but left the section promising something it could not express.
The field names read the opposite way round from the switches — `weekendSat =
true` means Saturday *is* a working day — so a migration that followed the
name would move every weekend worker to the wrong week.

#### Scenario: A weekend day that is not a working day

- **Given** the user's working days are Monday to Friday
- **And** today is a Saturday
- **When** the Today agenda is built
- **Then** it shows the date-based sections
- **And** it does not show the work-day blocks

#### Scenario: A weekend day that is a working day

- **Given** the user's working days include Sunday
- **And** today is a Sunday
- **When** the Today agenda is built
- **Then** it shows the work-day blocks

#### Scenario: A weekday

- **Given** today is a Wednesday
- **And** Wednesday is one of the user's working days
- **When** the Today agenda is built
- **Then** the work-day blocks apply

#### Scenario: A working week that is not Monday to Friday

- **Given** the user's working days are Saturday and Sunday only
- **And** today is a Monday
- **When** the Today agenda is built
- **Then** it shows the date-based sections
- **And** the work-day blocks do not apply

#### Scenario: A choice made before the working week was a set

- **Given** a user who turned the "Saturday" switch on before the section held
      a set
- **When** their working days are read
- **Then** Saturday is one of their working days
- **And** Sunday is not

### Requirement: A setting that changes the agenda changes the agenda

A setting the user can change in Settings SHALL affect the screen it describes. A
switch that is rendered, persisted, and read by nothing SHALL be reported as a
defect, and the defect SHALL be fixed by wiring the value or by removing the
control — never by baselining it.

Where a setting selects between two known behaviours, the decision SHALL live in
one pure function that both the production caller and the tests use, so the
behaviour cannot drift from the setting that promises it.

**Rationale:** the `WorkSchedule.weekendSat` and `WorkSchedule.weekendSun`
switches — "Saturday" and "Sunday" under a section headed "Working Days" — were
persisted, rendered, and read by nobody. The work-day agenda (morning work,
lunch, afternoon work) was shown on a Saturday the user had explicitly marked as
*not* a working day. `AgendaPresets.workDayPreset` named the gap itself — "Weekend
handling is the caller's responsibility" — and the caller never took it.

#### Scenario: A weekend day that is not a working day

- **Given** the user has not marked Saturday as a working day
- **And** today is a Saturday
- **When** the Today agenda is built
- **Then** it shows the date-based sections
- **And** it does not show the work-day blocks

#### Scenario: A weekend day that is a working day

- **Given** the user has marked Sunday as a working day
- **And** today is a Sunday
- **When** the Today agenda is built
- **Then** it shows the work-day blocks

#### Scenario: A weekday

- **Given** today is a Wednesday
- **When** the Today agenda is built
- **Then** the work-day blocks apply
- **And** the two working-day switches do not change that

### Requirement: REQ-TASKS-REWRITE-1

The task detail SHALL tell a replacement from a keystroke.

The task detail holds a **bridged** text field — a Compose `TextFieldState`
fed by a draft in the ViewModel — and the draft observes a task the repository
owns. That task can be rewritten from outside: a sync from another device, a
change made on another screen. A replacement and a keystroke arrive the same
way, as a new value, so the draft SHALL carry a revision token alongside the
value, and the token SHALL move only when the content is replaced.

The draft SHALL tell four cases apart:

- **the first seed** initialises the draft;
- **the echo of the draft's own save** — the draft already holds exactly that
  text — SHALL change nothing and SHALL NOT move the revision;
- **a replacement from outside** SHALL be taken, and SHALL move the revision;
- **the user's unsaved edits** SHALL win: a dirty draft SHALL NOT be re-seeded,
  and the replacement SHALL wait for the draft to become clean.

The repository's own echo SHALL mark the draft clean: when the repository holds
exactly what the draft holds, the write landed, and the baseline moves with it,
so that a later replacement is not refused as "the user is typing". A write
that landed while the user kept typing does not match the draft, so the newer
text wins and the draft stays dirty.

The task's title and description fields SHALL pass the draft's revision to the
bridge, so that the bridge's echo guard is lifted for a replacement that lands
on a value the field produced earlier.

#### Scenario: A task rewritten from outside

- **Given** the task detail holds a task and the draft is clean
- **And** the task's title is rewritten by something outside the screen
- **When** the screen observes the new title
- **Then** the title field shows the new title
- **And** the draft's revision has moved

#### Scenario: A rewrite that arrives while the user is typing

- **Given** the task detail holds a task with unsaved edits
- **And** the task's title is rewritten by something outside the screen
- **When** the screen observes the new title
- **Then** the title field still holds the user's edits
- **And** the draft's revision has not moved

#### Scenario: The screen's own save

- **Given** the task detail holds a task and the draft is clean
- **When** the user edits the title and the write is accepted
- **And** the repository echoes the saved task back
- **Then** the draft's revision has not moved
- **And** the title field is unchanged

#### Scenario: A replacement that lands on a value the field produced

- **Given** the user typed a title earlier and the field pushed it
- **And** the draft was saved, so it is clean
- **When** a sync rewrites the task's title to that same value
- **Then** the revision moves, and the field accepts the value

### Requirement: A task is blocked only by an unfinished prerequisite

A task SHALL be reported as blocked when it has a dependency that blocks it,
and that prerequisite is neither finished nor discarded.

A dependency of any other kind — one that says the task follows up on it, for
instance — SHALL NOT make the task blocked. Every place the app reports a task
as blocked SHALL use that one meaning: the badge, the saved views that select
blocked tasks, the health summary, the filter that hides blocked tasks, and the
list of dependencies offered for editing.

**Rationale:** the dependency graph records the *kind* of every relationship, but
the task remembered only *that* it had dependencies. So "blocked" was computed as
"has a dependency", which made a task that merely follows up on another look
exactly like a task that cannot start. The task's own description had said
"must be completed before" since the beginning; this requirement is that
sentence, enforced.

#### Scenario: An unfinished prerequisite blocks the task

- **Given** a task that depends on another task
- **And** that dependency blocks it
- **And** the dependency is neither finished nor discarded
- **When** the task is reported on
- **Then** it is reported as blocked

#### Scenario: A finished prerequisite does not block the task

- **Given** a task that depends on another task
- **And** that dependency blocks it
- **And** the dependency has been finished
- **When** the task is reported on
- **Then** it is not reported as blocked

#### Scenario: A discarded prerequisite does not block the task

- **Given** a task that depends on another task
- **And** that dependency blocks it
- **And** the dependency has been discarded
- **When** the task is reported on
- **Then** it is not reported as blocked

#### Scenario: A dependency of another kind does not block the task

- **Given** a task that depends on another task
- **And** that dependency says the task follows up on it rather than that it blocks it
- **When** the task is reported on
- **Then** it is not reported as blocked

#### Scenario: Every place that reports blocking agrees

- **Given** a task that follows up on another task
- **When** the app reports on the task in the badge, in a saved view that selects
      blocked tasks, in the health summary, in the filter that hides blocked
      tasks, and in the list of dependencies offered for editing
- **Then** none of them reports the task as blocked

### Requirement: Closing a task with an unfinished prerequisite asks first

Closing a task that has an unfinished prerequisite SHALL NOT take effect on the
first request. The system SHALL ask, naming the prerequisites that are still
open, and SHALL close the task only if the user confirms.

The confirmation SHALL be offered at every place the user can close a task, and
the same set of prerequisites SHALL be named at each of them.

Declining the confirmation SHALL leave the task exactly as it was.

Taking a finished task back into work SHALL NOT be asked about.

**Rationale:** the badge that says "this is not ready" and the checkbox next to
it that closed the task anyway were two halves of one feature with only one
half implemented. The user was told and then overridden by the affordance they
were looking at. Asking costs one interaction and preserves the user's
authority over their own data: sometimes the prerequisite really is done and
the graph is out of date.

#### Scenario: A blocked task asks before it closes

- **Given** a task with a prerequisite that is still open
- **When** the user closes the task
- **Then** the task stays open
- **And** the user is asked, and the open prerequisite is named

#### Scenario: Confirming the request closes the task

- **Given** a task with a prerequisite that is still open
- **And** the user has been asked
- **When** the user confirms
- **Then** the task is closed

#### Scenario: Declining the request leaves the task alone

- **Given** a task with a prerequisite that is still open
- **And** the user has been asked
- **When** the user declines
- **Then** the task is still open
- **And** nothing about the task has changed

#### Scenario: An unblocked task closes without a question

- **Given** a task with no open prerequisite
- **When** the user closes the task
- **Then** the task is closed
- **And** the user is not asked

#### Scenario: Every place that closes a task asks

- **Given** a task with a prerequisite that is still open
- **When** the user closes it from the agenda, from the agenda's context menu,
      from the task's own screen, or from the task's list of subtasks
- **Then** the task stays open
- **And** the user is asked, and the open prerequisite is named

#### Scenario: Taking a finished task back into work is not asked about

- **Given** a task that has been closed
- **When** the user takes it back into work
- **Then** it is open again
- **And** the user was not asked

### Requirement: A write that arrives from outside the user's own action is not asked about

A change to a task's finished state that arrives from synchronisation, from
restoring a backup, or from an accepted proposal SHALL be applied without a
confirmation, even when the task has an open prerequisite.

**Rationale:** the confirmation is a question for the person who is looking at
the screen, not a validation of the data. A device that has been offline for a
week and comes back should not stop and ask about tasks the user did not touch
while they were away, and a restored backup that leaves a task closed against an
open prerequisite is a faithful copy of what was backed up.

#### Scenario: A synchronised change is applied without a question

- **Given** a task with a prerequisite that is still open
- **When** the task's finished state changes through synchronisation
- **Then** the change is applied
- **And** the user is not asked

#### Scenario: A restored task is applied without a question

- **Given** a task with a prerequisite that is still open
- **When** the task is restored from a backup as closed
- **Then** it stays closed
- **And** the user is not asked

### Requirement: A completion is refused while a prerequisite is open

Completing a task SHALL be refused while one of the prerequisites that block it is
still open. The refusal SHALL be the write itself: the same operation that completes
the task SHALL decide whether it may, so that no separate check can be out of date when
the write lands.

When a completion is refused, the app SHALL ask the user, naming the prerequisites that
are in the way, and SHALL write nothing until the user confirms. On confirmation the
task SHALL be closed even though the prerequisite is still open.

Taking a finished task back into work SHALL never be refused.

#### Scenario: A close is refused while a prerequisite is open

- **Given** a task with a prerequisite that blocks it
- **And** that prerequisite is still open
- **When** the user asks for the task to be closed
- **Then** the task is not closed
- **And** the user is asked, and the prerequisites in the way are named

#### Scenario: Confirming the refusal closes the task

- **Given** a task with an open prerequisite that blocks it
- **And** the user was asked and confirmed
- **When** the task is closed
- **Then** the task is closed

#### Scenario: Taking a task back into work is never asked about

- **Given** a finished task
- **When** the user takes it back into work
- **Then** it is open again, and no question is asked

### Requirement: A bulk close asks too

Closing several tasks at once SHALL be refused when one of them has an open
prerequisite that blocks it. The app SHALL report which task is in the way and SHALL
close none of the selected tasks.

#### Scenario: A selection with a blocked task closes nothing

- **Given** a selection of tasks
- **And** one of them has an open prerequisite that blocks it
- **When** the user closes the selection
- **Then** none of the selected tasks is closed
- **And** the task in the way is named

#### Scenario: A selection without a blocked task is closed

- **Given** a selection of tasks
- **And** none of them has an open prerequisite that blocks it
- **When** the user closes the selection
- **Then** every selected task is closed

### Requirement: A task that comes back to work brings its project with it

When a task that belongs to a closed project is taken back into work, the project SHALL
be opened as well, so that the task does not belong to a project the user cannot see.

#### Scenario: Reopening a task opens its closed project

- **Given** a closed project
- **And** a task that belongs to it
- **When** the task is taken back into work
- **Then** the project is open again

#### Scenario: A task without a project is unaffected

- **Given** a task that belongs to no project
- **When** it is taken back into work
- **Then** no project changes

### Requirement: A recurring close is refused while a prerequisite is open

Completing one occurrence of a recurring task SHALL be refused while one of the
prerequisites that block it is still open. The refusal SHALL be the write itself, and
it SHALL leave the series unchanged: the occurrence is not closed, the series does not
advance to its next date, and no copy of a missed occurrence is written.

When the close of an occurrence is refused, the app SHALL ask the user, naming the
prerequisites that are in the way, and SHALL write nothing until the user confirms. On
confirmation the occurrence SHALL be closed and the series SHALL advance, even though
the prerequisite is still open.

A series advances by materialising, not by moving: the closed occurrence keeps its own
date, and the next occurrence is a new row of the same series.

**Rationale:** the refusal has to be the write itself. A close that is asked about and
then refused must leave nothing behind — not a closed occurrence, not a moved date, not
a written copy of a missed occurrence — or the user is left with a series they never
agreed to.

The refusal used to say that the series "moves to its next date", which meant that the
closed row changed its date. That is the write that produced a row closed and dated in
the future, and the row that carried it forward is the defect this change is about. The
series still advances; what advances it is a new row, and the closed one stays where it
was closed.

The scenario heading that says the series "moves" is kept **verbatim**, and its body says
what "moves" now means. The heading could not be renamed: the validator compares a
modified requirement against the current one and reports a scenario that is gone, and a
renamed heading is a scenario the current spec does not have.

**Test coverage:** the recurring-close tests assert that a refused close writes
nothing, and that a confirmed close closes the occurrence and materialises the next one
without moving the closed one.

#### Scenario: A recurring close is refused while a prerequisite is open

- **Given** a recurring task with a prerequisite that blocks it
- **And** that prerequisite is still open
- **When** the user asks for the occurrence to be closed
- **Then** the occurrence is not closed
- **And** the series does not advance
- **And** the user is asked, and the prerequisites in the way are named

#### Scenario: Confirming the refusal closes the occurrence and moves the series

- **Given** a recurring task with an open prerequisite that blocks it
- **And** the user was asked and confirmed
- **When** the occurrence is closed
- **Then** the occurrence is closed
- **And** the series advances to its next date: the closed occurrence keeps its own date,
  and the next occurrence is materialised as a new row

#### Scenario: A refused recurring close writes no copy of a missed occurrence

- **Given** a recurring task in catch-up mode with a prerequisite that blocks it
- **And** that prerequisite is still open
- **When** the user asks for the occurrence to be closed
- **Then** no copy of a missed occurrence is written
- **And** the series still holds the occurrence that was asked about

---

### Requirement: A bulk close asks the same question a single close asks

Closing a selection of tasks SHALL be refused when one of the tasks in it has a
prerequisite that is still open. The refusal SHALL close nothing: no task in
the selection is closed, and the selection remains selected.

When a bulk close is refused, the app SHALL ask the user, naming the tasks in
the selection that are in the way, and SHALL write nothing until the user
confirms. On confirmation every task in the selection SHALL be closed, including
the ones whose prerequisite is still open.

#### Scenario: A blocked bulk close asks and writes nothing

- **Given** a selection of tasks, one of which has a prerequisite that is still
  open
- **When** the user asks for the selection to be closed
- **Then** no task in the selection is closed
- **And** the user is asked, and the tasks in the way are named
- **And** the selection is still selected

#### Scenario: Confirming the refusal closes the whole selection

- **Given** a selection of tasks, one of which has an open prerequisite that
  blocks it
- **And** the user was asked and confirmed
- **When** the selection is closed
- **Then** every task in the selection is closed

### Requirement: The rows of a series say one thing about the work

A series SHALL have the same value for every field that describes the work —
the title, the description, the project, the tags, the priority, the accent
color, the emoji, the estimate and the recurrence — on every one of its rows.

The system SHALL repair that agreement when a single row of a series is
written: the row that was written SHALL say what the work is, and the other
rows of the series SHALL be written with its shared fields.

The system SHALL NOT write the rows that already agree.

**Rationale:** "apply to all of them" is a sentence about the series. If the
rows already disagree, the sentence has no subject — and the disagreement can
come from a writer that is not the editor: an MCP tool renames one occurrence,
another device pushes a row with another title, a restore writes rows one by
one. Healing the series at the write is what keeps the sentence meaningful
without asking the user to open the right occurrence first.

#### Scenario: A renamed occurrence realigns the rest of the series

- **GIVEN** a series of three occurrences that share one title
- **WHEN** one occurrence is renamed
- **THEN** the other two occurrences carry the new title

#### Scenario: The rows that already agree are not written again

- **GIVEN** a series whose occurrences agree about the work
- **WHEN** one occurrence is completed
- **THEN** the other occurrences are not written

#### Scenario: The occurrence's own fields are not touched by the repair

- **GIVEN** a series of three occurrences on three dates
- **WHEN** one occurrence is renamed
- **THEN** the dates of the three occurrences are unchanged

#### Scenario: A write that carries no tags does not take the tags off the series

- **GIVEN** a series of three occurrences that carry a tag
- **WHEN** one occurrence is edited through a read that carries no tags
- **THEN** the other occurrences still carry the tag

### Requirement: A series write that is not one series is refused

The system SHALL refuse a batch write of a series whose members carry more than
one series id, and SHALL refuse a batch write of a series whose members carry
no series id at all. A refused write SHALL write nothing.

A batch write of one series SHALL align the batch to one story before writing
it, so a batch that had drifted apart is repaired rather than written as two
stories.

**Rationale:** two series ids in one batch is not a stale row — it is two
stories, and no rule can say which one the user meant. A write path that cannot
repair it must not write it.

#### Scenario: A batch that carries two series ids is refused

- **GIVEN** two series
- **WHEN** a batch write asks for both of them at once
- **THEN** the write is refused and nothing is written

#### Scenario: A batch that carries no series id is refused where a series is meant

- **GIVEN** two tasks that carry no series id
- **WHEN** they are written as a series
- **THEN** the write is refused

#### Scenario: A batch that drifted apart is written as one story

- **GIVEN** two occurrences of one series whose titles differ
- **WHEN** the series is written
- **THEN** both occurrences carry the same title afterwards

### Requirement: A row that arrives from another device realigns the local series to it

When a row that belongs to a series arrives from another device, the system
SHALL write it and SHALL realign the rows of that series that this device owns
to its shared fields, and SHALL push the rows that changed back.

The realignment SHALL be scoped by the user the incoming row belongs to: the
series it can be a member of is that user's series, and this device writes the
rows of the profile it is on. A row of another profile is stored as it is,
because this device does not rewrite the rows of a profile it is not on.

**Rationale:** a remote row that disagrees with the local series is not a
mistake — it is the other device being right about a change this device has not
seen yet. Refusing would break the device forever: the row would come back on
every pull. The last write wins, applied to the whole series, because the
series is the thing the user thinks in.

#### Scenario: An incoming row realigns the local series

- **GIVEN** a series of three occurrences with one title
- **WHEN** a row of that series arrives from another device with another title
- **THEN** the local occurrences carry the title of the incoming row

#### Scenario: The row that arrives is the truth about the work

- **GIVEN** a series whose occurrences disagree about the title
- **WHEN** a row of that series arrives from another device
- **THEN** the occurrences agree about the title afterwards

#### Scenario: Another user's row does not touch this profile's series

- **GIVEN** a row of another user that carries a series id
- **WHEN** the row arrives
- **THEN** the rows of this profile are not written

### Requirement: One date of a series can leave it

The system SHALL let the user take one occurrence of a series out of it. The occurrence
SHALL keep its date, its time, its completion state, its checklist and its attachments,
and SHALL lose the series id.

An occurrence that leaves a **recurring series** SHALL also lose the rule. The
remaining members SHALL keep the rule, and the series SHALL continue from the members
that remain.

When the occurrence that leaves was the last other member of a **recurring** series, the
row that stays behind SHALL lose the rule as well: a row that stands alone and carries a
rule is a shape the launch repair would adopt into a new series, and it must not be
produced.

When the last occurrence leaves, the series SHALL be over: the remaining row SHALL stand
alone.

**Rationale:** a series is a decision the user makes once, and the decision can be wrong
— the second date needs another title, or is not this job at all. Without the operation,
that cost is paid silently on every series that was slightly wrong.

The rule is part of what an occurrence claims about itself: "this happens again, as one of
these dates". An occurrence that left the series while keeping the rule would keep a
promise nothing keeps — it would carry the mark that says it repeats, and it would never
repeat. The other members are untouched by this: their rule belongs to the series, not to
the occurrence that left.

The row that stays behind when the series ends is in the same position. It stands alone,
and a rule on a row that stands alone is a promise nothing keeps either — and a shape the
launch repair would adopt into a new series, which would undo the detach at the next
launch.

The claim the row makes on the wire follows the row: a detached occurrence carries no
rule, so the document that carries it carries `seriesId: null` together with
`recurrence: null`. The sync format does not change; two claims travel together, and the
device on the other side clears both.

**Test coverage:** the detach test asserts that an occurrence that leaves a recurring
series carries no rule, that the remaining members still carry it, and that the row that
stays behind when the series ends carries no rule either.

#### Scenario: An occurrence leaves the series and keeps its own date

- **GIVEN** a series of three occurrences on three dates
- **WHEN** one occurrence leaves the series
- **THEN** that occurrence keeps its date and stands alone

#### Scenario: The series of two becomes a series of one

- **GIVEN** a series of two occurrences
- **WHEN** one occurrence leaves the series
- **THEN** the other occurrence stands alone and carries no series id

#### Scenario: The last occurrence leaves and the series is over

- **GIVEN** a series of one occurrence
- **WHEN** that occurrence leaves the series
- **THEN** it stands alone and carries no series id

#### Scenario: An occurrence that leaves a recurring series stops repeating

- **GIVEN** a recurring series with three occurrences
- **WHEN** one occurrence leaves the series
- **THEN** that occurrence carries no rule
- **AND** the remaining occurrences carry the rule

#### Scenario: The row that stays behind carries no rule

- **GIVEN** a recurring series of two occurrences
- **WHEN** one occurrence leaves the series
- **THEN** the occurrence that stays behind stands alone and carries no rule
- **AND** the next launch does not grow a new series around it

### Requirement: Two series can be joined

The system SHALL let the user join two series. The series the user is looking at SHALL
keep its series id and its shared fields; the rows of the other series SHALL take that
id and those fields.

The dates of both series SHALL survive the join.

The join SHALL NOT mix the two kinds of series. A **recurring series** and a series of
**picked dates** SHALL NOT be joined, and two recurring series whose rules differ SHALL
NOT be joined; the refusal SHALL name the reason. A join of two series of the same kind
— two series of picked dates, or two recurring series carrying the same rule — SHALL
succeed as before.

**Rationale:** the user is looking at one of the two series and says "this is the same
work". That series's title is the one they read, and its id is the one their other
devices know — so that series is the one that stays. Merging into whichever series
happens to come first would make the result depend on the sort order.

A series is one kind of thing, and the join is the one operation that could make it two.
Joining a recurring series with a series of picked dates would leave the joined series
holding both a rule and hand-picked dates; joining two recurring series with different
rules would leave its members disagreeing about the work — the one thing the rows of a
series may not do. Refusing is the smaller promise: the two series stay two, and the
user is told why.

**Test coverage:** the merge tests assert that two series of the same kind merge, that a
mixed pair is refused with a reason, and that two recurring series with different rules
are refused.

#### Scenario: The series the user is looking at keeps its id

- **GIVEN** two series, and the user is looking at the first
- **WHEN** the two are joined
- **THEN** the joined rows carry the id of the first

#### Scenario: The joined rows take the fields of the series the user is looking at

- **GIVEN** two series with different titles
- **WHEN** the user joins them from the first one
- **THEN** every row of the joined series carries the title of the first

#### Scenario: The dates of both series survive

- **GIVEN** two series on two dates each
- **WHEN** the two are joined
- **THEN** the joined series has four occurrences on those four dates

#### Scenario: A recurring series and a series of picked dates are not joined

- **GIVEN** a recurring series and a series of picked dates
- **WHEN** the user joins them
- **THEN** the two series stay two
- **AND** the user is told that the kinds differ

#### Scenario: Two recurring series with different rules are not joined

- **GIVEN** a weekly recurring series and a monthly one
- **WHEN** the user joins them
- **THEN** the two series stay two
- **AND** the user is told that the rules differ

---

### Requirement: A row of a series says what it is

A row whose task belongs to a series SHALL carry the mark of a series: the
icon, and — where the surface has room for a word — the number of dates the
series has.

The calendar's chip SHALL carry the mark in its compact form (the icon alone),
because a chip has no room for a word. The day's list SHALL carry the mark
together with the number of dates.

The number of dates SHALL be the number of occurrences that share the series id.

**Rationale:** a row of a series looks like a task, and nothing says that this
row and the row three days down are one piece of work. Without the mark the
user reads them as two things, and "apply to all of them" reads as a feature
of the row rather than as the truth about the work. The count is the number the
user needs when they ask "how much of this is left?".

#### Scenario: The day's list says how many dates the work has

- **GIVEN** a series of three occurrences
- **WHEN** the day's list shows one of the occurrences
- **THEN** the row carries the mark of a series and the number three

#### Scenario: The calendar's chip carries the mark

- **GIVEN** a task that belongs to a series
- **WHEN** the calendar shows the task
- **THEN** the chip carries the mark of a series

#### Scenario: A task that stands alone carries no mark

- **GIVEN** a task that belongs to no series
- **WHEN** the day's list shows it
- **THEN** the row carries no mark of a series

### Requirement: A deleted series can be put back

Deleting a series SHALL archive every occurrence of it and SHALL offer the way
back. The message SHALL say how many dates went away.

The undo SHALL restore exactly the occurrences that were archived — the ids it
archived, not the members the series has at the time of the undo.

The offer comes before the screen leaves. The screen SHALL navigate back once
the offer has been declined; the offer and the navigation cannot both happen at
once, because the toast that carries the offer belongs to the screen that is
leaving.

**Rationale:** a series is several dates of one work, and the work is not what a
person throws away by accident. The undo restores the ids rather than the
series, because the series changes: a date added between the deletion and the
undo must not come back with the work, and a date removed must not stay away.

The offer comes first because the toast is the screen's own: it lives in the
composition that the navigation tears down. A screen that left first would take
the way back with it, and the user would see the series archived with nothing to
say about it.

#### Scenario: Deleting a series archives every occurrence

- **GIVEN** a series of three occurrences
- **WHEN** the series is deleted
- **THEN** every occurrence is archived

#### Scenario: The undo says how many dates went away

- **GIVEN** a series of three occurrences
- **WHEN** the series is deleted
- **THEN** the message says that three dates were deleted

#### Scenario: The undo puts the occurrences back

- **GIVEN** a series of three occurrences that was deleted
- **WHEN** the undo is used
- **THEN** the three occurrences are no longer archived

#### Scenario: The screen leaves once the offer is declined

- **GIVEN** a series of three occurrences that was deleted
- **WHEN** the offer goes away without being taken
- **THEN** the screen is no longer open

#### Scenario: An occurrence added after the deletion stays away

- **GIVEN** a series of two occurrences that was deleted
- **WHEN** an occurrence joins the series and the undo is used
- **THEN** the two occurrences that were deleted come back and the new one stays

### Requirement: The statistics count works, not occurrences

The statistics SHALL count a series as one work. A work SHALL count as
completed on the day it was FIRST completed, and — while nothing of it is
completed — as overdue once, on the day of its EARLIEST missed date.

A work that has a completed occurrence SHALL NOT count as overdue.

**Rationale:** the statistics measure works, not rows. A series scheduled on
seven dates with three of them completed is one thing the user did, not three
things. The first completion is the day the work was finished; the later dates
are the same work carried on. And a work the user has started is not something
they have not done — it is something they are in the middle of.

#### Scenario: A series that was completed counts once

- **GIVEN** a series of three occurrences, all completed
- **WHEN** the statistics are computed
- **THEN** the work counts once, not three times

#### Scenario: The work is counted on the day it was first completed

- **GIVEN** a series with occurrences completed on three different days
- **WHEN** the statistics are computed
- **THEN** the work counts on the first of those days

#### Scenario: A series with nothing completed counts as overdue once

- **GIVEN** a series of three occurrences, none completed, all past due
- **WHEN** the statistics are computed
- **THEN** the work counts as overdue once, on the earliest of those days

#### Scenario: A work the user has started is not overdue

- **GIVEN** a series with one completed occurrence and one past-due occurrence
- **WHEN** the statistics are computed
- **THEN** the work counts as completed and not as overdue

#### Scenario: A task that stands alone counts as before

- **GIVEN** a task that belongs to no series
- **WHEN** the statistics are computed
- **THEN** the task counts as one work

### Requirement: A task document is applied whole

A task document consists of the row of the task, its tags, the links its
description implies, and the blocking dependencies it carries.

When a document is applied, the system SHALL write every part it carries, in
one unit of work: the row, the tags, the links and the dependencies either all
land or none of them do. A part the document does not carry SHALL NOT be
written at all — the document says nothing about it, and the part that is
there belongs to the device that has it.

The links are not a claim of their own: they are derived from the description
of the row that arrived, and the row is written whole. A document therefore
carries the links it carries through its description, and there is no second
claim about them to make.

**Rationale:** a document that arrived half-applied is worse than one that did
not arrive. The row would say the work has no links and no dependencies while
the rest of the database still says it has them, and nothing in the system
knows which of the two is true. One writer for the whole document makes "the
document was applied" a single fact rather than four.

The parts the document carries are the ones it speaks about. The wire carries
the fields the sending device knew about, so the absence of a part is not a
claim that the part is empty. What the absence means is stated by
`the-document-says-only-what-it-carries`.

#### Scenario: A row that arrives brings its tags with it

- **GIVEN** a task that carries a tag
- **WHEN** the row of that task arrives from another device
- **THEN** the tag is in the database

#### Scenario: A row that arrives brings the links its description implies

- **GIVEN** a task whose description links to another task
- **WHEN** the row of that task arrives from another device
- **THEN** the links of that task are in the database

#### Scenario: A row that arrives brings its dependencies with it

- **GIVEN** a task that depends on another task
- **WHEN** the row of that task arrives from another device
- **THEN** the dependency is in the database

### Requirement: A row of another profile is stored as it is

A device SHALL NOT write the parts of a document that belongs to a profile it
is not on. When a row of another profile arrives, the system SHALL write the
row and SHALL keep the links the row already carried, and SHALL NOT write its
tags, its links or its dependencies.

**Rationale:** the parts of a document live in cross-reference tables that are
scoped by the owner, and writing them would be a write to another account's
work. A row that is new on this device has no links to keep, and this device
does not derive them either — deriving them would be the same write.

#### Scenario: Another profile's row keeps the links it carried

- **GIVEN** a task of another profile whose row carries links
- **WHEN** the row of that task arrives
- **THEN** the row still carries the links it carried

#### Scenario: Another profile's row is not given the parts of a document

- **GIVEN** a task of another profile
- **WHEN** the row of that task arrives
- **THEN** this device writes the row and nothing else of that document

### Requirement: The document says what the dependencies are

The dependencies a document carries are the blocking ones — the edges the wire
carries. When a document is applied, the system SHALL replace the blocking
edges of the task with the ones the document carries, and SHALL leave the
edges of other kinds alone.

An edge that would close a cycle SHALL be refused on its own, and the rest of
the document SHALL still be applied.

A pair of tasks that already has an edge of another kind SHALL keep it, and the
document's blocking edge SHALL be added alongside it: the two relations say
different things about the pair, and the document only speaks about the
blocking one.

**Rationale:** the other kinds of edge — a task that follows up another, one
that duplicates another — are not in the document at all. Replacing them with
the document's edges would delete what the document never spoke about. And the
code that walks the graph assumes it has no cycles, so an edge that would close
one is refused rather than taken; refusing it alone keeps the rest of the
document from being lost with it.

#### Scenario: A dependency the document dropped is dropped

- **GIVEN** a task that depends on another task
- **WHEN** a document arrives that carries an empty set of dependencies
- **THEN** the task does not depend on the other task any more

#### Scenario: An edge of another kind survives the document

- **GIVEN** a task that follows up another task
- **WHEN** a document arrives that carries no dependency for it
- **THEN** the task still follows up the other task

#### Scenario: A document that would close a cycle still lands its row

- **GIVEN** two tasks that depend on each other
- **WHEN** a document arrives that would close the cycle
- **THEN** the row of the task is in the database and the cycle is not

### Requirement: A path that applies part of a document says so

A path that writes one field of a task SHALL NOT write the parts of the
document it was not given. The tags of a task are written by the paths that
know what they should be — the path that sets them, the path that writes a
batch, and the path that applies a document — and a path that applies a row
without them SHALL leave them alone.

**Rationale:** the task an edit path is given was read through `get()`, which
does not load the tags — they live in the cross-ref table. An empty tag list on
a task that was read is not a claim that the task has no tags; it is the
absence of a claim, and a writer that reads it as a claim strips the tags off
the task. A batch write is a different path: it is given the tags of the whole
batch, and it writes them.

#### Scenario: An edit leaves the tags of the task alone

- **GIVEN** a task that carries a tag
- **WHEN** the task is edited
- **THEN** the task still carries the tag

#### Scenario: The tags are changed by the path that knows them

- **GIVEN** a task that carries a tag
- **WHEN** the tags of the task are set to another tag
- **THEN** the task carries the other tag and no longer the first

### Requirement: A document carries claims, and a claim can be absent

The tags and the dependencies of a task document SHALL be optional. A document
that does not carry them SHALL be read as saying nothing about them, and a
document that carries an empty collection SHALL be read as saying there are
none. The two SHALL NOT be read the same way.

**Rationale:** the wire carries the fields the sending device knew about. A
device that predates the tags sends no `tags` key, and reading that key as an
empty list would let it delete the tags of every task it writes. The claim
that is not made is not a claim that there are none.

#### Scenario: A document without the tags carries no claim about the tags

- **GIVEN** a task that carries a tag
- **WHEN** a document arrives that carries no tags
- **THEN** the task still carries the tag

#### Scenario: A document with no tags says there are none

- **GIVEN** a task that carries a tag
- **WHEN** a document arrives that carries an empty set of tags
- **THEN** the task carries no tag

#### Scenario: A document without the dependencies carries no claim about them

- **GIVEN** a task that depends on another task
- **WHEN** a document arrives that carries no dependencies
- **THEN** the task still depends on the other task

### Requirement: The absence is read before the decode

The receiving side SHALL read which parts a document carries from the document
itself, before it is decoded into a task. The names of the parts SHALL come
from the model of the task, so that a change of the wire name cannot leave the
reading behind.

**Rationale:** after the decode the two cases are the same task — the fields
the model cannot do without have defaults — and the difference between "no
tags" and "no claim about the tags" is gone. Reading it earlier is the only
place the difference still exists.

#### Scenario: A document that carries the tags says what they are

- **GIVEN** a task that carries a tag
- **WHEN** the document of that task arrives
- **THEN** the document says that the task carries the tag

#### Scenario: A document that does not carry the tags says nothing about them

- **GIVEN** a task that carries a tag
- **WHEN** a document arrives that has no tags
- **THEN** the document says nothing about the tags

### Requirement: A pair may carry several relations

A pair of tasks SHALL be allowed to carry more than one relation. The relations
are different claims about the same pair, and the document SHALL apply only the
one it speaks about: the blocking one.

**Rationale:** the cross-reference key carries the verb, so the relations of a
pair are separate facts. A task can wait for another one and follow it up, and
a document that says "this task waits for that one" says nothing about the
follow-up.

#### Scenario: A relation of another kind survives a document

- **GIVEN** a task that follows up another task
- **WHEN** a document arrives that says the task waits for the other one
- **THEN** the task still follows up the other task, and waits for it too

### Requirement: A repair follows the claim of the writer

When the system repairs the series around a row it has written, it SHALL apply
the shared fields the writer had a claim about, and SHALL leave the fields it
had no claim about alone. A document that carries no `tags` key SHALL NOT take
the tags off the other members of the series.

**Rationale:** the repair copies the source's shared fields onto the members,
and the source's tags are the ones the database holds. A row that is new on
this device holds none — and the row is new precisely because the document
arrived. Copying that emptiness onto the members would turn the absence of a
claim into a deletion one level further out, and the document never spoke
about the members at all.

#### Scenario: A document without a claim leaves the tags of the series alone

- **GIVEN** a series of two tasks, one of which carries a tag
- **WHEN** a document arrives for the other one that carries no tags
- **THEN** the task that carries the tag still carries it

### Requirement: A refused edge is said out loud
When the system refuses an edge of a document because it would close a cycle,
it SHALL record the refusal, and the rest of the document SHALL still be
applied.

**Rationale:** the refusal is a disagreement between two devices about the
shape of the work, and it repeats on every pull. A refusal nobody can see is
a device that silently disagrees with the device that sent it.

#### Scenario: A refused edge is recorded and the row lands

- **GIVEN** two tasks that depend on each other
- **WHEN** a document arrives that would close the cycle
- **THEN** the row of the task is in the database and the refusal is recorded

### Requirement: A batch write repairs the series, like an ordinary write

A batch write of tasks SHALL repair the series around it, the way an ordinary
write of one task does: the members that were not in the batch SHALL follow the
ones that were.

**Rationale:** a series is one piece of work, and the rows of a series are
copies of it. A write that changes one of the copies changes the work, and the
other copies follow. A batch write is that write, taken several times at once.

#### Scenario: A partial batch write repairs the rest of the series

- **GIVEN** a series of two tasks
- **WHEN** a batch write renames one of them
- **THEN** the other one carries the new name as well

### Requirement: A push carries what the database holds

When the system pushes a task to the server, it SHALL push the task as the
database holds it — the row, the tags and the blocking edges — and SHALL NOT
push a part it did not read as the truth about the work. The task the
database holds is read back after the write, within the transaction that
wrote it, so the claim and the write are one unit of work.

**Rationale:** on the wire an empty collection is a claim. A document whose
`tags` is empty says "the work has no tags", and the receiver removes them; a
document whose `dependsOn` is empty says "the work waits for nothing", and the
receiver removes the edges. A task read through `get()` carries two empty
collections that are the truth about the *read* and not about the work, and a
device that sends them as claims strips the work of every other device on the
next pull. The write that pushes has to push what the database holds.

#### Scenario: An edit does not tell the server that the work has no tags

- **GIVEN** a task that carries a tag
- **WHEN** the task is edited through a read that does not load the tags
- **THEN** the pushed task carries the tag

#### Scenario: An edit does not tell the server that the work waits for nothing

- **GIVEN** a task that waits for another task
- **WHEN** the task is edited through a read that does not load the edges
- **THEN** the pushed task carries the edge

#### Scenario: A batch write does not tell the server that the work waits for nothing

- **GIVEN** a task that waits for another task
- **WHEN** the task is written as part of a batch built from a read that does
  not load the edges
- **THEN** the pushed task carries the edge

#### Scenario: A bulk archive does not tell the server that the work has no tags

- **GIVEN** a completed task that carries a tag
- **WHEN** the completed tasks are archived in bulk
- **THEN** the pushed task carries the tag

#### Scenario: The claim is the task the write left

- **GIVEN** a task that carries a tag
- **WHEN** the task is written
- **THEN** the task the push carries is the task the write left, tags included

### Requirement: A repeating task's future is visible as real tasks

When a task carries a repeat rule, the app SHALL materialise the rule's future
occurrences as separate tasks of the same series, each with its own due date, the same
work — title, project, tags, priority and accent colour — and the same rule. The
materialised occurrences SHALL appear in the Upcoming agenda in the section of their own
date, and each SHALL be openable, editable, completable and deletable on its own.

**Rationale:** a repeating task was one row that moved. A row cannot hold a future — it
can only be somewhere else, and wherever it is, the previous occurrence is gone. The
user completed "buy bread — every week" and saw nothing new, because the row that
should have carried the next week was closed and dated in the future.

#### Scenario: A rule produces occurrences the user can see

- **GIVEN** a task with a weekly rule, due today
- **WHEN** the rule is saved
- **THEN** the task itself is the current occurrence, on today's date
- **AND** the series holds further occurrences, dated in the future, carrying the same
  title, project, tags and priority
- **AND** those occurrences appear in the Upcoming agenda in the section of their own
  date

#### Scenario: An occurrence is an ordinary task

- **GIVEN** a materialised future occurrence
- **WHEN** the user opens it
- **THEN** it opens, edits, completes and deletes like any other task

#### Scenario: A closed occurrence is not shown as an open future task

- **GIVEN** a series with a closed occurrence
- **WHEN** the Upcoming agenda is drawn
- **THEN** the closed occurrence is not presented as an open future task

### Requirement: Closing an occurrence leaves it on its date

When the user closes an occurrence of a repeating task, the app SHALL close that
occurrence and SHALL leave its due date unchanged, and SHALL materialise the next
occurrence as a new task of the same series. The app SHALL NOT move a closed
occurrence's date, and SHALL NOT leave a task that is both closed and dated in the
future.

**Rationale:** the old roll-forward closed the row and then moved its date, and the
move never touched the closed state — so the row was closed *and* dated in the future.
It left "Today" and came back in "Upcoming" struck through, and the next occurrence
never existed as an open task.

#### Scenario: The closed occurrence stays where it was

- **GIVEN** a repeating task due today
- **WHEN** the user closes the current occurrence
- **THEN** the occurrence stays closed on today's date
- **AND** its due date is unchanged

#### Scenario: Closing does not shrink the future

- **GIVEN** a series with open occurrences in the future
- **WHEN** the user closes the current occurrence
- **THEN** the number of open future occurrences of the series does not decrease

#### Scenario: A refusal changes nothing

- **GIVEN** an occurrence that has an open prerequisite
- **WHEN** the user tries to close it
- **THEN** the close is refused
- **AND** no occurrence is closed, none is created, and no date moves

#### Scenario: A failed close leaves nothing behind

- **GIVEN** a series of occurrences
- **WHEN** the write that closes an occurrence and materialises the next one fails
- **THEN** the series is exactly what it was — no half-built series

#### Scenario: A missed occurrence is a closed occurrence, dated in the past

- **GIVEN** a catch-up rule whose occurrence was left undone for three periods
- **WHEN** the user closes it
- **THEN** the missed occurrences exist as closed rows dated in the past
- **AND** they are not materialised twice on a retry

#### Scenario: One occurrence closes without closing the others

- **GIVEN** a series with several open occurrences
- **WHEN** the user closes a future one
- **THEN** the earlier occurrences stay open

#### Scenario: A shared change reaches the whole series

- **GIVEN** a series of occurrences
- **WHEN** the user renames one of them
- **THEN** every occurrence carries the new name

#### Scenario: Removing the rule stops the series growing

- **GIVEN** a series with future occurrences
- **WHEN** the user removes the rule
- **THEN** the series stops growing
- **AND** the existing occurrences stay where they are

#### Scenario: The bases differ only in the date

- **GIVEN** a repeating task closed under each of the three bases
- **THEN** the occurrence is closed and unmoved in every case
- **AND** only the date of the next occurrence differs between the bases

### Requirement: A rule set on a created task survives the create

When the user creates a task with a repeat rule, the app SHALL carry the rule onto the
row it creates, and SHALL materialise the series the rule describes. When the draft
carries a repeat rule **and** picked dates, the app SHALL drop the rule and say so.

**Rationale:** the create path went through the series planner, which cleared the rule
on every row it wrote — including the single row of a one-date draft. The draft carried
the rule and the planner dropped it on the way to the row, so a task created with
"repeat every week" came out with no rule at all, silently. Only the task screen's
edit path ever produced a repeating task.

#### Scenario: A created repeating task repeats

- **GIVEN** a draft with a weekly rule and today's date
- **WHEN** the task is created
- **THEN** the created row carries the rule
- **AND** the series holds the materialised future occurrences

#### Scenario: A rule together with picked dates is dropped and said so

- **GIVEN** a draft with a repeat rule and a second date
- **WHEN** the user adds the second date
- **THEN** the draft carries no rule
- **AND** the user is told the rule was dropped

### Requirement: A rule that has run out closes the occurrence and stops the series

When the next date of a rule falls past the rule's end date, the app SHALL close the
occurrence, SHALL materialise no further occurrence, and SHALL NOT archive anything.
The occurrence SHALL remain a closed task on its own date.

**Rationale:** the series used to be soft-deleted when its rule ran out, and the row
went to the trash. That was right when the single row *was* the series. Under
materialisation the series is over precisely when no further occurrence is
materialised, and a closed occurrence is already out of the active list — archiving a
row the user closed would move finished work into the trash, which is a different thing
from ending a schedule.

#### Scenario: The last occurrence closes and nothing is archived

- **GIVEN** a repeating task whose rule ends before its next occurrence
- **WHEN** the user closes the current occurrence
- **THEN** the occurrence is closed on its own date
- **AND** the series holds no further occurrence
- **AND** nothing was archived

### Requirement: A rule that measures from completion shifts the window when the close is late

The app SHALL measure a "from completion" rule from the date the occurrence was
closed, and a "from due date" rule from the occurrence's due date. The catch-up rule
SHALL measure from the occurrence's due date, because it fills the gaps between
*scheduled* occurrences. Closing an occurrence late SHALL therefore move the window of
a "from completion" series by exactly how late the close was, and SHALL NOT move the
window of a "from due date" or catch-up series at all. The app SHALL apply that move as
a change of date on the rows that remain, and SHALL NOT discard their own state.

**Rationale:** the default base measures from the completion date, so the window of the
common case depends on when the user acts. The move is the base's meaning and not a
defect; rebuilding the rows instead of moving them would lose the per-occurrence
reminders and checklists of every future row on every late close.

#### Scenario: A late close moves a "from completion" series

- **GIVEN** a weekly "from completion" rule, planned from a due date
- **WHEN** the occurrence is closed three days late
- **THEN** the window moves by three days
- **AND** the rows that remain keep their own state

#### Scenario: A late close does not move a "from due date" series

- **GIVEN** a weekly "from due date" rule
- **WHEN** the occurrence is closed three days late
- **THEN** the next occurrence is still due on the date the rule gives
- **AND** the window does not move

#### Scenario: A late close does not move a catch-up series

- **GIVEN** a weekly catch-up rule
- **WHEN** the occurrence is closed three days late
- **THEN** the missed occurrences are dated from the schedule
- **AND** the window does not move

### Requirement: A repeating task's window does not move on its own

The set of future occurrences of a repeating series SHALL be determined by the rule and
by the series' position, and SHALL NOT change because the calendar day changed. The
app SHALL NOT need a scheduled job or a write on a read path to keep the window
current.

**Rationale:** a window measured from *today* makes the row set a function of the date,
so something has to notice the day change and write — a daily job that may not have run
before the user opens the app, or a read that mutates the database.

#### Scenario: The same rule at the same position gives the same dates

- **GIVEN** a rule and a series position
- **WHEN** the window is planned twice
- **THEN** both plans give the same dates

#### Scenario: A rare rule still has a next occurrence

- **GIVEN** a task with a yearly rule
- **WHEN** the window is planned
- **THEN** the series holds the next year's occurrence

#### Scenario: A dense rule stays bounded

- **GIVEN** a task with a daily rule
- **WHEN** the window is planned
- **THEN** the series holds at most ten future occurrences

### Requirement: A recurring series rotates at the ceiling rather than refusing

A repeating series SHALL hold at most thirty occurrences. When it reaches that ceiling
and another occurrence would be added, the app SHALL remove the oldest closed
occurrence from the series and keep it as an ordinary closed task, rather than refusing
to grow. The occurrence that leaves SHALL lose the rule with the series id, exactly as
an occurrence the user takes out of the series does.

**Rationale:** the ceiling was a refusal, and for thirty picked dates it still is — that
is what the user asked for. For a recurring series a refusal would stop the
recurrence, so a daily task would stop repeating after thirty completions.

The rule has to go with the series id here too. A closed row that keeps its rule and has
no series id is exactly the shape the launch repair adopts into a new series, so a rule
that stayed would make the ceiling a loop: the repair would grow a fresh series around
the rotated row at the next launch.

#### Scenario: The series keeps recurring

- **GIVEN** a series at the ceiling of thirty occurrences
- **WHEN** one more occurrence is completed
- **THEN** the series still holds thirty occurrences
- **AND** the oldest closed occurrence has left the series and is still an ordinary
  closed task
- **AND** it carries no rule

#### Scenario: The rotated occurrence is not adopted back into a series

- **GIVEN** a series at the ceiling of thirty occurrences
- **WHEN** one more occurrence is completed
- **THEN** the rotated occurrence carries no rule
- **AND** the next launch does not grow a new series around it

### Requirement: Existing repeating tasks are repaired at launch

The app SHALL, at launch, turn a task that carries a repeat rule and belongs to no
series into a series with its window materialised. It SHALL reopen that task only when
the series would otherwise have no open occurrence, and SHALL leave a repeating task
that was closed in the past closed. The repair SHALL cover the active profile; another
profile's legacy repeating tasks SHALL be repaired at the next launch with that profile
active.

**Rationale:** the row the old roll-forward left closed with a future date is the row
the user is complaining about, and it is the current occurrence of the series it should
become. A repeating task closed in the past is history, and reopening it would
resurrect finished work.

#### Scenario: The rolled-forward row becomes the current occurrence

- **GIVEN** a task with a rule, closed, dated in the future, belonging to no series
- **WHEN** the app launches
- **THEN** the task belongs to a series and is open
- **AND** the series holds the materialised future occurrences

#### Scenario: History stays closed

- **GIVEN** a task with a rule, closed, dated in the past
- **WHEN** the app launches
- **THEN** the task stays closed

#### Scenario: The repair is idempotent

- **GIVEN** a repaired database
- **WHEN** the app launches again
- **THEN** nothing changes and no occurrence is duplicated

#### Scenario: Another profile is repaired at its next launch

- **GIVEN** a second profile with a legacy repeating task
- **WHEN** the app is launched with that profile active
- **THEN** that profile's legacy repeating task is repaired

### Requirement: A list row says that the task repeats

A task row in a list SHALL carry a repeat badge when the task's own rule is present, and
SHALL NOT carry one when the task has no rule. The badge SHALL be derived from the
task's own data rather than from a separately maintained flag, and it SHALL be shown
even when the row has nothing else to show.

**Rationale:** the row already drew a repeat icon, and nothing ever set the flag it
draws from — the agenda built every row with the flag hardcoded to "no". The icon had
never been drawn by anything.

#### Scenario: A repeating task's row shows the badge

- **GIVEN** a task row whose task carries a repeat rule
- **WHEN** the list is rendered
- **THEN** the row carries a repeat badge

#### Scenario: A one-off task's row does not

- **GIVEN** a task row whose task carries no rule
- **WHEN** the list is rendered
- **THEN** the row carries no repeat badge

#### Scenario: Every occurrence says it repeats

- **GIVEN** a series of occurrences
- **WHEN** any of them is rendered
- **THEN** every one of them carries the repeat badge

#### Scenario: The badge shows on an otherwise empty row

- **GIVEN** a repeating task with no due date, no project, not blocked and not in a
  series
- **WHEN** the list is rendered
- **THEN** the row still shows the repeat badge

### Requirement: A list row names the task's project

A task row in a list SHALL carry a project badge when the task belongs to a project, and
the badge SHALL carry the project's name and the project's colour. A task without a
project SHALL carry no project badge, and a task whose project has been closed SHALL
still carry one. The project's name and colour SHALL be resolved once per list build,
not once per row.

**Rationale:** the project's name never reached the row, so every agenda row with a
due date printed the literal "Без проекта" next to it. A badge carrying the project's
colour makes the projects distinguishable at a glance, which is the part a name alone
cannot do.

#### Scenario: The badge names and colours the project

- **GIVEN** a task row whose task belongs to a project with a colour
- **WHEN** the list is rendered
- **THEN** the row carries a project badge with the project's name and the project's
  colour

#### Scenario: A task without a project has no badge

- **GIVEN** a task row whose task belongs to no project
- **WHEN** the list is rendered
- **THEN** the row carries no project badge

#### Scenario: A closed project still names itself

- **GIVEN** a task row whose task belongs to a project that has been closed
- **WHEN** the list is rendered
- **THEN** the row still carries a project badge with that project's name

#### Scenario: The placeholder text is gone

- **GIVEN** any task row
- **WHEN** the list is rendered
- **THEN** the row does not print a placeholder where a project's name would be
