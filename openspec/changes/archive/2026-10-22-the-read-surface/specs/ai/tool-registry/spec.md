# The read surface speaks the app's language — Observable Behavior

**capability:** `ai/tool-registry` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-TOOLREG-007

A reader **SHALL** be able to ask for a named set of the user's tasks, and the set
**SHALL** contain what the app's own screen for that set contains.

**Rationale:** the reader and the app are looking at the same tasks. If the tool and
the screen disagree about what "overdue" means, the reader has to decide whom to
believe, and the app is the one that already has an answer. One rule, two directions.

#### Scenario: The set the tool returns is the set the screen shows
- The user asks for the overdue tasks
- The tool returns the tasks that are past their due date and still open
- The tool returns no task that is finished, and no task in the trash

#### Scenario: The vocabulary is offered, not guessed
- The tool's schema lists every named set it accepts
- The names in the schema are the names the tool accepts

---

### Requirement: REQ-TOOLREG-008

Narrowing a set **SHALL** be an intersection: a task is returned **only** if it
satisfies the selection and every narrowing the reader asked for.

**Rationale:** each word in a request removes something, and the reader's words are
about removing. "My tasks in this project, tagged home" is the intersection, not the
union — and the other reading is one a reader would notice only by being wrong.

#### Scenario: Two tags mean both tags
- The reader asks for the tasks tagged "home" and "errand"
- Only the tasks carrying both tags are returned

#### Scenario: A project narrows the selection
- The reader asks for the inbox within one project
- Only that project's tasks are returned

#### Scenario: A priority is matched without regard to case
- The reader asks for the "high" priority
- The tasks the app calls High are returned

---

### Requirement: REQ-TOOLREG-009

A read **SHALL** say how many tasks the selection holds, and **SHALL** hand back the
next page rather than everything at once.

**Rationale:** a list that arrives in pieces is a list a reader can work through. A list
that arrives in one piece is a wall, and the reader cannot tell where the wall ends.

#### Scenario: The reader knows there is more
- The selection holds more tasks than the ten the page holds
- The answer says how many tasks the selection holds
- The answer carries a cursor for the next page

#### Scenario: The reader continues where they stopped
- The reader hands the cursor back
- The next page starts after the page they have seen

#### Scenario: The last page says so
- The page reaches the end of the selection
- The answer carries no cursor

#### Scenario: The page is ten tasks
- The reader asks for a selection without naming a size
- The page holds ten tasks

---

### Requirement: REQ-TOOLREG-010

A cursor **SHALL** name the selection it came from, and a cursor handed over for a
different selection **SHALL NOT** be used.

**Rationale:** a cursor is a promise about a list. Used against a different list it
would hand the reader a page of something else, and the reader would have no way of
knowing — the page looks exactly like a page. So the cursor says what it is about, and
a mismatch is said out loud rather than quietly obeyed.

#### Scenario: A cursor from another selection is refused
- The reader hands back a cursor from a different selection
- The read starts from the beginning
- The answer says that the cursor was for a different selection

#### Scenario: A string that is not a cursor is refused
- The reader hands back something that is not a cursor
- The read starts from the beginning
- The answer says the cursor could not be read

---

### Requirement: REQ-TOOLREG-011

A read **SHALL** say how the selected tasks are doing: how many are overdue, how many
are blocked, how many have gone stale.

**Rationale:** "here are your 40 tasks" and "here are your 40 tasks, 9 of them overdue
and 3 waiting on something" are different answers, and only the second one tells the
reader what to do. The counts are the app's counts, so the reader is told the same
thing the statistics screen would tell them.

#### Scenario: The health of the selection is reported
- The selection holds an overdue task and a blocked task
- The answer says one task is overdue and one is blocked

---

### Requirement: REQ-TOOLREG-012

A call whose arguments do not fit the tool **SHALL** be answered with an error the
caller can read, and **MUST NOT** be reported as an internal failure.

**Rationale:** arguments that do not fit are the caller's mistake, and the caller can
correct one. Reporting it as an internal failure makes a typo in an argument look like
a broken server, which is the one thing the caller cannot do anything about.

#### Scenario: A value outside the accepted set is a readable error
- The caller asks for a set that does not exist
- The caller is told the arguments do not fit
- The error does not carry a stack trace

---

### Requirement: REQ-TOOLREG-018

A read **SHALL** say how much thinking a task asks for and whether it weighs on the
user more than it matters to them, and the two **SHALL** be reported separately.

**Rationale:** a task that takes four hours of thinking and matters enormously is not
the same as a task that takes four hours of thinking and matters not at all. One score
would hide exactly the distinction the reader needs. So the load is one measurement,
the value is another, and the verdict — a burden, or merely a big task — is a rule on
top of them, not a number that swallows both.

#### Scenario: The load grows with the size of the task
- The reader reads a task with a large estimate and many steps
- The answer says it asks for more thinking than a small task would

#### Scenario: The load does not follow the priority
- Two tasks have the same size and different priorities
- The answer gives them the same load

#### Scenario: A task nobody prioritised is not called a burden
- The task is old and heavy
- The user gave it no priority
- The answer does not call it a burden

#### Scenario: A blocked task that matters little is called a burden
- The task waits on something unfinished, has sat for a long time, and has a low priority
- The answer calls it a burden

---

### Requirement: REQ-TOOLREG-019

A read **SHALL** judge staleness by the threshold the user set, not by a threshold of
the tool's own.

**Rationale:** the user has a setting for how long a task may sit before it counts as
stale. A tool that applies its own threshold tells the reader a task is fine when the
user's own screen calls it stale — and the reader is left choosing between two
answers about the same task.

#### Scenario: The tool and the screen agree about staleness
- The user set a staleness threshold of seven days
- A task untouched for ten days
- The answer calls it stagnant, as the app's screen does
