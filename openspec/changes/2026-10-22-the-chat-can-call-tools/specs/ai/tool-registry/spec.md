# The chat can call the tools it was given — Observable Behavior

**capability:** `ai/tool-registry` | **status:** proposed

---

## ADDED Requirements

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
