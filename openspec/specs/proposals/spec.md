# proposals Specification

## Purpose
The proposal's lifecycle and its typed vocabulary: a change the user may
edit before it is applied, a visible reason for a refusal, a status that
is what the proposal's changes say, and one vocabulary that survives the
database.

## Requirements

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

### Requirement: REQ-PROP-023 A change this build cannot read is not read as something else

A stored change that this build cannot read in full SHALL be shown to the user
as a change that cannot be applied, together with the reason it cannot be
applied. It SHALL NOT be read as a different change, and it SHALL NOT be applied.

A stored change written in the previous flat shape SHALL remain readable, and
SHALL read as it was written.

**Rationale:** the sealed format exists so that the model keeps the stored
shape honest. A reader that fills in a default for a field it does not know
invents a change the user never proposed — and a reader that tries one format
after another reads a change in the format it happens to fall back to. A change
the user sees as something else is a change nobody proposed.

#### Scenario: A newer build wrote a field this build does not know

- **Given** a change stored by a build that knew a field this one does not
- **When** the proposal is shown
- **Then** the change is on the card
- **And** the card says the change cannot be applied
- **And** the reason says the change was written by another version of the app

#### Scenario: A change written in the previous flat shape

- **Given** a change stored in the flat shape, by a build before this one
- **When** the proposal is shown
- **Then** the change reads as it was written
- **And** it is not shown as a change that cannot be applied

#### Scenario: The stored payload is not a change at all

- **Given** a stored payload that is not a change in any shape
- **When** the proposal is shown
- **Then** the change is on the card
- **And** the reason says the change is not stored in a form this version can read

### Requirement: REQ-PROP-024 A change says what it is about, and the route is checked

Every kind of change SHALL declare what it is about. The declaration SHALL be
part of the kind, and a change that declares a group it does not belong to
SHALL NOT compile. A change that joins a group SHALL be handled by that
group's plan, or the build SHALL NOT compile.

A change that cannot be applied by this build SHALL be refused before it is
claimed, and the refusal SHALL carry the reason the change carries.

**Rationale:** the routing used to be a list of questions asked about the
change in a place that had to be kept in step with the model by hand. A change
that answered none of the questions fell through to the branch that assumed a
task, and the mistake showed up at runtime, on a user's device, as a change
applied to the wrong thing.

#### Scenario: A kind is added to the model

- **Given** a new kind of change
- **When** it is declared
- **Then** the build fails until the kind says what it is about
- **And** the build fails until the group that handles it handles it

#### Scenario: A change this build cannot read

- **Given** a change this build cannot read
- **When** the user applies it
- **Then** it is refused before the change is claimed
- **And** the reason it carries is the answer
- **And** the change is still waiting for a decision

### Requirement: REQ-PROP-025 The reasons live with the decisions

The reason a change could not be applied SHALL be kept in one store, owned by
the thing the surfaces that show changes share. A surface SHALL show the
reasons that store holds, and SHALL NOT keep a store of its own.

**Rationale:** three surfaces showed the same reason in three stores. A reason
that lives in one place is a reason that is in the right place when the user
looks for it.

#### Scenario: Two surfaces show the same change

- **Given** a change whose last attempt failed
- **When** it is shown on two surfaces
- **Then** the reason is the same on both

### Requirement: REQ-PROP-026 A name is declared once and checked

The name of a field a change sets SHALL be known to the reader that reads it.
Where the name is declared in two places, the build SHALL fail if the two
disagree.

**Rationale:** a field added to a value and not to the reader does not read —
and the change becomes a value the user never asked for, with a reason that
blames the model for a field the model has.

#### Scenario: A field is added to a value but not to the reader

- **Given** a value that declares a field name
- **When** the build runs
- **Then** it fails, and says the reader does not know the name

### Requirement: REQ-PROP-027 A change says what the proposal is addressed to

Every kind of change SHALL declare the kind of target it is about. A change that
brings something into being SHALL declare what it brings into being.

The kind of target a proposal is addressed by SHALL be derived from its changes.
A caller SHALL NOT be able to state it separately, and a change offered an id of
a different kind of target SHALL be refused rather than written.

A proposal SHALL hold changes about one kind of target. A set of changes about
more than one SHALL be refused.

A stored kind of target this build does not know SHALL read as unknown, and the
proposal SHALL NOT be addressed to a task, a note, a project or a tag.

**Rationale:** the kind of target used to be written beside the change — a
second declaration of one fact, in thirteen places, with nothing checking the
two. A caller that wrote the wrong one wrote a proposal the confirmation screen
does not show: a change that was proposed and cannot be found. The derivation
removes the second copy, so there is nothing left to get wrong, and the check
against the id turns the remaining mistake into an error where it is made.

#### Scenario: A change is proposed against the thing it is about

- **Given** a change about a task
- **When** a proposal is built for it
- **Then** the proposal is addressed to that task
- **And** the kind of target is read from the change, not stated by the caller

#### Scenario: A change is offered the id of another kind of target

- **Given** a change about a note
- **When** a caller proposes it against a task id
- **Then** the proposal is refused
- **And** the refusal says what the change is about

#### Scenario: A proposal is built from changes about two things

- **Given** a set of changes about a task and about a project
- **When** a proposal is built from them
- **Then** the proposal is refused
- **And** the refusal says a proposal holds changes about one thing

#### Scenario: A row was written by a build that knew another kind of target

- **Given** a proposal row whose kind of target this build does not know
- **When** the proposal is read
- **Then** the proposal is not addressed to a task, a note, a project or a tag
- **And** it does not crash the screen that shows it

### Requirement: REQ-PROP-028 A status this build does not know is not a waiting one

A stored status this build does not know SHALL NOT be read as any status it does
know. It SHALL be neither waiting nor decided.

A change whose status this build does not know SHALL NOT be applied, and the
refusal SHALL name the status it was written with. It SHALL remain on the card
with that reason, and the user SHALL still be able to turn it down.

A stored status SHALL be written back exactly as it was found.

**Rationale:** the reader filled the gap — an unknown name became `Pending`,
which is the one status that says "this change can still be applied". A change
that had already been applied was therefore applied a second time. The same
status was read in two ways in two places: one reader filled the gap and the
other threw. One reader, one answer, and the answer for a name this build does
not know is that this build does not know it.

#### Scenario: A newer build wrote a status this one does not know

- **Given** a change whose stored status this build does not know
- **When** the proposal is shown
- **Then** the change is on the card
- **And** the card says the change cannot be applied, and names the status
- **And** the user can still turn it down

#### Scenario: The change is confirmed anyway

- **Given** a change whose stored status this build does not know
- **When** the change is confirmed
- **Then** nothing is applied
- **And** the refusal names the status the change was written with

### Requirement: REQ-PROP-029 The status of a proposal is what its changes say

The status of a proposal SHALL be derived from the status of its changes. It
SHALL NOT be stated separately, and the stored summary SHALL NOT be read into
the domain as the status of the proposal.

A surface SHALL NOT name the status of a proposal or of a change, and the domain
SHALL answer the questions a surface asks: what a decided change reads as, what
a proposed change is, and whether a proposal is still waiting for the user.

A surface that names a status SHALL fail the build.

**Rationale:** the summary row was written on a best-effort path, so it was
expected to be stale sometimes — and the review screen asked it which proposals
to show. Two questions, two sources, and the row answered the first while the
changes answered the second. Deriving the status removes the second source
rather than repairing the first, and asking the proposal puts the question where
the answer is.

#### Scenario: Every change of a proposal has been decided

- **Given** a proposal whose changes have all been decided
- **When** the review screen asks for what is waiting
- **Then** the proposal is not offered, whatever the stored summary says

#### Scenario: A change of a proposal is still undecided

- **Given** a proposal with a change that has not been decided
- **When** the review screen asks for what is waiting
- **Then** the proposal is offered

#### Scenario: A surface names a status

- **Given** a surface that imports the status of a proposal or of a change
- **When** the build runs the gates
- **Then** the build fails
- **And** the failure names the surface
