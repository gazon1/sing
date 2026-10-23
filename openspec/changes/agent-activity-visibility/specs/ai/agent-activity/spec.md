# The agent's work is visible — Observable Behavior

**capability:** `ai/agent-activity` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-AGACT-001

A turn **SHALL** produce activity in the form of text as it arrives, tool calls with their
outcome, and the agent's plan — and **SHALL NOT** carry the raw payload of a tool call.

**Rationale:** the reader needs to know what the agent is doing and whether it worked. They
do not need the arguments the agent passed, and the arguments are where user content lives.

#### Scenario: A tool call appears with an outcome
- The agent calls a tool
- An activity entry appears carrying the tool's name and that it is running
- A later entry carries that the call completed or failed
- The raw arguments are not part of the activity

#### Scenario: The answer is assembled from its pieces
- The agent's answer arrives in several pieces
- The pieces are added to the message
- The message ends up holding the whole answer

---

### Requirement: REQ-AGACT-002

Every turn **SHALL** end with exactly one outcome — completed, failed, or cancelled — however
the turn ends.

**Rationale:** a turn that ends without an outcome is a spinner that never stops, and a
spinner that never stops teaches the reader that the app is broken. Exactly one outcome also
means the outcome cannot be argued with: there is a fact about how the turn ended.

#### Scenario: A normal answer completes
- The agent answers
- The turn is completed
- The loading indicator is gone

#### Scenario: A turn whose stream dies is failed
- The agent's stream ends without an answer
- The turn is failed
- The turn is not reported as completed

#### Scenario: The user cancels
- The user cancels a running turn
- The turn is cancelled
- The answer produced so far is still there
- The loading indicator is gone

#### Scenario: An outcome does not change
- The turn has already reached an outcome
- A late event arrives
- The outcome is unchanged

---

### Requirement: REQ-AGACT-003

A tool call that fails **SHALL** be shown as failed, and **SHALL NOT** be silently omitted.

**Rationale:** the whole point of watching is to see the failure. A call that vanishes leaves
the reader to guess whether the agent did the thing, and the agent's own prose is not a
reliable witness for its own tools.

#### Scenario: A failing tool is visible
- A tool throws
- Its card shows that it failed
- The turn continues or ends with an outcome; either way the failure was visible

---

### Requirement: REQ-AGACT-004

The activity shown on screen **SHALL** be a bounded, sanitized summary of what a tool
touched — identifiers, titles and field names, and **NOT** the content of the user's notes —
and the raw detail **SHALL** be hidden until the reader asks for it.

**Rationale:** a card that shows a whole note body is a card that puts the user's content on
a screen that was about to be shared, photographed, or screenshotted. The reader needs to
know *what* was touched, not *the whole thing*.

#### Scenario: The summary names the thing, not its content
- A tool touches a note
- The card names the note and the fields it touched
- The card does not show the note's text

#### Scenario: The raw detail is opt-in
- A card has more detail than the summary
- The detail is not shown at first
- The reader asks for it and then sees it, bounded in length

---

### Requirement: REQ-AGACT-005

The same presentation of an agent's activity **SHALL** be used by every screen that shows one.

**Rationale:** two screens that show the same thing in two ways is two things to keep right,
and the reader has to learn the difference between them for no benefit.

#### Scenario: One presentation, two surfaces
- The built-in chat shows an agent's activity
- The external-agent screen shows an agent's activity
- Both use the same cards
