# Attention items are derived, ordered, and actionable

**capability:** `tasks` | **status:** in progress

This change adds requirements to the existing `tasks` capability rather than creating a
new one: the behaviour it settles — what "overdue", "blocked" and "due today" mean —
is already stated there, and the new list is a projection of those same rules rather
than a second definition of them.

---

## ADDED Requirements

### Requirement: REQ-TH-010

The app **SHALL** offer a list of attention items computed from the task and project
facts it already holds, and **SHALL NOT** keep that list anywhere: the list **SHALL**
be rebuilt from the current rows every time it is shown.

**Rationale:** an attention item is a statement about a row — it is late, it is due
soon, it is waiting, its project ran out of time. Storing the statement would create
a second version of the truth, which would disagree with the row it was derived from
after the first edit, and the app already holds a rule that derived values are never
stored.

#### Scenario: The list follows the rows

- **Given** a task that is due today
- **When** the attention list is shown
- **Then** the task is on it
- **And** when the task's due date is moved to next month, the list no longer names it

---

### Requirement: REQ-TH-011

An attention item **SHALL** state exactly one reason, and **SHALL** state the most
relevant one that applies to it: a task past its due date outranks one that is merely
due soon, which outranks one that is waiting, which outranks a project whose deadline
has arrived.

**Rationale:** a task can be late *and* waiting on something, and a project can have a
deadline *and* hold late tasks. Naming every reason makes the list long and tells the
user nothing about where to start; naming none makes it a list of accusations. One
reason, the most urgent one, is what makes the list a shortlist.

#### Scenario: One item, one reason

- **Given** a task that is past its due date and waits on an unfinished prerequisite
- **When** the attention list is shown
- **Then** the task appears once
- **And** the reason it gives is that it is past its due date

---

### Requirement: REQ-TH-012

The attention list **SHALL** be ordered: items that outrank others come first, and
items of the same kind **SHALL** be ordered by the date that makes them due, earliest
first, and by their own identity when the dates are the same.

**Rationale:** the list is a shortlist, so its order is the answer to "what first?".
An order that changed between two openings of the same screen would make the list
untrustworthy — the user would not know whether the item that moved up did so because
it became more urgent or because the list is not stable.

#### Scenario: The order is stable

- **Given** two tasks that are both past their due date, one due earlier than the other
- **When** the attention list is shown twice without any change to the tasks
- **Then** the earlier task is first both times

#### Scenario: The most relevant kind comes first

- **Given** a task that is waiting on an unfinished prerequisite and a task that is due
  today
- **When** the attention list is shown
- **Then** the task due today is above the task that is waiting

---

### Requirement: REQ-TH-013

The attention list **SHALL** name only work that is still to do: **SHALL NOT** name
finished work, discarded work, work the user has set aside for later, or work that
belongs to a project whose work was declared finished.

**Rationale:** the list is a set of things to act on, and each of those is work the
user has not finished. Work they deferred was parked on purpose, work in a finished
project is over, and finished or discarded work is not late — it is done.

#### Scenario: Work the user set aside is not named

- **Given** a task that is due today and has been set aside for later
- **When** the attention list is shown
- **Then** the task is not on it

#### Scenario: A finished project is not named

- **Given** a project whose work was declared finished, holding a task that is past its
  due date
- **When** the attention list is shown
- **Then** neither the project nor the task is on it

---

### Requirement: REQ-TH-014

"Due soon" **SHALL** mean due today or due tomorrow, judged by the day, and **SHALL**
include a task due today even when that task is also waiting on something.

**Rationale:** a shortlist needs a window, and the window has to be one the user can
predict. "Today and tomorrow" is the window the app already uses to group the next
two days of work, so a task that shows up on the agenda's Today list is a task that
appears on the attention list — and the two never disagree about what "today" means.

#### Scenario: The window is today and tomorrow

- **Given** three open tasks due today, tomorrow and the day after
- **When** the attention list is shown
- **Then** the tasks due today and tomorrow are on it
- **And** the task due the day after is not

#### Scenario: A due-soon task that is also waiting

- **Given** a task due today that waits on an unfinished prerequisite
- **When** the attention list is shown
- **Then** the task is on it
- **And** the reason it gives is that it is due soon

---

### Requirement: REQ-TH-015

A project **SHALL** be named by the attention list only when it has a deadline that
has arrived or passed, the project is still open, and at least one of its tasks is
unfinished.

**Rationale:** a deadline on a project is a promise about a body of work, not about
one row. It is worth attention while the promise is unmet — that is, while the work is
still open — and worth nothing once the work is finished, even if the date has passed.

#### Scenario: A deadline with work left

- **Given** an open project whose deadline has passed and which holds an unfinished
  task
- **When** the attention list is shown
- **Then** the project is on it
- **And** the reason it gives is that its deadline has passed

#### Scenario: A deadline with the work already done

- **Given** an open project whose deadline has passed and whose every task is finished
- **When** the attention list is shown
- **Then** the project is not on it

---

### Requirement: REQ-TH-016

Each item on the attention list **SHALL** name one task or one project, **SHALL** say
why it is there, and **SHALL** open that named item when the user selects it. The user
**SHALL** be able to set one item aside for now, and doing so **SHALL NOT** change the
task or the project in any way.

**Rationale:** a list that cannot be opened is a list to read, not a list to act on.
And a shortlist that cannot be trimmed is a list the user has to re-read every time:
without a way to say "not now", the same item at the top is the first thing they see
every morning. Setting an item aside is a statement about the shortlist, not about the
work, so it must not touch the work.

#### Scenario: Opening an item

- **Given** an attention item naming a task
- **When** the user selects it
- **Then** that task opens

#### Scenario: Setting an item aside changes nothing else

- **Given** an attention item naming a task
- **When** the user sets it aside for now
- **Then** the item is no longer on the list
- **And** the task itself is unchanged — it is still open, still has its due date, and
  still has its dependencies

#### Scenario: Setting an item aside does not survive leaving the screen

- **Given** an attention item the user set aside for now
- **When** the user leaves the screen and comes back to it
- **Then** the item is on the list again

---

### Requirement: REQ-TH-017

The attention list **SHALL** show a bounded number of items at a time, and **SHALL**
say how many items it did not show.

**Rationale:** the list exists to be a shortlist. A list of every late task in a
year's backlog is the backlog again, and the items below the fold are the ones the
user was never going to reach anyway. Saying how many are hidden keeps the list
honest: the user knows the shortlist is a shortlist.

#### Scenario: More items than the list shows

- **Given** more attention items than the list shows at once
- **When** the list is shown
- **Then** it says how many items it did not show
