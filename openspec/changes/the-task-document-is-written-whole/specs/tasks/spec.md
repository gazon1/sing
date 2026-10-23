# Tasks — Observable Behavior

**capability:** `tasks` | **status:** proposed

---

## ADDED Requirements

### Requirement: A task document is applied whole

A task document consists of the row of the task, its tags, the links its
description implies, and the blocking dependencies it carries.

When a document is applied, the system SHALL write every part it carries, in
one unit of work: the row, the tags, the links and the dependencies either all
land or none of them do. A part the document does not carry SHALL NOT be
written at all — the document says nothing about it, and the part that is
there belongs to the device that has it.

The links are not a claim of their own: they are derived from the description
of the row that arrived, and the row is written whole. A document therefore
carries the links it carries through its description, and there is no second
claim about them to make.

**Rationale:** a document that arrived half-applied is worse than one that did
not arrive. The row would say the work has no links and no dependencies while
the rest of the database still says it has them, and nothing in the system
knows which of the two is true. One writer for the whole document makes "the
document was applied" a single fact rather than four.

The parts the document carries are the ones it speaks about. The wire carries
the fields the sending device knew about, so the absence of a part is not a
claim that the part is empty. What the absence means is stated by
`the-document-says-only-what-it-carries`.

#### Scenario: A row that arrives brings its tags with it

- **GIVEN** a task that carries a tag
- **WHEN** the row of that task arrives from another device
- **THEN** the tag is in the database

#### Scenario: A row that arrives brings the links its description implies

- **GIVEN** a task whose description links to another task
- **WHEN** the row of that task arrives from another device
- **THEN** the links of that task are in the database

#### Scenario: A row that arrives brings its dependencies with it

- **GIVEN** a task that depends on another task
- **WHEN** the row of that task arrives from another device
- **THEN** the dependency is in the database

### Requirement: A row of another profile is stored as it is

A device SHALL NOT write the parts of a document that belongs to a profile it
is not on. When a row of another profile arrives, the system SHALL write the
row and SHALL keep the links the row already carried, and SHALL NOT write its
tags, its links or its dependencies.

**Rationale:** the parts of a document live in cross-reference tables that are
scoped by the owner, and writing them would be a write to another account's
work. A row that is new on this device has no links to keep, and this device
does not derive them either — deriving them would be the same write.

#### Scenario: Another profile's row keeps the links it carried

- **GIVEN** a task of another profile whose row carries links
- **WHEN** the row of that task arrives
- **THEN** the row still carries the links it carried

#### Scenario: Another profile's row is not given the parts of a document

- **GIVEN** a task of another profile
- **WHEN** the row of that task arrives
- **THEN** this device writes the row and nothing else of that document

### Requirement: The document says what the dependencies are

The dependencies a document carries are the blocking ones — the edges the wire
carries. When a document is applied, the system SHALL replace the blocking
edges of the task with the ones the document carries, and SHALL leave the
edges of other kinds alone.

An edge that would close a cycle SHALL be refused on its own, and the rest of
the document SHALL still be applied.

A pair of tasks that already has an edge of another kind SHALL keep it, and the
document's blocking edge SHALL be added alongside it: the two relations say
different things about the pair, and the document only speaks about the
blocking one.

**Rationale:** the other kinds of edge — a task that follows up another, one
that duplicates another — are not in the document at all. Replacing them with
the document's edges would delete what the document never spoke about. And the
code that walks the graph assumes it has no cycles, so an edge that would close
one is refused rather than taken; refusing it alone keeps the rest of the
document from being lost with it.

#### Scenario: A dependency the document dropped is dropped

- **GIVEN** a task that depends on another task
- **WHEN** a document arrives that carries an empty set of dependencies
- **THEN** the task does not depend on the other task any more

#### Scenario: An edge of another kind survives the document

- **GIVEN** a task that follows up another task
- **WHEN** a document arrives that carries no dependency for it
- **THEN** the task still follows up the other task

#### Scenario: A document that would close a cycle still lands its row

- **GIVEN** two tasks that depend on each other
- **WHEN** a document arrives that would close the cycle
- **THEN** the row of the task is in the database and the cycle is not

### Requirement: A path that applies part of a document says so

A path that writes one field of a task SHALL NOT write the parts of the
document it was not given. The tags of a task are written by the paths that
know what they should be — the path that sets them, the path that writes a
batch, and the path that applies a document — and a path that applies a row
without them SHALL leave them alone.

**Rationale:** the task an edit path is given was read through `get()`, which
does not load the tags — they live in the cross-ref table. An empty tag list on
a task that was read is not a claim that the task has no tags; it is the
absence of a claim, and a writer that reads it as a claim strips the tags off
the task. A batch write is a different path: it is given the tags of the whole
batch, and it writes them.

#### Scenario: An edit leaves the tags of the task alone

- **GIVEN** a task that carries a tag
- **WHEN** the task is edited
- **THEN** the task still carries the tag

#### Scenario: The tags are changed by the path that knows them

- **GIVEN** a task that carries a tag
- **WHEN** the tags of the task are set to another tag
- **THEN** the task carries the other tag and no longer the first
