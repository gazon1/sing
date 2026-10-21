# Tasks — Observable Behavior

**capability:** `tasks` | **status:** accepted

---

## MODIFIED Requirements

### Requirement: Task attributes are editable from the editor

The system SHALL let the user set priority and due date from the editor's
attribute rows, SHALL leave a task undated when the date picker is cancelled, and
SHALL pre-fill a due date only where a tab says it should.

Every attribute row SHALL open the picker it names, in every editor — the one that
creates a task and the one that opens an existing one. A row that opens nothing SHALL
NOT be presented as a row the user can press.

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
