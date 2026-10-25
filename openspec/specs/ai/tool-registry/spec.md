# ai/tool-registry Specification

## Purpose

The tools an assistant may be offered, and what the assistant is told when a tool
fails, does not exist, or keeps being asked for — and how that answer reaches the
reader while it is being produced.

## Requirements

### Requirement: REQ-TOOLREG-001

A conversation with the assistant **SHALL** offer the assistant the tools that
are available to it, and **MUST NOT** answer a question about the user's own
data without looking it up first.

**Rationale:** the assistant and the user are talking about the same tasks. An
answer that has not been looked up is not an answer about those tasks — it is an
answer about tasks in general, wearing their names. The difference is invisible
to the reader, which is exactly why it has to be structural rather than a
matter of the model's disposition.

#### Scenario: A question about the user's tasks is answered from the tasks
- The user asks the assistant which of their tasks are overdue
- The assistant looks the tasks up
- The answer names the tasks that are actually overdue

#### Scenario: A question that needs no lookup is answered directly
- The user asks the assistant how to write a good commit message
- No tool is called
- The assistant answers in words

---

### Requirement: REQ-TOOLREG-002

A tool the assistant asks for **SHALL** be run, and its answer **SHALL** reach
the assistant before the assistant answers the user.

**Rationale:** a lookup that does not come back is a lookup that did not
happen. The conversation has to carry the result of the call, and carry it in
the shape the model reads, or the model answers from the question alone — which
is what it would have done anyway.

#### Scenario: The answer to a call reaches the assistant
- The assistant asks for a tool
- The tool returns a result
- The next turn of the assistant sees that result

---

### Requirement: REQ-TOOLREG-003

The assistant's answer **SHALL** reach the reader while it is being produced,
and **MUST NOT** be held back until the whole answer is known.

**Rationale:** the reader watches a reply being written. Holding the text back
until the tools have run and the model has finished turns a conversation into a
request that returns at the end — and the tools make that temptation stronger,
because a turn now takes two passes.

#### Scenario: The text appears as it is produced
- The assistant answers
- The first words of the answer reach the reader before the last words exist

---

### Requirement: REQ-TOOLREG-004

A tool that fails, or a tool that does not exist, **SHALL** end the assistant's
turn with a readable explanation, and **MUST NOT** end the conversation.

**Rationale:** one broken tool is not a broken conversation. The model asked for
something and the honest answer is that it did not work — and the model is
exactly the party that can act on that answer, by asking differently or
answering without the lookup. Ending the conversation takes that chance away
and shows the reader an error they cannot do anything about.

#### Scenario: A call that fails does not end the conversation
- The assistant asks for a tool
- The tool fails
- The assistant is told that the call failed
- The assistant answers the user anyway

#### Scenario: A call to a tool that does not exist does not end the conversation
- The assistant asks for a tool that is not available
- The assistant is told that the tool is not available
- The assistant answers the user anyway

---

### Requirement: REQ-TOOLREG-005

An assistant that keeps asking for tools **SHALL** be given a last turn in which
no tool is offered, and the conversation **SHALL NOT** continue past it.

**Rationale:** a model that asks for a tool, gets an answer, and asks again
would otherwise ask until the user closes the app. The bound has to exist, and
it has to end in an answer rather than in silence: the user asked a question
and is owed words.

#### Scenario: The bound is reached and the assistant answers in words
- The assistant asks for a tool in every turn
- The turn limit is reached
- The assistant is given a turn in which no tool is offered
- The assistant answers in words and the conversation ends

#### Scenario: The last request of a bounded conversation is still honoured
- The assistant asks for a tool in the last turn that allows one
- The tool runs
- The assistant is given the turn in which it must answer in words

---

### Requirement: REQ-TOOLREG-006

A tool that has been switched off **SHALL NOT** be offered to the assistant, and
a request for it **SHALL** be answered as a request for a tool that does not
exist.

**Rationale:** the switch is the user's and the operator's way of saying "not
this one". A switch that is read but not honoured is worse than no switch: it
looks like a working control, and the thing it is supposed to stop keeps
happening.

#### Scenario: A switched-off tool is not offered
- A tool is switched off
- The assistant is offered the tools that are left
- The switched-off tool is not among them

#### Scenario: A request for a switched-off tool is a request for a tool that does not exist
- The assistant asks for a tool that is switched off
- The assistant is told that the tool is not available
- The assistant answers the user anyway

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

### Requirement: REQ-TOOLREG-013

A change an assistant proposes to a task **SHALL** be held as a proposal until the
user decides, and **MUST NOT** be applied by the tool that proposed it.

**Rationale:** the assistant reached for the change on its own initiative. The user did
not ask for it and has not seen it. A change that lands unseen is a change nobody
agreed to, and the fact that the tool reported it as done does not make it agreed to.

#### Scenario: A proposed change is not applied
- The assistant proposes a new due date for a task
- The task still has its old due date
- A proposal exists that the user can see and decide

#### Scenario: The assistant says what it did
- The assistant proposes a change
- The assistant says the change has been proposed
- The assistant does not say the task has been changed

---

### Requirement: REQ-TOOLREG-014

A proposal made by the assistant **SHALL** be the same kind of proposal the app makes
elsewhere, and **SHALL** be decidable by the user in the same place.

**Rationale:** a second kind of "pending change" is a second thing to keep right, and a
second place for the user to look. One kind of proposal, one screen, one apply path.

#### Scenario: The proposal is decided like any other
- The user opens the confirmation screen
- The assistant's proposal is among the proposals waiting for a decision
- Deciding it applies it through the app's usual path

#### Scenario: A change the user already turned down is not proposed again
- The user rejects a change
- The assistant proposes the same change again
- The proposal is refused

---

### Requirement: REQ-TOOLREG-015

A proposal request that cannot be carried out **SHALL** be refused with a message
that says which part of it was wrong.

**Rationale:** "something went wrong" tells the caller nothing it can act on. The
caller can act on "that operation is not one of these" and on "that task does not
exist" — and the model, which is the caller, is the party that can fix both.

#### Scenario: An unknown operation is refused
- The assistant asks for an operation that does not exist
- The answer says which operations exist
- No proposal is created

#### Scenario: A missing value is refused
- The assistant asks to set a field and gives no value
- The answer says the operation needs a value
- No proposal is created

#### Scenario: A task that does not exist is refused
- The assistant proposes a change to a task that does not exist
- The answer says there is no such task
- No proposal is created

---

### Requirement: REQ-TOOLREG-020

A change proposed for a note **SHALL** be visible in the note's own screen, and the
user **SHALL** be able to decide it there.

**Rationale:** the note editor's AI actions propose a change and wait. If the proposal
is saved and nothing watches it, the user asked for a rewrite, the rewrite was written,
and nothing appeared — a feature that works and is invisible. The answer to a proposal
belongs next to the thing it proposes to change.

#### Scenario: The proposed change is shown
- An AI action has proposed a change to the note
- The note editor shows the change
- The change waits for the user's word

#### Scenario: The user applies it
- The user applies the change
- The change is applied through the app's usual path
- The change is no longer waiting

### Requirement: REQ-TOOLREG-021

A tool that writes a user's data directly SHALL be listed with the reason it
does, and the list SHALL shrink as tools are converted.

A tool that writes a user's data and is not listed SHALL fail the build. A listed
tool that no longer writes directly SHALL fail the build as well. A tool that both
proposes a change and writes it directly SHALL fail the build.

**Rationale:** the write path is half a write path. The delete tools and the
proposal tool propose a change and let the user decide; the other tools write
straight to the user's data. The same act gets two treatments, decided by which
tool the model happened to pick — and a user who agreed to one thing cannot tell
which of the two they are looking at.

A rule that is written down and not checked is a rule that is true in the document
and false in the product. The list is the measure of what is left, so it is
held in both directions: a new writer has to say why it writes, and a tool that
was converted cannot leave a stale entry behind that hides the work.

#### Scenario: A new tool writes the user's data directly

- **Given** a tool writes the user's data and is not listed
- **When** the build runs
- **Then** it fails
- **And** it names the tool and says the reason has to be written down

#### Scenario: A tool was converted to the proposal path

- **Given** a listed tool no longer writes the user's data directly
- **And** its entry is still listed
- **When** the build runs
- **Then** it fails
- **And** it says the entry is stale

#### Scenario: A tool proposes and writes at the same time

- **Given** a tool both proposes a change and writes the user's data directly
- **When** the build runs
- **Then** it fails
- **And** it says one tool must not have two treatments

#### Scenario: The listed writers are the actual writers

- **Given** every tool that writes the user's data directly is listed with a
      reason
- **And** every listed tool writes directly
- **When** the build runs
- **Then** it passes
- **And** it reports how many writers are listed

### Requirement: REQ-TOOLREG-024 No write tool writes

No tool SHALL change the user's data without the user confirming a proposal
first. A tool that proposes SHALL answer with the proposal it created, and a
tool that could not store the proposal SHALL say so and SHALL NOT name a thing
that does not exist.

The invariant SHALL be enforced by a check that reads what the tools do — and
not by a list of exceptions kept in a file, because a list of exceptions is a
second thing to keep in step with the code, and the code can change without it.

**Rationale:** eight tools proposed and one wrote. Which one a change got was
decided by which tool the model happened to reach for, and the file that said
so was a promise about the code rather than a measurement of it.

#### Scenario: A tool that creates a tag

- **Given** a tool that creates a tag
- **When** the model calls it
- **Then** a proposal is stored
- **And** the tag does not exist yet
- **And** the answer names the proposal

#### Scenario: Every write tool is checked

- **Given** the tools that change the user's data
- **When** the check runs
- **Then** each of them leaves the user's data unchanged and stores a proposal

### Requirement: REQ-TOOLREG-025 The value carries its type

A proposal's value SHALL be typed: the field and the value SHALL be one thing,
so they cannot disagree. There SHALL be one reader per entity, and every value
that is read — by the tool, by the planner, or by the user changing it — SHALL
go through it. A value that does not read SHALL be refused with a reason.

**Rationale:** the field and the value were two things, so the shape of the
value was whatever the model happened to type and the code reading it had to
guess. A priority was `"high"` in one place and `TaskPriority.High` in another,
and the two spellings did not always agree.

#### Scenario: The tool reads the value it was given

- **Given** a tool that was given a value the entity cannot hold
- **When** the model calls it
- **Then** the tool refuses the value
- **And** the reason is reported to the model
- **And** no proposal is stored

#### Scenario: The user changes the value

- **Given** a proposal carrying a typed value
- **When** the user changes it
- **Then** the new value is read by the same reader
- **And** a value that does not read is refused with the reason

### Requirement: REQ-TOOLREG-022

A tool that changes the user's data SHALL propose the change instead of
performing it.

The change SHALL be stored as a proposal, and the change itself SHALL happen
only when the user confirms the proposal. A creation SHALL be a proposal like
any other change, and the proposal SHALL name the identifier the thing will
have. A tool's answer SHALL say that a proposal was created, and SHALL NOT
carry an identifier of a thing that does not exist.

**Rationale:** the same act must get the same treatment. When a deletion asks
and a creation does not, the user's "yes" means two different things depending
on which tool the model reached for — and a consent that means two things is
not consent.

A creation needs the same treatment as an edit because a creation is a change:
the model wrote it, nobody has seen it, and the user may want another title.

#### Scenario: The tool proposes a change

- **Given** a tool that changes the user's data
- **When** the model calls it
- **Then** the change is stored as a proposal
- **And** the user's data is unchanged
- **And** the answer names the proposal

#### Scenario: The user confirms the proposal

- **Given** a proposal waiting for a decision
- **When** the user confirms it
- **Then** the change happens
- **And** the thing that comes into being is the thing the proposal named

#### Scenario: A creation is proposed

- **Given** a tool that creates something
- **When** the model calls it
- **Then** a proposal is stored
- **And** the proposal names the identifier the thing will have
- **And** the thing does not exist yet

#### Scenario: The proposal cannot be applied

- **Given** a proposal whose value cannot be read
- **When** the user confirms it
- **Then** nothing is created or changed
- **And** the reason is reported

### Requirement: REQ-TOOLREG-023

A tool SHALL read the values it was given before it stores the proposal, and
SHALL store them the way the entity holds them.

A value the tool cannot read SHALL be reported to the model in the tool's
answer, and no proposal SHALL be stored. A value that has more than one
spelling — a priority, a kind of work, a status — SHALL be read in the
spelling the model writes and stored in the spelling the entity holds.

**Rationale:** a proposal the user approves and then cannot apply is a
proposal that burned the user's attention for nothing. The model is the one
who can fix a value it named wrong, so the value is checked where the model
can still see it.

#### Scenario: A value the model named wrong

- **Given** a tool that changes the user's data
- **When** the model calls it with a value that does not exist
- **Then** no proposal is stored
- **And** the answer names the value and the names that do exist

#### Scenario: A value the model spelled differently

- **Given** a priority the model wrote in lower case
- **When** the tool stores the proposal
- **Then** the proposal carries the spelling the entity holds
- **And** confirming the proposal applies it
