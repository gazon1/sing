# task-series

## MODIFIED Requirements

### Requirement: REQ-TS-001

The system **SHALL** let a user schedule one piece of work on several dates, and
**SHALL** create one task row per date, all sharing one series identity.

A single **picked** date, on the other hand, is an ordinary task and must stay one
— the feature must not make every task a series. A single date **together with a
repeat rule** is the one exception: the rule itself describes several dates, so the
row becomes the current occurrence of a recurring series, and the rule's future
dates become further members of that series.

**Rationale:** some work is not one occurrence. Three dates of one obligation are
three rows of one job, and the user must be able to say so. A single date, on the
other hand, is an ordinary task and must stay one — the feature must not make every
task a series.

A repeat rule is the case that sentence did not cover. A rule says "this work
happens again", and the dates it produces are dates the user asked for even though
the user did not pick them one by one. A row that carries such a rule cannot stay a
single row and keep its meaning: the row that was closed and dated in the future was
the attempt to make it fit, and that attempt is what made the repeat feature
invisible. The rule's dates become rows of their own.

**Test coverage:** the planner is covered by unit tests asserting the shape of the
plan: one date produces one row with no series id, several dates produce one row each
with the same id, and a date with a rule produces the current occurrence and the
rule's future occurrences as members of one series.

#### Scenario: One date is an ordinary task

- The user picks one date
- One task is created
- The task carries no series identity

#### Scenario: Several dates are one series

- The user picks three dates
- Three tasks are created
- All three carry the same series identity
- Each carries its own date

#### Scenario: A date with a rule is the first occurrence of a series

- The user picks one date and sets a weekly rule
- One task is created, carrying the rule
- The task is the current occurrence of a recurring series
- The series holds the rule's future dates as further members

---

### Requirement: REQ-TS-005

The system **SHALL** refuse a series of more than thirty dates, and **SHALL** tell
the user that it did.

The refusal belongs to a series of **picked** dates: the user is told that thirty is
the maximum, and the thirty-first date is not added. A **recurring series** **SHALL
NOT** refuse — its dates are produced by the rule, and a refusal would stop the
recurrence rather than bound it. A recurring series holds at most thirty
occurrences, and when another occurrence would be added the oldest closed occurrence
leaves the series and stays an ordinary closed task. The occurrence that leaves **SHALL
lose the rule** with the series id, as any occurrence that leaves a recurring series
does.

**Rationale:** five hundred tasks from one action is a way of loading the database,
not a feature. A silent cap would let the user believe they picked forty dates and
then discover that thirty survived.

A rule is not one action that picked many dates: it is a standing promise that the
work comes back. A refusal at the ceiling would make a daily task stop repeating
after thirty completions, and the user would watch the recurrence stop without being
told anything. The bound stays; what changes is what happens at it. The oldest closed
occurrence is the one that leaves the series — it is finished work, it is already out
of the active list, and it stays an ordinary closed task.

**Test coverage:** the planner test asserts that the thirty-first date is refused;
the series test asserts that a recurring series at the ceiling rotates rather than
refuses.

#### Scenario: Picking more than thirty days

- The user has picked thirty days
- The user taps another day
- The day is not added
- The user is told that thirty is the maximum

#### Scenario: A recurring series keeps recurring at the ceiling

- The user has closed thirty occurrences of a daily rule
- Another occurrence comes due
- The series still holds thirty occurrences
- The oldest closed occurrence has left the series and is still an ordinary closed task
- The occurrence that left carries no rule

---

### Requirement: REQ-TS-007

A repeat rule and a set of **picked** dates **SHALL NOT** be in force on the same
task, and choosing several dates **SHALL** drop the rule and say so.

A recurring series does not break this rule. Its dates are the rule's own occurrences
rather than dates the user picked, and each of its members carries the rule together
with its own date.

**Rationale:** a rule generates occurrences on its own. A row that carried both would
produce dates the user never picked, and the two intentions would quietly fight over
the same row.

The fight was real, and it was between two *intentions*: the dates the user picked one
by one, and the rule that stands in their place. A recurring series has only the
second intention, so there is nothing to fight — the rule produces the dates, the
dates belong to the rule, and no date was picked by hand.

**Test coverage:** the create view model test asserts that picking a second date drops
the rule and leaves the message on screen; the series test asserts that every member
of a recurring series carries the rule.

#### Scenario: A rule and several dates

- The user has set a repeat rule
- The user picks a second date
- The rule is dropped
- The user is told that the rule was dropped

#### Scenario: A recurring series carries the rule on every member

- The user has set a weekly rule on a task
- The series holds the current occurrence and the materialised future occurrences
- Every member carries the rule

## ADDED Requirements

### Requirement: REQ-TS-009 A series has one kind, and the kind is where its dates come from

A series **SHALL** be of exactly one kind, and the kind **SHALL** be decided by where
its dates come from:

- a **picked-dates series** — the user picked the dates;
- a **recurring series** — the dates are the occurrences of a repeat rule.

The two kinds **SHALL NOT** be mixed in one series: a member of a picked-dates series
**SHALL NOT** carry a rule, and a member of a recurring series **SHALL NOT** carry a
date the user picked by hand. The join of two series **SHALL NOT** mix them either: a
recurring series and a series of picked dates **SHALL NOT** be joined, and two
recurring series whose rules differ **SHALL NOT** be joined. The behaviour of the
occurrences themselves — how they are materialised, how they close, and what a row
says — is specified by the `tasks` capability.

**Rationale:** the two kinds come from two different intentions, and the older rule —
"a series never holds a rule" — was right about the first kind and wrong about the
second. A series of picked dates describes work that happens on those dates; a rule on
such a row would produce dates nobody asked for. A recurring series is the other kind:
its dates *are* the rule's occurrences, so the rule is not in addition to the dates,
it is what they are.

**Test coverage:** the planner test asserts that a draft with several dates produces
members without a rule, and that a draft with a rule produces members with it.

#### Scenario: The kind follows the dates

- The user picks three dates
- The series is a picked-dates series
- Its members carry no rule

#### Scenario: A rule makes a recurring series

- The user sets a weekly rule on a task
- The series is a recurring series
- Every member carries the rule

#### Scenario: The kinds are not joined into one series

- The user joins a recurring series with a series of picked dates
- The two series stay two
- The user is told that the kinds differ

---

### Requirement: REQ-TS-010 A recurring series carries the rule on every member

Every member of a recurring series **SHALL** carry the rule, and **SHALL** carry the
same rule as every other member of that series. Editing the rule on one member
**SHALL** edit the rule of the whole series, **SHALL** materialise the members the
new rule gives and **SHALL** remove the open members it does not give. The members
that are already closed **SHALL** stay closed.

**Rationale:** the rule is what makes a recurring series recurring. A rule that lived
on one row alone would be lost the moment that row was closed, and the series would
stop — which is the defect this change is about. A rule on every member also means
that the mark which says "this repeats" can be derived from the row's own data
instead of a flag that nothing sets.

The shared-field rule (REQ-TS-002) already says that the recurrence is written to
every member. What it does not say is what the rule *does* to the series: it re-plans
it. The dates the old rule gave stop being dates of this series, and the dates the
new rule gives become dates of it.

**Test coverage:** the series tests assert that every member carries the rule, and
that editing the rule re-plans the open members and leaves the closed ones.

#### Scenario: Every member carries the rule

- The user has a recurring series of three occurrences
- Every occurrence carries the weekly rule

#### Scenario: Editing the rule re-plans the series

- The user changes the rule of one occurrence to a monthly one
- Every occurrence carries the monthly rule
- The open occurrences the monthly rule gives are materialised
- The open occurrences the monthly rule does not give are removed
- The closed occurrence stays closed
