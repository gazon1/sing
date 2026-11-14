# task-editor-parity

## ADDED Requirements

### Requirement: REQ-PARITY-1 Create and Edit expose one field contract

The screen that creates a task and the screen that opens an existing one SHALL
expose the same set of user-editable fields, in the same order, for every field
that does not require the task to exist.

A field that requires the task to exist SHALL be absent from the creating screen
only where the absence is necessary, and the reason SHALL be recorded beside the
list that holds the parity claim. A field SHALL NOT be absent because a call site
forgot to pass it.

Every field the editor offers SHALL reach the created task. A field whose value
the creating write drops SHALL NOT be offered: a control that accepts a value and
discards it reports a success that did not happen.

**Rationale:** the two screens already shared one renderer, so the divergence was
in what each call site passed — and in two fields the write path dropped after
the row had accepted them. The research in #2871 measured the result: a task
created on one screen had a different shape from the same task opened on the
other, and the two screens taught two sets of rules for one object.

#### Scenario: The same fields in the same order

- **Given** the creating screen and the detail of an existing task
- **When** both are open in turn on the same device
- **Then** the attribute rows they draw are the same rows in the same order
- **And** the comparison excludes only the rows listed as necessarily one-sided,
  each with its reason

#### Scenario: A field offered by the editor reaches the created task

- **Given** a draft on the creating screen with the pin set and an estimate chosen
- **When** the task is created
- **Then** the created task carries the pin and the estimate
- **And** no field the editor drew was dropped on the way to the row

### Requirement: REQ-PARITY-2 A pristine form shows no validation error

The creating screen SHALL NOT show a validation error before the user has edited
the draft or attempted a save.

A validation error SHALL be inline, under the field it is about. A failure to
persist SHALL be presented as a failure to persist and SHALL NOT be presented as
a validation error.

**Rationale:** the red line was on the create form from the first frame, which
made the first thing a person saw when creating a task a complaint, and it made a
disabled button read as an error the user had caused.

#### Scenario: Opening the creating screen

- **Given** a draft the user has not touched
- **When** the creating screen is opened
- **Then** no validation error is shown
- **And** the control that commits the draft is not enabled

#### Scenario: Emptying the title after typing

- **Given** a creating screen whose title the user has typed and then cleared
- **When** the field is empty
- **Then** the validation message is shown under the field
- **And** the message is not repeated as a transient notification

#### Scenario: A save the storage refused

- **Given** a valid draft whose creation fails below the domain
- **When** the user attempts the save
- **Then** the failure is reported as a failure
- **And** no validation message is drawn under the title

### Requirement: REQ-PARITY-3 A picker keeps its actions on the screen

A picker sheet whose body can be taller than the space the sheet is given SHALL
open in a state where its action row — the control that commits, cancels or
clears the choice — is on the screen and reachable.

An action row that is below the screen edge SHALL be treated as a defect, not as
content the user is expected to scroll to: it is not on the screen, and where the
platform exposes only what is drawn, it is not in the accessibility tree either.

**Rationale:** the multi-date picker opened half-expanded; the grid filled the
visible half and the confirm row fell below the edge of a 360×800 screen. The
sheet could not be committed by a person, and the flow that drives it failed at
the confirm rather than at anything the flow did wrong.

#### Scenario: The date picker is opened on a phone

- **Given** a task editor on a 360×800 screen
- **When** the date row is tapped and the picker opens
- **Then** the control that commits the chosen dates is on the screen
- **And** a flow that confirms the picker completes

The half-expanded state exists only where the platform offers it, so this
scenario is held by the device flow (`13-create-full-task`) and not by a desktop
component test: the desktop host never enters that state, and a test there would
pass either way.

### Requirement: REQ-PARITY-4 Create and Edit keep separate save lifecycles

The two screens SHALL each keep their own commit, discard and back behaviour: the
creating screen SHALL autosave a draft and commit it on an explicit action, and
the detail SHALL autosave silently. Leaving the creating screen with an unsaved
draft SHALL ask before discarding it; leaving the detail SHALL NOT ask, because
everything on it is saved.

**Rationale:** one renderer and one field contract do not require one lifecycle.
The creating screen exists before the task does, which is what the draft and the
explicit save are for; collapsing them into the detail's lifecycle would make the
draft a special case of a task that is not real.

#### Scenario: Leaving a dirty, never-saved draft

- **Given** a creating screen whose draft has been edited and never saved
- **When** the user goes back
- **Then** the screen asks before discarding the draft
- **And** the typed title is not lost without the user's answer

#### Scenario: Leaving an existing task

- **Given** a task opened on the detail whose edits have been autosaved
- **When** the user goes back
- **Then** the screen leaves without asking
- **And** nothing written is lost
