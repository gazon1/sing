# The proposal says what it is about, and reads strictly — Observable Behavior

**capability:** `proposals` | **status:** accepted

---

## ADDED Requirements

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
