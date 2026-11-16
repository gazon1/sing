# Task health reports one answer, and says why

**capability:** `tasks` | **status:** implemented

This change adds requirements to the existing `tasks` capability rather than creating
a new one: the behaviour it settles — what "blocked", "overdue" and "stagnant" mean,
and that every surface agrees — is already stated there ("A task is blocked only by
an unfinished prerequisite"). What was missing is that the surfaces that *count*
those buckets must agree with each other and with the user's own threshold.

---

## ADDED Requirements

### Requirement: REQ-TH-001

Every surface that reports a task as overdue, blocked or stagnant **SHALL** classify
it by one rule, and a task that qualifies for several **SHALL** appear under exactly
one of them: overdue first, then blocked, then stagnant.

**Rationale:** the precedence is a product decision. It was implemented twice — once
for the statistics summary, once for the tools that describe a task to a reader — and
the two copies were free to drift, with nothing able to see the drift because they
agreed on the day they were written.

#### Scenario: A task with several problems is reported once

- **Given** a task that is past its due date, waits on an unfinished prerequisite, and has not been touched for a long time
- **When** any surface reports the task's health
- **Then** it reports the task as overdue, and as neither blocked nor stagnant

#### Scenario: Every surface reports the same bucket

- **Given** a set of open tasks, some overdue, some blocked, some idle, some fine
- **When** the statistics screen counts them and a reader asks the tools about the same tasks
- **Then** both report the same number of tasks in each of the three buckets

---

### Requirement: REQ-TH-002

The stored staleness threshold **SHALL** mean the same thing to every surface, and a
stored value that means "leave it at the default" **SHALL NOT** be read as a window
of zero days.

**Rationale:** an undated task is stagnant once it has been idle for longer than the
threshold. A zero-day window would make every undated task stagnant the moment it was
created, which is not a signal — so the setting distinguishes "unset" from "zero
days", and a surface that interpreted it differently reported a problem the user does
not have.

#### Scenario: The unset threshold falls back to the default

- **Given** the staleness threshold is stored as its "unset" value
- **And** an undated task has not been touched for forty days
- **And** another undated task has not been touched for five days
- **When** any surface reports their health
- **Then** only the forty-day task is reported as stagnant

#### Scenario: A configured threshold is used, not the default

- **Given** the staleness threshold is configured to thirty days
- **And** an undated task has not been touched for ten days
- **When** any surface reports its health
- **Then** the task is not reported as stagnant

---

### Requirement: REQ-TH-003

The window a task must be idle for to count as stagnant **SHALL** include its
boundary: a task idle for exactly the threshold is stagnant, and one idle for less is
not.

**Rationale:** the boundary is where an off-by-one hides, and a task the summary
counts but the list omits differs by exactly this case.

#### Scenario: Exactly the threshold

- **Given** the threshold is twenty-one days
- **When** a task has been idle for exactly twenty-one days
- **Then** it is reported as stagnant

#### Scenario: A moment under the threshold

- **Given** the threshold is twenty-one days
- **When** a task has been idle for twenty-one days less one millisecond
- **Then** it is not reported as stagnant

---

### Requirement: REQ-TH-004

A task's due date **SHALL** be judged by the day, not the instant: a task due
yesterday is overdue, one due today is not, and one due tomorrow is not.

**Rationale:** "overdue" is a statement about a calendar day. A rule that compared
instants would call a task overdue for part of its due day, which is the day the user
intended to do it.

#### Scenario: The due-date boundary

- **Given** today is a known date
- **When** three open tasks are due yesterday, today and tomorrow
- **Then** only the one due yesterday is reported as overdue

---

### Requirement: REQ-TH-005

The list opened by a health count **SHALL** contain the same tasks the count was made
from, under any configured threshold.

**Rationale:** a card that says "twelve" and opens a list of nine is a card the user
cannot trust, and the difference is invisible until they count the rows.

#### Scenario: The count and its list agree

- **Given** the staleness threshold is configured away from its default
- **When** the user opens the stagnant count
- **Then** the list holds the tasks that were counted, and no others

---

### Requirement: REQ-TH-006

A health count **SHALL** state the rule it was made with, so that the user can tell
why a task belongs to it and what opening it will show.

**Rationale:** the count and its label do not say what the bucket means. "Blocked"
reads like a status the user set rather than a dependency that is unfinished, and
"not touched in a while" is a threshold they configured and do not see restated.

#### Scenario: A count explains itself

- **Given** the health tab shows the overdue, blocked and stagnant counts
- **When** the user reads a count
- **Then** it states the condition a task had to meet to be counted

---

### Requirement: REQ-TH-007

A finished or discarded task **SHALL NOT** be counted as overdue, blocked or
stagnant, and **SHALL NOT** be counted as open work.

**Rationale:** the buckets describe work still to do. A finished task is not late,
not waiting and not neglected, and counting it as open inflates the list the user is
being asked to act on.

#### Scenario: Finished and discarded work is not a problem

- **Given** a finished task and a discarded task, both idle for a long time
- **When** any surface reports health
- **Then** neither is counted as a problem
- **And** neither is counted as open work
