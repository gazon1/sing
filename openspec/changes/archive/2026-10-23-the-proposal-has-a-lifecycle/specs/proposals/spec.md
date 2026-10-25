# The proposal has a lifecycle — Observable Behavior

**capability:** `proposals` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-PROP-010 The user may change the value of a proposal before it is applied

A proposal that carries a value a person would type SHALL offer that value for
change before it is applied. The value the user types SHALL be read by the
same reader that read the model's value, so a value that does not read SHALL be
refused, and the refusal SHALL carry the reason. A refused value SHALL leave
the proposal as it was, and the proposal SHALL still be waiting for a decision.

A change that carries no value a person would type — a list of texts, a
project id — SHALL NOT offer a field, because a field that cannot be filled in
correctly is worse than no field.

**Rationale:** the model named the value. The model may have named it wrong, and
the user is the one who knows what they meant. A proposal the user can only
accept or refuse makes the only way to get the right value to start the
conversation again.

#### Scenario: The user changes the value

- **Given** a proposal waiting for a decision whose change carries a value
- **When** the user changes the value
- **Then** the proposal carries the value the user typed
- **And** the summary the user reads says the new value
- **And** the change is still waiting for a decision

#### Scenario: The user types a value that does not read

- **Given** a proposal waiting for a decision
- **When** the user types a value the domain cannot hold
- **Then** the proposal keeps the value it had
- **And** the reason is shown next to the change
- **And** the change is still waiting for a decision

#### Scenario: A change with nothing to type

- **Given** a proposal whose change carries a list of texts or an id
- **When** the user looks at it
- **Then** there is no field to change it in

### Requirement: REQ-PROP-011 The reason for a refusal is visible

A proposal the user turned down SHALL keep the reason they gave, and that
reason SHALL be visible on the card while the proposal is on the screen. A
change that could not be applied SHALL keep the reason it failed, and that
reason SHALL be visible next to the change it belongs to. The screen SHALL read
the events that say what went wrong.

**Rationale:** a refusal the user cannot see is a refusal they cannot learn
from, and a failure that says nothing leaves the user pressing the same button
and wondering whether the app is broken. Before this, the proposals screen did
not read its error events at all: every failure there was silent.

#### Scenario: The user turns a change down with a reason

- **Given** a proposal with two changes, one of them still waiting
- **When** the user turns the other one down and gives a reason
- **Then** the turned-down change is still on the card
- **And** the reason is shown with it

#### Scenario: A change cannot be applied

- **Given** a proposal whose change cannot be applied
- **When** the user applies it
- **Then** nothing is changed
- **And** the reason is shown next to the change
- **And** the change is still waiting for a decision

### Requirement: REQ-PROP-012 The vocabulary survives the database

Every kind of change and every value a kind can carry SHALL survive being
written to the database and read back. A kind that this build knows and the
codec cannot read SHALL be reported by a check, not dropped silently.

**Rationale:** the codec decodes on a string — the type name of the kind — so a
kind it was not taught to read does not fail to compile and does not fail
loudly: it decodes to nothing, the item is dropped, and the proposal silently
loses a change. The exhaustive `when`s over the kind in the domain do not help,
because the codec is not a `when` over the type.

#### Scenario: Every kind is written and read back

- **Given** every kind this build knows
- **When** each is written to the database and read back
- **Then** each reads back as the kind it was

#### Scenario: A kind arrives without a sample

- **Given** a kind that was added to the vocabulary
- **When** the round-trip check runs
- **Then** the check fails, naming the kind that has no sample

### Requirement: REQ-PROP-013 A change that fails while it is applied goes back to waiting

The claim of a change SHALL be the decision and the writing of the change SHALL be
the work. When the work fails, the claim SHALL be released, the change SHALL go
back to waiting for a decision, and the reason SHALL be visible next to it. A
change that was not written SHALL NOT be recorded as decided.

A change that writes several rows SHALL write all of them or none of them: the
rows written before a later row fails SHALL be taken back, so that the next
attempt — which is the user's — does not write them twice.

**Rationale:** the change is confirmed before it is written, so that a proposal
the app cannot apply does not burn its one decision. But a decision that is
recorded for something that never happened is worse than a silent failure: the
user sees the change as done, and it is not. And a change that leaves half of
its rows behind would be applied twice the next time the user tries.

#### Scenario: The write fails

- **Given** a proposal waiting for a decision
- **When** the user applies it and the write fails
- **Then** the change is still waiting for a decision
- **And** the reason is shown next to it
- **And** the user can apply it again

#### Scenario: The write breaks halfway

- **Given** a proposal that adds three rows
- **When** the second row fails
- **Then** none of the three rows is on the task
- **And** applying the proposal again adds each row once

#### Scenario: The write is cut short

- **Given** a proposal that adds three rows
- **When** the write is cancelled after the first row — the user left the screen
- **Then** the row that was written is taken back
- **And** the change is waiting for a decision again
- **And** applying the proposal again adds each row once

### Requirement: REQ-PROP-014 A date can be cleared

The vocabulary SHALL hold the absence of a date, and the reader SHALL read an
empty value as that absence rather than as a mistake. A task that can be given a
due date SHALL be able to lose it again, and the stored shape SHALL stay the one
the rows written before the typed dictionary already have.

**Rationale:** the tools that set a date have always said that an empty value
clears it. A vocabulary that refuses the empty value makes the tool's own
description false, and turns "the task has no due date any more" into an error
the model cannot recover from.

#### Scenario: The model clears the due date

- **Given** a task with a due date
- **When** the model asks for the due date to be cleared
- **Then** the proposal says the due date is cleared
- **And** applying it leaves the task without a due date

#### Scenario: The cleared date survives the database

- **Given** a proposal that clears a due date
- **When** it is written to the database and read back
- **Then** it reads back as the same change

### Requirement: REQ-PROP-015 A value that does not read keeps the change

A value read from the database that this build cannot read SHALL NOT make the
change disappear. The change SHALL stay on the card, SHALL carry the reason it
cannot be applied, and SHALL be waiting for a decision. Applying it SHALL fail
with that reason, and the change SHALL stay waiting.

The reason SHALL be the one the reader gives, and the reader SHALL be the same
one that reads the value the user types.

**Rationale:** a row written by a build that knew the field — an enum that lost a
variant, a spelling that changed — read as "nothing here", and the proposal
quietly lost a change. The user saw a card with a change missing and no reason:
the change was not refused, it was deleted. A change the user cannot see is a
change nobody decided about.

#### Scenario: The stored value no longer reads

- **Given** a proposal whose stored value this build cannot read
- **When** the proposal is read from the database
- **Then** the change is still on the card
- **And** the reason it cannot be read is shown next to it
- **And** the change is waiting for a decision

#### Scenario: The user applies a value that cannot be read

- **Given** a proposal whose stored value this build cannot read
- **When** the user applies it
- **Then** nothing is changed
- **And** the reason is shown next to the change
- **And** the change is still waiting for a decision

#### Scenario: The user types a value that does read

- **Given** a proposal whose stored value this build cannot read
- **When** the user types a value the domain can hold
- **Then** the proposal carries the value the user typed
- **And** the change can be applied

### Requirement: REQ-PROP-016 Every surface answers a proposal the same way

Every surface that shows a proposal SHALL show the reason a change failed next
to that change, and SHALL report a failure that arrived as an exception. A
batch in which some changes failed SHALL NOT be reported as one failure: each
failed change SHALL keep its own reason next to it, and each change that worked
SHALL have its reason cleared.

The wording MAY differ between surfaces. What may not differ is which failures
are shown and where.

**Rationale:** the same change failed on one surface and succeeded on another,
and the user could not tell which had happened: one surface read its error
events and the others did not. A feature whose surfaces disagree about failure
is a feature the user cannot trust.

#### Scenario: The same change on two surfaces

- **Given** a change that cannot be applied
- **When** it is applied on the review screen and on the task detail
- **Then** the reason is shown next to the change on both
- **And** a failure that arrives as an exception is shown on both

#### Scenario: A batch where some changes failed

- **Given** a proposal with three changes, one of which cannot be applied
- **When** the user applies them all
- **Then** the failed change keeps its own reason next to it
- **And** the two changes that worked have no reason
- **And** the batch is not reported as a single failure
