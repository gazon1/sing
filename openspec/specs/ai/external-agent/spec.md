# ai/external-agent Specification

## Purpose
The user runs an agent program of their own against the application: they pick an agent from
a registry of known ones, see what it may do before it starts, connect it, watch it work, and
stop it — with every write it proposes passing through the application's own confirmation, and
every process it starts ending with the session.

## Requirements

### Requirement: REQ-EXTAG-001

The desktop application **SHALL** offer a screen where the user can select an external agent
from a registry of known agents, see which command will be launched, and connect to it.

**Rationale:** the user is choosing a program to run on their machine with access to their
data. Which program, and how it is launched, has to be visible before the connection, not
discoverable afterwards in a log.

#### Scenario: The registry offers a choice
- The user opens the external agent screen
- The agents that can be selected are listed
- One of them is selected by default
- The command that will be launched is shown

#### Scenario: The screen is reachable
- The user navigates from the application
- The external agent screen opens
- It is a part of the application, not a preview

---

### Requirement: REQ-EXTAG-002

The application **SHALL** give the external agent access to the user's tools by handing it a
launchable reference to the application's own tool server, and **SHALL NOT** give the agent
any other route to the user's data.

**Rationale:** the domain access, the kill switch, the tool-call log and the rule that a
write asks first live in one place. A second route to the same data is a second place where
they can be forgotten.

#### Scenario: The agent reaches the user's tasks
- The user connects an agent and asks it something about their tasks
- The agent obtains the answer by calling the application's own tools
- The calls are recorded and the writes are proposed

---

### Requirement: REQ-EXTAG-003

When the tool server cannot be provided, the application **SHALL** start the session without
tool access, **SHALL** show a warning **before the first message** is sent, and **SHALL**
keep that warning visible for the whole session.

**Rationale:** an agent that cannot see the user's tasks answers from its imagination, and
the user has no way to tell the difference. The warning is the difference — and it has to
outlive the agent's first reply, which is exactly when a warning placed in the message list
would disappear.

#### Scenario: The warning comes before the first message
- The tool server cannot be found
- The user opens the screen
- The warning is already visible, before anything has been sent
- The connection still succeeds

#### Scenario: The warning survives the first reply
- The warning is visible
- The agent answers
- The warning is still visible

#### Scenario: No access is distinguishable from a failed tool
- The agent is connected and a tool fails
- What the user sees differs from the no-access warning
- The user can tell which of the two happened

---

### Requirement: REQ-EXTAG-004

Closing a session **SHALL** terminate every process of that session's process tree, including
processes the agent itself started, and **SHALL** be safe to repeat.

**Rationale:** the tool server the agent launches is a grandchild of the application. Ending
the agent does not end it, and a tool server left running against the user's database is a
process nobody is watching and nobody can stop.

#### Scenario: The agent's own child is ended too
- The agent started a process of its own
- The user closes the session
- No process from that tree remains
- Closing an already-closed session changes nothing

---

### Requirement: REQ-EXTAG-005

An unanswered permission request **SHALL** be declined, and a permission request **SHALL**
be resolved when the turn ends, is cancelled, or the session closes.

**Rationale:** a permission request that waits forever is a turn that waits forever. And a
request that outlives the turn it belonged to is a question about a turn that is over.

#### Scenario: Nobody answers
- The agent requests permission
- Nobody answers within the deadline
- The request is declined
- The turn continues

#### Scenario: The turn is cancelled with a request outstanding
- The agent requests permission
- The user cancels the turn
- The request is resolved
- Nothing is left waiting for an answer

---

### Requirement: REQ-EXTAG-006

The application **SHALL NOT** retry a failed prompt or a failed tool call, **SHALL** preserve
the answer produced before a failure, and **SHALL** show an error the reader can act on.

**Rationale:** a prompt that is replayed can produce a second proposal for a change the user
has already seen, or a second write for a change the user already confirmed. The cost of a
retry is not a wasted attempt — it is a duplicate the user has to notice.

#### Scenario: The agent dies mid-turn
- The agent has already produced part of an answer
- It dies
- The part of the answer that exists is still there
- An error and a way to reconnect are shown
- The prompt is not sent again

---

### Requirement: REQ-EXTAG-007

The application **SHALL** refuse to begin a second turn while one is in progress, and
**SHALL** say why.

**Rationale:** two turns interleaved on one connection produce an answer neither turn asked
for.

#### Scenario: A second message is refused
- A turn is running
- The user sends another message
- The message is refused with a message that says a turn is running
