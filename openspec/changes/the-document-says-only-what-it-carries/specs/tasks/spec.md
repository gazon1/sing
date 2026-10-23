# Tasks — Observable Behavior

**capability:** `tasks` | **status:** proposed

---

## ADDED Requirements

### Requirement: A document carries claims, and a claim can be absent

The tags and the dependencies of a task document SHALL be optional. A document
that does not carry them SHALL be read as saying nothing about them, and a
document that carries an empty collection SHALL be read as saying there are
none. The two SHALL NOT be read the same way.

**Rationale:** the wire carries the fields the sending device knew about. A
device that predates the tags sends no `tags` key, and reading that key as an
empty list would let it delete the tags of every task it writes. The claim
that is not made is not a claim that there are none.

#### Scenario: A document without the tags carries no claim about the tags

- **GIVEN** a task that carries a tag
- **WHEN** a document arrives that carries no tags
- **THEN** the task still carries the tag

#### Scenario: A document with no tags says there are none

- **GIVEN** a task that carries a tag
- **WHEN** a document arrives that carries an empty set of tags
- **THEN** the task carries no tag

#### Scenario: A document without the dependencies carries no claim about them

- **GIVEN** a task that depends on another task
- **WHEN** a document arrives that carries no dependencies
- **THEN** the task still depends on the other task

### Requirement: The absence is read before the decode

The receiving side SHALL read which parts a document carries from the document
itself, before it is decoded into a task. The names of the parts SHALL come
from the model of the task, so that a change of the wire name cannot leave the
reading behind.

**Rationale:** after the decode the two cases are the same task — the fields
the model cannot do without have defaults — and the difference between "no
tags" and "no claim about the tags" is gone. Reading it earlier is the only
place the difference still exists.

#### Scenario: A document that carries the tags says what they are

- **GIVEN** a task that carries a tag
- **WHEN** the document of that task arrives
- **THEN** the document says that the task carries the tag

#### Scenario: A document that does not carry the tags says nothing about them

- **GIVEN** a task that carries a tag
- **WHEN** a document arrives that has no tags
- **THEN** the document says nothing about the tags

### Requirement: A pair may carry several relations

A pair of tasks SHALL be allowed to carry more than one relation. The relations
are different claims about the same pair, and the document SHALL apply only the
one it speaks about: the blocking one.

**Rationale:** the cross-reference key carries the verb, so the relations of a
pair are separate facts. A task can wait for another one and follow it up, and
a document that says "this task waits for that one" says nothing about the
follow-up.

#### Scenario: A relation of another kind survives a document

- **GIVEN** a task that follows up another task
- **WHEN** a document arrives that says the task waits for the other one
- **THEN** the task still follows up the other task, and waits for it too

### Requirement: A repair follows the claim of the writer

When the system repairs the series around a row it has written, it SHALL apply
the shared fields the writer had a claim about, and SHALL leave the fields it
had no claim about alone. A document that carries no `tags` key SHALL NOT take
the tags off the other members of the series.

**Rationale:** the repair copies the source's shared fields onto the members,
and the source's tags are the ones the database holds. A row that is new on
this device holds none — and the row is new precisely because the document
arrived. Copying that emptiness onto the members would turn the absence of a
claim into a deletion one level further out, and the document never spoke
about the members at all.

#### Scenario: A document without a claim leaves the tags of the series alone

- **GIVEN** a series of two tasks, one of which carries a tag
- **WHEN** a document arrives for the other one that carries no tags
- **THEN** the task that carries the tag still carries it

### Requirement: A refused edge is said out loud
When the system refuses an edge of a document because it would close a cycle,
it SHALL record the refusal, and the rest of the document SHALL still be
applied.

**Rationale:** the refusal is a disagreement between two devices about the
shape of the work, and it repeats on every pull. A refusal nobody can see is
a device that silently disagrees with the device that sent it.

#### Scenario: A refused edge is recorded and the row lands

- **GIVEN** two tasks that depend on each other
- **WHEN** a document arrives that would close the cycle
- **THEN** the row of the task is in the database and the refusal is recorded

### Requirement: A batch write repairs the series, like an ordinary write

A batch write of tasks SHALL repair the series around it, the way an ordinary
write of one task does: the members that were not in the batch SHALL follow the
ones that were.

**Rationale:** a series is one piece of work, and the rows of a series are
copies of it. A write that changes one of the copies changes the work, and the
other copies follow. A batch write is that write, taken several times at once.

#### Scenario: A partial batch write repairs the rest of the series

- **GIVEN** a series of two tasks
- **WHEN** a batch write renames one of them
- **THEN** the other one carries the new name as well
