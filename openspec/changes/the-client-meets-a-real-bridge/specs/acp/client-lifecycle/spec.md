# The client survives what it has no answer for — Observable Behavior

**capability:** `acp/client-lifecycle` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-ACPL-004

The client **SHALL** read a notification that answers no request it made, and **SHALL**
carry on. A notification **SHALL NOT** end the conversation, and **SHALL NOT** produce
activity the reader sees.

**Rationale:** the protocol grows. An agent announces things the application never asked
for — its authentication state, its available commands, whatever the next version adds
— and a client that treated an unknown notification as a failure would never get past
the handshake with a real bridge. The rule is not "ignore what is unknown": it is "read
it, and produce nothing for it", because an update that invented an activity would put a
card on the reader's screen that nothing justified.

#### Scenario: The agent announces its authentication state
- The agent answers the handshake
- The agent sends a notification this application made no request for
- The conversation continues, and the reader is told nothing about the notification

#### Scenario: The agent announces its available commands
- The session is open
- The agent sends a notification this application made no request for
- The conversation continues, and the reader is told nothing about the notification
