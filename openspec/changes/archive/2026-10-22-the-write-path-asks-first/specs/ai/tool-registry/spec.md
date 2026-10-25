# The write path asks first — Observable Behavior

**capability:** `ai/tool-registry` | **status:** proposed

---

## ADDED Requirements

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
