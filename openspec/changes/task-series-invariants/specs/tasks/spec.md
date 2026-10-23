# Tasks — Observable Behavior

**capability:** `tasks` | **status:** proposed

---

## ADDED Requirements

### Requirement: The rows of a series say one thing about the work

A series SHALL have the same value for every field that describes the work —
the title, the description, the project, the tags, the priority, the accent
color, the emoji, the estimate and the recurrence — on every one of its rows.

The system SHALL repair that agreement when a single row of a series is
written: the row that was written SHALL say what the work is, and the other
rows of the series SHALL be written with its shared fields.

The system SHALL NOT write the rows that already agree.

**Rationale:** "apply to all of them" is a sentence about the series. If the
rows already disagree, the sentence has no subject — and the disagreement can
come from a writer that is not the editor: an MCP tool renames one occurrence,
another device pushes a row with another title, a restore writes rows one by
one. Healing the series at the write is what keeps the sentence meaningful
without asking the user to open the right occurrence first.

#### Scenario: A renamed occurrence realigns the rest of the series

- **GIVEN** a series of three occurrences that share one title
- **WHEN** one occurrence is renamed
- **THEN** the other two occurrences carry the new title

#### Scenario: The rows that already agree are not written again

- **GIVEN** a series whose occurrences agree about the work
- **WHEN** one occurrence is completed
- **THEN** the other occurrences are not written

#### Scenario: The occurrence's own fields are not touched by the repair

- **GIVEN** a series of three occurrences on three dates
- **WHEN** one occurrence is renamed
- **THEN** the dates of the three occurrences are unchanged

#### Scenario: A write that carries no tags does not take the tags off the series

- **GIVEN** a series of three occurrences that carry a tag
- **WHEN** one occurrence is edited through a read that carries no tags
- **THEN** the other occurrences still carry the tag

### Requirement: A series write that is not one series is refused

The system SHALL refuse a batch write of a series whose members carry more than
one series id, and SHALL refuse a batch write of a series whose members carry
no series id at all. A refused write SHALL write nothing.

A batch write of one series SHALL align the batch to one story before writing
it, so a batch that had drifted apart is repaired rather than written as two
stories.

**Rationale:** two series ids in one batch is not a stale row — it is two
stories, and no rule can say which one the user meant. A write path that cannot
repair it must not write it.

#### Scenario: A batch that carries two series ids is refused

- **GIVEN** two series
- **WHEN** a batch write asks for both of them at once
- **THEN** the write is refused and nothing is written

#### Scenario: A batch that carries no series id is refused where a series is meant

- **GIVEN** two tasks that carry no series id
- **WHEN** they are written as a series
- **THEN** the write is refused

#### Scenario: A batch that drifted apart is written as one story

- **GIVEN** two occurrences of one series whose titles differ
- **WHEN** the series is written
- **THEN** both occurrences carry the same title afterwards

### Requirement: A row that arrives from another device realigns the local series to it

When a row that belongs to a series arrives from another device, the system
SHALL write it and SHALL realign the rows of that series that this device owns
to its shared fields, and SHALL push the rows that changed back.

The realignment SHALL be scoped by the user the incoming row belongs to: the
series it can be a member of is that user's series, and this device writes the
rows of the profile it is on. A row of another profile is stored as it is,
because this device does not rewrite the rows of a profile it is not on.

**Rationale:** a remote row that disagrees with the local series is not a
mistake — it is the other device being right about a change this device has not
seen yet. Refusing would break the device forever: the row would come back on
every pull. The last write wins, applied to the whole series, because the
series is the thing the user thinks in.

#### Scenario: An incoming row realigns the local series

- **GIVEN** a series of three occurrences with one title
- **WHEN** a row of that series arrives from another device with another title
- **THEN** the local occurrences carry the title of the incoming row

#### Scenario: The row that arrives is the truth about the work

- **GIVEN** a series whose occurrences disagree about the title
- **WHEN** a row of that series arrives from another device
- **THEN** the occurrences agree about the title afterwards

#### Scenario: Another user's row does not touch this profile's series

- **GIVEN** a row of another user that carries a series id
- **WHEN** the row arrives
- **THEN** the rows of this profile are not written

### Requirement: One date of a series can leave it

The system SHALL let the user take one occurrence of a series out of it. The
occurrence SHALL keep its date, its time, its completion state, its checklist and
its attachments, and SHALL lose the series id.

When the last occurrence leaves, the series SHALL be over: the remaining row
SHALL stand alone.

**Rationale:** a series is a decision the user makes once, and the decision can
be wrong — the second date needs another title, or is not this job at all.
Without the operation, that cost is paid silently on every series that was
slightly wrong.

#### Scenario: An occurrence leaves the series and keeps its own date

- **GIVEN** a series of three occurrences on three dates
- **WHEN** one occurrence leaves the series
- **THEN** that occurrence keeps its date and stands alone

#### Scenario: The series of two becomes a series of one

- **GIVEN** a series of two occurrences
- **WHEN** one occurrence leaves the series
- **THEN** the other occurrence stands alone and carries no series id

#### Scenario: The last occurrence leaves and the series is over

- **GIVEN** a series of one occurrence
- **WHEN** that occurrence leaves the series
- **THEN** it stands alone and carries no series id

### Requirement: Two series can be joined

The system SHALL let the user join two series. The series the user is looking
at SHALL keep its series id and its shared fields; the rows of the other series
SHALL take that id and those fields.

The dates of both series SHALL survive the join.

**Rationale:** the user is looking at one of the two series and says "this is
the same work". That series's title is the one they read, and its id is the one
their other devices know — so that series is the one that stays. Merging into
whichever series happens to come first would make the result depend on the sort
order.

#### Scenario: The series the user is looking at keeps its id

- **GIVEN** two series, and the user is looking at the first
- **WHEN** the two are joined
- **THEN** the joined rows carry the id of the first

#### Scenario: The joined rows take the fields of the series the user is looking at

- **GIVEN** two series with different titles
- **WHEN** the user joins them from the first one
- **THEN** every row of the joined series carries the title of the first

#### Scenario: The dates of both series survive

- **GIVEN** two series on two dates each
- **WHEN** the two are joined
- **THEN** the joined series has four occurrences on those four dates
