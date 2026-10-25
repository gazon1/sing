# The proposal reads strictly — Observable Behavior

**capability:** `proposals` | **status:** accepted

---

## ADDED Requirements

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
