## MODIFIED Requirements

### Requirement: Two series can be joined

The system SHALL let the user join two series. The series the user is looking at SHALL
keep its series id and its shared fields; the rows of the other series SHALL take that
id and those fields.

The dates of both series SHALL survive the join.

The join SHALL NOT mix the two kinds of series. A **recurring series** and a series of
**picked dates** SHALL NOT be joined, and two recurring series whose rules differ SHALL
NOT be joined; the refusal SHALL name the reason. A join of two series of the same kind
— two series of picked dates, or two recurring series carrying the same rule — SHALL
succeed as before.

**Rationale:** the user is looking at one of the two series and says "this is the same
work". That series's title is the one they read, and its id is the one their other
devices know — so that series is the one that stays. Merging into whichever series
happens to come first would make the result depend on the sort order.

A series is one kind of thing, and the join is the one operation that could make it two.
Joining a recurring series with a series of picked dates would leave the joined series
holding both a rule and hand-picked dates; joining two recurring series with different
rules would leave its members disagreeing about the work — the one thing the rows of a
series may not do. Refusing is the smaller promise: the two series stay two, and the
user is told why.

**Test coverage:** the merge tests assert that two series of the same kind merge, that a
mixed pair is refused with a reason, and that two recurring series with different rules
are refused.

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

#### Scenario: A recurring series and a series of picked dates are not joined

- **GIVEN** a recurring series and a series of picked dates
- **WHEN** the user joins them
- **THEN** the two series stay two
- **AND** the user is told that the kinds differ

#### Scenario: Two recurring series with different rules are not joined

- **GIVEN** a weekly recurring series and a monthly one
- **WHEN** the user joins them
- **THEN** the two series stay two
- **AND** the user is told that the rules differ

---

### Requirement: A recurring close is refused while a prerequisite is open

Completing one occurrence of a recurring task SHALL be refused while one of the
prerequisites that block it is still open. The refusal SHALL be the write itself, and
it SHALL leave the series unchanged: the occurrence is not closed, the series does not
advance to its next date, and no copy of a missed occurrence is written.

When the close of an occurrence is refused, the app SHALL ask the user, naming the
prerequisites that are in the way, and SHALL write nothing until the user confirms. On
confirmation the occurrence SHALL be closed and the series SHALL advance, even though
the prerequisite is still open.

A series advances by materialising, not by moving: the closed occurrence keeps its own
date, and the next occurrence is a new row of the same series.

**Rationale:** the refusal has to be the write itself. A close that is asked about and
then refused must leave nothing behind — not a closed occurrence, not a moved date, not
a written copy of a missed occurrence — or the user is left with a series they never
agreed to.

The refusal used to say that the series "moves to its next date", which meant that the
closed row changed its date. That is the write that produced a row closed and dated in
the future, and the row that carried it forward is the defect this change is about. The
series still advances; what advances it is a new row, and the closed one stays where it
was closed.

The scenario heading that says the series "moves" is kept **verbatim**, and its body says
what "moves" now means. The heading could not be renamed: the validator compares a
modified requirement against the current one and reports a scenario that is gone, and a
renamed heading is a scenario the current spec does not have.

**Test coverage:** the recurring-close tests assert that a refused close writes
nothing, and that a confirmed close closes the occurrence and materialises the next one
without moving the closed one.

#### Scenario: A recurring close is refused while a prerequisite is open

- **Given** a recurring task with a prerequisite that blocks it
- **And** that prerequisite is still open
- **When** the user asks for the occurrence to be closed
- **Then** the occurrence is not closed
- **And** the series does not advance
- **And** the user is asked, and the prerequisites in the way are named

#### Scenario: Confirming the refusal closes the occurrence and moves the series

- **Given** a recurring task with an open prerequisite that blocks it
- **And** the user was asked and confirmed
- **When** the occurrence is closed
- **Then** the occurrence is closed
- **And** the series advances to its next date: the closed occurrence keeps its own date,
  and the next occurrence is materialised as a new row

#### Scenario: A refused recurring close writes no copy of a missed occurrence

- **Given** a recurring task in catch-up mode with a prerequisite that blocks it
- **And** that prerequisite is still open
- **When** the user asks for the occurrence to be closed
- **Then** no copy of a missed occurrence is written
- **And** the series still holds the occurrence that was asked about

---

### Requirement: One date of a series can leave it

The system SHALL let the user take one occurrence of a series out of it. The occurrence
SHALL keep its date, its time, its completion state, its checklist and its attachments,
and SHALL lose the series id.

An occurrence that leaves a **recurring series** SHALL also lose the rule. The
remaining members SHALL keep the rule, and the series SHALL continue from the members
that remain.

When the occurrence that leaves was the last other member of a **recurring** series, the
row that stays behind SHALL lose the rule as well: a row that stands alone and carries a
rule is a shape the launch repair would adopt into a new series, and it must not be
produced.

When the last occurrence leaves, the series SHALL be over: the remaining row SHALL stand
alone.

**Rationale:** a series is a decision the user makes once, and the decision can be wrong
— the second date needs another title, or is not this job at all. Without the operation,
that cost is paid silently on every series that was slightly wrong.

The rule is part of what an occurrence claims about itself: "this happens again, as one of
these dates". An occurrence that left the series while keeping the rule would keep a
promise nothing keeps — it would carry the mark that says it repeats, and it would never
repeat. The other members are untouched by this: their rule belongs to the series, not to
the occurrence that left.

The row that stays behind when the series ends is in the same position. It stands alone,
and a rule on a row that stands alone is a promise nothing keeps either — and a shape the
launch repair would adopt into a new series, which would undo the detach at the next
launch.

The claim the row makes on the wire follows the row: a detached occurrence carries no
rule, so the document that carries it carries `seriesId: null` together with
`recurrence: null`. The sync format does not change; two claims travel together, and the
device on the other side clears both.

**Test coverage:** the detach test asserts that an occurrence that leaves a recurring
series carries no rule, that the remaining members still carry it, and that the row that
stays behind when the series ends carries no rule either.

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

#### Scenario: An occurrence that leaves a recurring series stops repeating

- **GIVEN** a recurring series with three occurrences
- **WHEN** one occurrence leaves the series
- **THEN** that occurrence carries no rule
- **AND** the remaining occurrences carry the rule

#### Scenario: The row that stays behind carries no rule

- **GIVEN** a recurring series of two occurrences
- **WHEN** one occurrence leaves the series
- **THEN** the occurrence that stays behind stands alone and carries no rule
- **AND** the next launch does not grow a new series around it

## ADDED Requirements

### Requirement: A repeating task's future is visible as real tasks

When a task carries a repeat rule, the app SHALL materialise the rule's future
occurrences as separate tasks of the same series, each with its own due date, the same
work — title, project, tags, priority and accent colour — and the same rule. The
materialised occurrences SHALL appear in the Upcoming agenda in the section of their own
date, and each SHALL be openable, editable, completable and deletable on its own.

**Rationale:** a repeating task was one row that moved. A row cannot hold a future — it
can only be somewhere else, and wherever it is, the previous occurrence is gone. The
user completed "buy bread — every week" and saw nothing new, because the row that
should have carried the next week was closed and dated in the future.

#### Scenario: A rule produces occurrences the user can see

- **GIVEN** a task with a weekly rule, due today
- **WHEN** the rule is saved
- **THEN** the task itself is the current occurrence, on today's date
- **AND** the series holds further occurrences, dated in the future, carrying the same
  title, project, tags and priority
- **AND** those occurrences appear in the Upcoming agenda in the section of their own
  date

#### Scenario: An occurrence is an ordinary task

- **GIVEN** a materialised future occurrence
- **WHEN** the user opens it
- **THEN** it opens, edits, completes and deletes like any other task

#### Scenario: A closed occurrence is not shown as an open future task

- **GIVEN** a series with a closed occurrence
- **WHEN** the Upcoming agenda is drawn
- **THEN** the closed occurrence is not presented as an open future task

### Requirement: Closing an occurrence leaves it on its date

When the user closes an occurrence of a repeating task, the app SHALL close that
occurrence and SHALL leave its due date unchanged, and SHALL materialise the next
occurrence as a new task of the same series. The app SHALL NOT move a closed
occurrence's date, and SHALL NOT leave a task that is both closed and dated in the
future.

**Rationale:** the old roll-forward closed the row and then moved its date, and the
move never touched the closed state — so the row was closed *and* dated in the future.
It left "Today" and came back in "Upcoming" struck through, and the next occurrence
never existed as an open task.

#### Scenario: The closed occurrence stays where it was

- **GIVEN** a repeating task due today
- **WHEN** the user closes the current occurrence
- **THEN** the occurrence stays closed on today's date
- **AND** its due date is unchanged

#### Scenario: Closing does not shrink the future

- **GIVEN** a series with open occurrences in the future
- **WHEN** the user closes the current occurrence
- **THEN** the number of open future occurrences of the series does not decrease

#### Scenario: A refusal changes nothing

- **GIVEN** an occurrence that has an open prerequisite
- **WHEN** the user tries to close it
- **THEN** the close is refused
- **AND** no occurrence is closed, none is created, and no date moves

#### Scenario: A failed close leaves nothing behind

- **GIVEN** a series of occurrences
- **WHEN** the write that closes an occurrence and materialises the next one fails
- **THEN** the series is exactly what it was — no half-built series

#### Scenario: A missed occurrence is a closed occurrence, dated in the past

- **GIVEN** a catch-up rule whose occurrence was left undone for three periods
- **WHEN** the user closes it
- **THEN** the missed occurrences exist as closed rows dated in the past
- **AND** they are not materialised twice on a retry

#### Scenario: One occurrence closes without closing the others

- **GIVEN** a series with several open occurrences
- **WHEN** the user closes a future one
- **THEN** the earlier occurrences stay open

#### Scenario: A shared change reaches the whole series

- **GIVEN** a series of occurrences
- **WHEN** the user renames one of them
- **THEN** every occurrence carries the new name

#### Scenario: Removing the rule stops the series growing

- **GIVEN** a series with future occurrences
- **WHEN** the user removes the rule
- **THEN** the series stops growing
- **AND** the existing occurrences stay where they are

#### Scenario: The bases differ only in the date

- **GIVEN** a repeating task closed under each of the three bases
- **THEN** the occurrence is closed and unmoved in every case
- **AND** only the date of the next occurrence differs between the bases

### Requirement: A rule set on a created task survives the create

When the user creates a task with a repeat rule, the app SHALL carry the rule onto the
row it creates, and SHALL materialise the series the rule describes. When the draft
carries a repeat rule **and** picked dates, the app SHALL drop the rule and say so.

**Rationale:** the create path went through the series planner, which cleared the rule
on every row it wrote — including the single row of a one-date draft. The draft carried
the rule and the planner dropped it on the way to the row, so a task created with
"repeat every week" came out with no rule at all, silently. Only the task screen's
edit path ever produced a repeating task.

#### Scenario: A created repeating task repeats

- **GIVEN** a draft with a weekly rule and today's date
- **WHEN** the task is created
- **THEN** the created row carries the rule
- **AND** the series holds the materialised future occurrences

#### Scenario: A rule together with picked dates is dropped and said so

- **GIVEN** a draft with a repeat rule and a second date
- **WHEN** the user adds the second date
- **THEN** the draft carries no rule
- **AND** the user is told the rule was dropped

### Requirement: A rule that has run out closes the occurrence and stops the series

When the next date of a rule falls past the rule's end date, the app SHALL close the
occurrence, SHALL materialise no further occurrence, and SHALL NOT archive anything.
The occurrence SHALL remain a closed task on its own date.

**Rationale:** the series used to be soft-deleted when its rule ran out, and the row
went to the trash. That was right when the single row *was* the series. Under
materialisation the series is over precisely when no further occurrence is
materialised, and a closed occurrence is already out of the active list — archiving a
row the user closed would move finished work into the trash, which is a different thing
from ending a schedule.

#### Scenario: The last occurrence closes and nothing is archived

- **GIVEN** a repeating task whose rule ends before its next occurrence
- **WHEN** the user closes the current occurrence
- **THEN** the occurrence is closed on its own date
- **AND** the series holds no further occurrence
- **AND** nothing was archived

### Requirement: A rule that measures from completion shifts the window when the close is late

The app SHALL measure a "from completion" rule from the date the occurrence was
closed, and a "from due date" rule from the occurrence's due date. The catch-up rule
SHALL measure from the occurrence's due date, because it fills the gaps between
*scheduled* occurrences. Closing an occurrence late SHALL therefore move the window of
a "from completion" series by exactly how late the close was, and SHALL NOT move the
window of a "from due date" or catch-up series at all. The app SHALL apply that move as
a change of date on the rows that remain, and SHALL NOT discard their own state.

**Rationale:** the default base measures from the completion date, so the window of the
common case depends on when the user acts. The move is the base's meaning and not a
defect; rebuilding the rows instead of moving them would lose the per-occurrence
reminders and checklists of every future row on every late close.

#### Scenario: A late close moves a "from completion" series

- **GIVEN** a weekly "from completion" rule, planned from a due date
- **WHEN** the occurrence is closed three days late
- **THEN** the window moves by three days
- **AND** the rows that remain keep their own state

#### Scenario: A late close does not move a "from due date" series

- **GIVEN** a weekly "from due date" rule
- **WHEN** the occurrence is closed three days late
- **THEN** the next occurrence is still due on the date the rule gives
- **AND** the window does not move

#### Scenario: A late close does not move a catch-up series

- **GIVEN** a weekly catch-up rule
- **WHEN** the occurrence is closed three days late
- **THEN** the missed occurrences are dated from the schedule
- **AND** the window does not move

### Requirement: A repeating task's window does not move on its own

The set of future occurrences of a repeating series SHALL be determined by the rule and
by the series' position, and SHALL NOT change because the calendar day changed. The
app SHALL NOT need a scheduled job or a write on a read path to keep the window
current.

**Rationale:** a window measured from *today* makes the row set a function of the date,
so something has to notice the day change and write — a daily job that may not have run
before the user opens the app, or a read that mutates the database.

#### Scenario: The same rule at the same position gives the same dates

- **GIVEN** a rule and a series position
- **WHEN** the window is planned twice
- **THEN** both plans give the same dates

#### Scenario: A rare rule still has a next occurrence

- **GIVEN** a task with a yearly rule
- **WHEN** the window is planned
- **THEN** the series holds the next year's occurrence

#### Scenario: A dense rule stays bounded

- **GIVEN** a task with a daily rule
- **WHEN** the window is planned
- **THEN** the series holds at most ten future occurrences

### Requirement: A recurring series rotates at the ceiling rather than refusing

A repeating series SHALL hold at most thirty occurrences. When it reaches that ceiling
and another occurrence would be added, the app SHALL remove the oldest closed
occurrence from the series and keep it as an ordinary closed task, rather than refusing
to grow. The occurrence that leaves SHALL lose the rule with the series id, exactly as
an occurrence the user takes out of the series does.

**Rationale:** the ceiling was a refusal, and for thirty picked dates it still is — that
is what the user asked for. For a recurring series a refusal would stop the
recurrence, so a daily task would stop repeating after thirty completions.

The rule has to go with the series id here too. A closed row that keeps its rule and has
no series id is exactly the shape the launch repair adopts into a new series, so a rule
that stayed would make the ceiling a loop: the repair would grow a fresh series around
the rotated row at the next launch.

#### Scenario: The series keeps recurring

- **GIVEN** a series at the ceiling of thirty occurrences
- **WHEN** one more occurrence is completed
- **THEN** the series still holds thirty occurrences
- **AND** the oldest closed occurrence has left the series and is still an ordinary
  closed task
- **AND** it carries no rule

#### Scenario: The rotated occurrence is not adopted back into a series

- **GIVEN** a series at the ceiling of thirty occurrences
- **WHEN** one more occurrence is completed
- **THEN** the rotated occurrence carries no rule
- **AND** the next launch does not grow a new series around it

### Requirement: Existing repeating tasks are repaired at launch

The app SHALL, at launch, turn a task that carries a repeat rule and belongs to no
series into a series with its window materialised. It SHALL reopen that task only when
the series would otherwise have no open occurrence, and SHALL leave a repeating task
that was closed in the past closed. The repair SHALL cover the active profile; another
profile's legacy repeating tasks SHALL be repaired at the next launch with that profile
active.

**Rationale:** the row the old roll-forward left closed with a future date is the row
the user is complaining about, and it is the current occurrence of the series it should
become. A repeating task closed in the past is history, and reopening it would
resurrect finished work.

#### Scenario: The rolled-forward row becomes the current occurrence

- **GIVEN** a task with a rule, closed, dated in the future, belonging to no series
- **WHEN** the app launches
- **THEN** the task belongs to a series and is open
- **AND** the series holds the materialised future occurrences

#### Scenario: History stays closed

- **GIVEN** a task with a rule, closed, dated in the past
- **WHEN** the app launches
- **THEN** the task stays closed

#### Scenario: The repair is idempotent

- **GIVEN** a repaired database
- **WHEN** the app launches again
- **THEN** nothing changes and no occurrence is duplicated

#### Scenario: Another profile is repaired at its next launch

- **GIVEN** a second profile with a legacy repeating task
- **WHEN** the app is launched with that profile active
- **THEN** that profile's legacy repeating task is repaired

### Requirement: A list row says that the task repeats

A task row in a list SHALL carry a repeat badge when the task's own rule is present, and
SHALL NOT carry one when the task has no rule. The badge SHALL be derived from the
task's own data rather than from a separately maintained flag, and it SHALL be shown
even when the row has nothing else to show.

**Rationale:** the row already drew a repeat icon, and nothing ever set the flag it
draws from — the agenda built every row with the flag hardcoded to "no". The icon had
never been drawn by anything.

#### Scenario: A repeating task's row shows the badge

- **GIVEN** a task row whose task carries a repeat rule
- **WHEN** the list is rendered
- **THEN** the row carries a repeat badge

#### Scenario: A one-off task's row does not

- **GIVEN** a task row whose task carries no rule
- **WHEN** the list is rendered
- **THEN** the row carries no repeat badge

#### Scenario: Every occurrence says it repeats

- **GIVEN** a series of occurrences
- **WHEN** any of them is rendered
- **THEN** every one of them carries the repeat badge

#### Scenario: The badge shows on an otherwise empty row

- **GIVEN** a repeating task with no due date, no project, not blocked and not in a
  series
- **WHEN** the list is rendered
- **THEN** the row still shows the repeat badge

### Requirement: A list row names the task's project

A task row in a list SHALL carry a project badge when the task belongs to a project, and
the badge SHALL carry the project's name and the project's colour. A task without a
project SHALL carry no project badge, and a task whose project has been closed SHALL
still carry one. The project's name and colour SHALL be resolved once per list build,
not once per row.

**Rationale:** the project's name never reached the row, so every agenda row with a
due date printed the literal "Без проекта" next to it. A badge carrying the project's
colour makes the projects distinguishable at a glance, which is the part a name alone
cannot do.

#### Scenario: The badge names and colours the project

- **GIVEN** a task row whose task belongs to a project with a colour
- **WHEN** the list is rendered
- **THEN** the row carries a project badge with the project's name and the project's
  colour

#### Scenario: A task without a project has no badge

- **GIVEN** a task row whose task belongs to no project
- **WHEN** the list is rendered
- **THEN** the row carries no project badge

#### Scenario: A closed project still names itself

- **GIVEN** a task row whose task belongs to a project that has been closed
- **WHEN** the list is rendered
- **THEN** the row still carries a project badge with that project's name

#### Scenario: The placeholder text is gone

- **GIVEN** any task row
- **WHEN** the list is rendered
- **THEN** the row does not print a placeholder where a project's name would be
