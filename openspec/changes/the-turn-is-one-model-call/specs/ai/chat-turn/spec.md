# ai/chat-turn Specification

## ADDED Requirements

### Requirement: REQ-CHTURN-001

A turn of the built-in chat **SHALL** ask the model once, through the same port that
owns the tool registry, and the model **SHALL** be offered the tools that the kill
switch left standing.

**Rationale:** the chat is where a person asks about their own tasks. An agent that
cannot look them up answers from its imagination, wearing their names — and a reader
cannot tell the difference between a task that exists and a task the model invented.

#### Scenario: The reader asks about their own tasks
- The reader asks which of their tasks are overdue
- The agent is offered the tools that read the reader's tasks
- The agent looks the tasks up, and the answer names tasks that exist

#### Scenario: The reader asks a question that needs no data
- The reader asks what a pomodoro is
- The agent answers in words
- No card appears, because no tool was called

### Requirement: REQ-CHTURN-002

A tool call made during a turn of the built-in chat **SHALL** appear as an activity card
that carries the tool's name and its outcome, in the same presentation every other agent
surface uses.

**Rationale:** a card is the only way the reader learns what an agent did to their data.
Two screens that show the same work in two ways is one more thing to keep in step; one
presentation, used twice, cannot drift apart.

#### Scenario: A tool runs while the reader waits
- The agent calls a tool
- A card appears with the tool's name while the call is running
- The card says whether the call finished or failed

#### Scenario: The turn answers with words only
- The agent calls no tool
- No card appears
- The answer is on screen as text

### Requirement: REQ-CHTURN-003

Every turn of the built-in chat **SHALL** reach exactly one terminal outcome — completed,
failed or cancelled — and **SHALL NOT** leave the reader waiting.

**Rationale:** "is it still going?" is a question the reader asks of the screen, and the
screen has to answer it. A turn that ends without an outcome is a turn the reader cannot
tell apart from one that is still running.

#### Scenario: The answer arrives
- The model finishes the turn
- The outcome is completed, and the loading indicator is gone

#### Scenario: The model dies mid-answer
- The stream ends without the completion event
- The outcome is failed, and the answer so far stays on screen

#### Scenario: The reader stops the turn
- The reader stops the turn while it is running
- The outcome is cancelled, the answer so far stays on screen, and the loading
  indicator is gone

### Requirement: REQ-CHTURN-004

The reader **SHALL** be able to stop a turn that is in flight, and a stopped turn
**SHALL** keep the answer it had already produced.

**Rationale:** an agent that cannot be stopped is an agent the reader has to wait for.
The mechanics of stopping existed; what was missing was a way to reach them.

#### Scenario: The turn is taking too long
- A turn is running
- The reader presses stop
- The turn ends as cancelled, the partial answer stays, and the screen is ready for the
  next question

### Requirement: REQ-CHTURN-005

An answer that draws a screen **SHALL** render that screen, and a screen the reader's
model could not get right **SHALL** be asked for again — up to the number of corrections
the screen's owner allows.

**Rationale:** the screen is part of the answer, not a separate request. A screen that
was rejected and never corrected is a broken answer; a screen that is never drawn at all
is not one.

#### Scenario: The answer draws a screen
- The answer carries a screen
- The screen is rendered next to the answer, under the answer's own identifier

#### Scenario: The screen was rejected
- The model's screen did not satisfy the screen's rules
- The model is asked again, with the rejections named
- The turn ends with a screen, or with an answer and a note that the screen could not
  be completed

### Requirement: REQ-CHTURN-006

A provider that is not configured **SHALL** end the turn as failed, and **SHALL NOT** end
it as completed.

**Rationale:** a turn that says "your key is missing" in its text and reports itself as
finished is a turn that reports two different things. The reader is owed one answer
about how the turn ended.

#### Scenario: No credentials are configured
- The reader asks a question
- The turn ends as failed, and the screen says the provider is not configured

### Requirement: REQ-CHTURN-007

The cost of a turn of the built-in chat **SHALL** be recorded once, by the one component
that records the calls the port makes.

**Rationale:** two writers for one fact is a disagreement waiting to happen. A screen is
part of the answer to the reader's question; it is not a second expense to be counted
separately.

#### Scenario: A turn draws a screen
- The turn is recorded in the usage ledger
- The ledger holds one row for that turn

### Requirement: REQ-CHTURN-008

A turn **SHALL** be asked in the context of the conversation it belongs to.

**Rationale:** "and now only the overdue ones" is a question about the previous answer.
An agent that has forgotten what it was asked is answering a different question, and the
reader cannot tell.

#### Scenario: The reader asks a follow-up
- The reader asked something, and the agent answered
- The reader asks a follow-up
- The agent answers in the context of the conversation so far
