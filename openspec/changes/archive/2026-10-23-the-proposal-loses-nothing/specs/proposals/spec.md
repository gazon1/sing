# The proposal loses nothing — Observable Behavior

**capability:** `proposals` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-PROP-017 A change this build cannot read stays on the card

A stored change that this build cannot read — a kind it does not know, or a
payload it cannot make sense of — SHALL be shown to the user as a change that
cannot be applied, together with the reason it cannot be applied. It SHALL NOT
be dropped from the proposal, and it SHALL NOT be applied.

The user SHALL be able to turn such a change down, like any other change.

A change that cannot be read SHALL keep the stored payload unchanged when it is
written back, so that a build which *can* read it finds the same change.

**Rationale:** the row was written by a build that knew something this one does
not. The user is the one who can decide about it — and a change that vanishes
from the card is a change nobody decided about.

#### Scenario: The kind is not one this build knows

- **Given** a proposal whose change was written by another version of the app
- **When** the proposal is shown
- **Then** the change is on the card
- **And** the card says the change cannot be applied
- **And** the user can turn the change down

#### Scenario: The change is applied anyway

- **Given** a proposal whose change this build cannot read
- **When** the user applies the change
- **Then** the change is not applied
- **And** the reason it cannot be read is the answer
- **And** the change is still waiting for a decision

### Requirement: REQ-PROP-018 The row a write creates is addressable before it is written

A write that creates rows SHALL know the identity of every row before the row
is written. When a write is cut short — by a failure or by a cancellation —
after the database has committed, the rows that were written SHALL be taken
back, and the take-back SHALL address them by the identity they were given
before the write.

A take-back of a row that is not there SHALL succeed: the goal of a take-back is
that the row is not there, and a row that was never created is not there.

**Rationale:** the write that is cut short is the one whose result never
arrives. If the identity of a row is the result of the write, the take-back has
nothing to address on exactly the path it exists for — and the row would be
there twice after the next attempt, which is the user's.

#### Scenario: The write committed and was then cut short

- **Given** a change that writes a row
- **And** the write commits and the screen goes away before the write returns
- **When** the change waits again
- **Then** the row that was written is not there
- **And** the next attempt writes it once

#### Scenario: The write failed before the row was created

- **Given** a change that writes a row
- **And** the write fails before the row exists
- **When** the failure is reported
- **Then** the change says the write failed
- **And** the change does not say that a row stayed behind

### Requirement: REQ-PROP-019 The claim is determined by the transaction

A change that is claimed for application SHALL be given back when the
application did not happen — including when the application was cut short after
the claim was made. A claim SHALL NOT stand for a change that was not written.

The fact that the claim stands SHALL be known at the moment the claim was made,
not afterwards.

**Rationale:** a standing claim is a change the user cannot decide about: it is
not waiting, and it is not applied.

#### Scenario: The screen goes away while the claim is in flight

- **Given** a change waiting for a decision
- **When** the screen goes away while the change is being claimed
- **Then** the change is waiting for a decision again
- **And** nothing was written

### Requirement: REQ-PROP-020 A surface does not decide what a proposal's state means

A surface that shows a proposal SHALL NOT name a proposal item's status. The
domain SHALL answer what a decided change reads as, what a change that was
proposed is, and what is still waiting, and the surface SHALL print the answer.

The wording of a proposal decision SHALL be declared in one place, and every
surface that words a decision SHALL take it from there.

**Rationale:** three surfaces draw the same card. A surface that names a status
is a surface that has opinions about the lifecycle, and the lifecycle is not the
surface's — so the opinions are three places to keep in step, and nothing keeps
them in step.

#### Scenario: The same change on three screens

- **Given** a decided change
- **When** the user sees it on the review screen, in the note editor and in the
      task detail
- **Then** it reads the same on all three

#### Scenario: A surface asks what is waiting

- **Given** a proposal with one waiting change and one decided change
- **When** the review screen asks what is waiting
- **Then** the answer is the waiting change

### Requirement: REQ-PROP-021 The reason a change failed lives in one place

The reason a change could not be applied SHALL be kept in one store, and every
surface that shows changes SHALL show the reasons that store holds. A reason
the user has not read yet SHALL survive the arrival of new data on the screen.

**Rationale:** the state a surface draws from is rebuilt from the data the
screen was given. A reason kept only in that state is lost on every rebuild —
and a reason the user did not read yet is still a reason.

#### Scenario: New proposals arrive while a reason is unread

- **Given** a change whose last attempt failed, with the reason on its card
- **When** another proposal arrives on the same screen
- **Then** the reason is still on the card

### Requirement: REQ-PROP-022 The stored change is the model's own shape

A stored change SHALL be written in the shape the model itself declares, so that
a field that changed in the model is a field that changed in what is stored. A
change stored in the previous flat shape SHALL remain readable.

**Rationale:** a payload written by hand, field by field, keeps a field the model
renamed under its old name — or stops writing it, and the value reads back as
something else. The user approved a value; a value that comes back as another
value is a change nobody decided about.

#### Scenario: A row written by an earlier build

- **Given** a change stored in the flat shape, by a build before this one
- **When** the proposal is shown
- **Then** the change reads as it was written

#### Scenario: A value survives the trip through the database

- **Given** any change this build knows
- **When** it is stored and read back
- **Then** it is the change that was stored
