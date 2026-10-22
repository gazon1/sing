# Tasks — Observable Behavior

**capability:** `tasks` | **status:** proposed

---

## ADDED Requirements

### Requirement: A row of a series says what it is

A row whose task belongs to a series SHALL carry the mark of a series: the
icon, and — where the surface has room for a word — the number of dates the
series has.

The calendar's chip SHALL carry the mark in its compact form (the icon alone),
because a chip has no room for a word. The day's list SHALL carry the mark
together with the number of dates.

The number of dates SHALL be the number of occurrences that share the series id.

**Rationale:** a row of a series looks like a task, and nothing says that this
row and the row three days down are one piece of work. Without the mark the
user reads them as two things, and "apply to all of them" reads as a feature
of the row rather than as the truth about the work. The count is the number the
user needs when they ask "how much of this is left?".

#### Scenario: The day's list says how many dates the work has

- **GIVEN** a series of three occurrences
- **WHEN** the day's list shows one of the occurrences
- **THEN** the row carries the mark of a series and the number three

#### Scenario: The calendar's chip carries the mark

- **GIVEN** a task that belongs to a series
- **WHEN** the calendar shows the task
- **THEN** the chip carries the mark of a series

#### Scenario: A task that stands alone carries no mark

- **GIVEN** a task that belongs to no series
- **WHEN** the day's list shows it
- **THEN** the row carries no mark of a series

### Requirement: A deleted series can be put back

Deleting a series SHALL archive every occurrence of it and SHALL offer the way
back. The message SHALL say how many dates went away.

The undo SHALL restore exactly the occurrences that were archived — the ids it
archived, not the members the series has at the time of the undo.

After the deletion the screen SHALL navigate back.

**Rationale:** a series is several dates of one work, and the work is not what a
person throws away by accident. The undo restores the ids rather than the
series, because the series changes: a date added between the deletion and the
undo must not come back with the work, and a date removed must not stay away.

#### Scenario: Deleting a series archives every occurrence

- **GIVEN** a series of three occurrences
- **WHEN** the series is deleted
- **THEN** every occurrence is archived

#### Scenario: The undo says how many dates went away

- **GIVEN** a series of three occurrences
- **WHEN** the series is deleted
- **THEN** the message says that three dates were deleted

#### Scenario: The undo puts the occurrences back

- **GIVEN** a series of three occurrences that was deleted
- **WHEN** the undo is used
- **THEN** the three occurrences are no longer archived

#### Scenario: An occurrence added after the deletion stays away

- **GIVEN** a series of two occurrences that was deleted
- **WHEN** an occurrence joins the series and the undo is used
- **THEN** the two occurrences that were deleted come back and the new one stays

### Requirement: The statistics count works, not occurrences

The statistics SHALL count a series as one work. A work SHALL count as
completed on the day it was FIRST completed, and — while nothing of it is
completed — as overdue once, on the day of its EARLIEST missed date.

A work that has a completed occurrence SHALL NOT count as overdue.

**Rationale:** the statistics measure works, not rows. A series scheduled on
seven dates with three of them completed is one thing the user did, not three
things. The first completion is the day the work was finished; the later dates
are the same work carried on. And a work the user has started is not something
they have not done — it is something they are in the middle of.

#### Scenario: A series that was completed counts once

- **GIVEN** a series of three occurrences, all completed
- **WHEN** the statistics are computed
- **THEN** the work counts once, not three times

#### Scenario: The work is counted on the day it was first completed

- **GIVEN** a series with occurrences completed on three different days
- **WHEN** the statistics are computed
- **THEN** the work counts on the first of those days

#### Scenario: A series with nothing completed counts as overdue once

- **GIVEN** a series of three occurrences, none completed, all past due
- **WHEN** the statistics are computed
- **THEN** the work counts as overdue once, on the earliest of those days

#### Scenario: A work the user has started is not overdue

- **GIVEN** a series with one completed occurrence and one past-due occurrence
- **WHEN** the statistics are computed
- **THEN** the work counts as completed and not as overdue

#### Scenario: A task that stands alone counts as before

- **GIVEN** a task that belongs to no series
- **WHEN** the statistics are computed
- **THEN** the task counts as one work
