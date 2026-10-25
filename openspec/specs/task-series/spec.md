# task-series Specification

## Purpose
One piece of work scheduled on several dates: one task row per date, all
sharing one series identity, with the shared fields edited for the whole
series in one operation.

## Requirements

### Requirement: REQ-TS-001

The system **SHALL** let a user schedule one piece of work on several dates, and
**SHALL** create one task row per date, all sharing one series identity.

**Rationale:** some work is not one occurrence. Three dates of one obligation are
three rows of one job, and the user must be able to say so. A single date, on the
other hand, is an ordinary task and must stay one — the feature must not make
every task a series.

**Test coverage:** the planner is covered by unit tests asserting the shape of the
plan: one date produces one row with no series id, several dates produce one row
each with the same id.

#### Scenario: One date is an ordinary task
- The user picks one date
- One task is created
- The task carries no series identity

#### Scenario: Several dates are one series
- The user picks three dates
- Three tasks are created
- All three carry the same series identity
- Each carries its own date

---

### Requirement: REQ-TS-002

A field that describes the work — the title, the description, the priority, the
project, the tags, the accent, the emoji, the estimate, the recurrence — **SHALL**
be edited for the whole series, and **SHALL** be written to every member in one
operation.

**Rationale:** a series whose three rows carry three titles says three different
things about the same job, and no rule says which one is right. The write has to
be one operation: a series that landed halfway would be indistinguishable from a
series that is broken.

**Test coverage:** the slot tests assert that editing a shared field changes every
member, and that the members are written together.

#### Scenario: Editing the title of one member
- The user edits the title of one task of a series
- Every task of the series carries the new title

#### Scenario: A write that fails halfway
- The write of the series fails
- No member carries the new title

---

### Requirement: REQ-TS-003

A field that describes the occurrence — the date, the time of day, the completion
state, the pin, the checklist, the attachments — **SHALL** be edited for that
occurrence alone.

**Rationale:** completing one occurrence must not complete the others. "The same
for all of them" is a lie about these fields, and a rule that applied it would
destroy work the user did not ask to destroy.

**Test coverage:** the slot tests assert that completing one member leaves the
others untouched.

#### Scenario: Completing one occurrence
- The user completes one task of a series
- The other tasks of the series stay open

---

### Requirement: REQ-TS-004

The system **SHALL** let the user start a series from the create editor, from the
calendar, and from the agenda, and **SHALL** create the task by the same path in
all three.

**Rationale:** three entry points that create the task three different ways would
produce three different tasks, and the difference would only show up in a bug
report weeks later. The days travel in a draft, so the editor is the only place
that creates the task.

**Test coverage:** the create flow test asserts that a draft carrying several
dates becomes a series, whatever screen wrote the draft.

#### Scenario: Starting a series from the calendar
- The user long-presses a day that holds tasks
- The user taps three more days
- The user taps "Создать задачу"
- The editor opens with the four dates
- Saving creates four tasks that share one series identity

#### Scenario: Starting a series from the agenda
- The user taps the "task on several days" action in the top bar
- The user picks three days
- The editor opens with the three dates
- Saving creates three tasks that share one series identity

---

### Requirement: REQ-TS-005

The system **SHALL** refuse a series of more than thirty dates, and **SHALL** tell
the user that it did.

**Rationale:** five hundred tasks from one action is a way of loading the
database, not a feature. A silent cap would let the user believe they picked forty
dates and then discover that thirty survived.

**Test coverage:** the planner test asserts that the thirty-first date is refused.

#### Scenario: Picking more than thirty days
- The user has picked thirty days
- The user taps another day
- The day is not added
- The user is told that thirty is the maximum

---

### Requirement: REQ-TS-006

A task of a series **SHALL** be shown as belonging to that series: the detail
screen **SHALL** show how many dates the work is scheduled on, **SHALL** let the
user reach the other occurrences, and **SHALL** offer to apply the time of day and
the pin to all of them, and to archive the whole series.

**Rationale:** a user who cannot see that a task is one of three has no way to
know that editing the title will change the other two, nor that the series can be
closed as a whole. The other occurrences have to be reachable, or the series is
only visible to the code.

**Test coverage:** the detail screen's UI test asserts that the card is on screen
for a task of a series and absent for a task that stands alone.

#### Scenario: Opening a task of a series
- The task belongs to a series of three
- The detail screen shows the series card
- The card names the three dates
- Tapping a date opens that occurrence

#### Scenario: A task that stands alone
- The task belongs to no series
- The detail screen shows no series card

---

### Requirement: REQ-TS-007

A repeat rule and a set of dates **SHALL NOT** be in force on the same task, and
choosing several dates **SHALL** drop the rule and say so.

**Rationale:** a rule generates occurrences on its own. A row that carried both
would produce dates the user never picked, and the two intentions would quietly
fight over the same row.

**Test coverage:** the create view model test asserts that picking a second date
drops the rule and leaves the message on screen.

#### Scenario: A rule and several dates
- The user has set a repeat rule
- The user picks a second date
- The rule is dropped
- The user is told that the rule was dropped

---

### Requirement: REQ-TS-008

A backup of a task **SHALL** carry its series identity, and the sync allowlist
**SHALL** accept it.

**Rationale:** a backup that dropped the identity would restore a series as
unrelated tasks — the dates would survive, the connection between them would not.
The server rejects a document with a field it does not know, so a client that
writes the identity needs the server to accept it.

**Test coverage:** the backup round-trip test asserts that the identity survives,
and the allowlist gate fails if the client and the server seed disagree.

#### Scenario: A backup and a restore
- A task of a series is backed up
- The backup is restored
- The restored task still belongs to the series
