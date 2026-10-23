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
