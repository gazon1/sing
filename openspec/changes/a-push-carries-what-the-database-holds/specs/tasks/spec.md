## ADDED Requirements

### Requirement: A push carries what the database holds

When the system pushes a task to the server, it SHALL push the task as the
database holds it — the row, the tags and the blocking edges — and SHALL NOT
push a part it did not read as the truth about the work.

**Rationale:** on the wire an empty collection is a claim. A document whose
`tags` is empty says "the work has no tags", and the receiver removes them; a
document whose `dependsOn` is empty says "the work waits for nothing", and the
receiver removes the edges. A task read through `get()` carries two empty
collections that are the truth about the *read* and not about the work, and a
device that sends them as claims strips the work of every other device on the
next pull. The write that pushes has to push what the database holds.

#### Scenario: An edit does not tell the server that the work has no tags

- **GIVEN** a task that carries a tag
- **WHEN** the task is edited through a read that does not load the tags
- **THEN** the pushed task carries the tag

#### Scenario: An edit does not tell the server that the work waits for nothing

- **GIVEN** a task that waits for another task
- **WHEN** the task is edited through a read that does not load the edges
- **THEN** the pushed task carries the edge

#### Scenario: A batch write does not tell the server that the work waits for nothing

- **GIVEN** a task that waits for another task
- **WHEN** the task is written as part of a batch built from a read that does
  not load the edges
- **THEN** the pushed task carries the edge

#### Scenario: A bulk archive does not tell the server that the work has no tags

- **GIVEN** a completed task that carries a tag
- **WHEN** the completed tasks are archived in bulk
- **THEN** the pushed task carries the tag

#### Scenario: The claim is read inside the transaction

- **GIVEN** a task that carries a tag
- **WHEN** the task is written
- **THEN** the task the push carries is the task the write left, tags included
