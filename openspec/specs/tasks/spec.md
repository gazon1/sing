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
