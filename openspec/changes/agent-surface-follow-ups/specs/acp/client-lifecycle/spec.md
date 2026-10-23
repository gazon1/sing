# The client's lifecycle is guarded — Observable Behavior

**capability:** `acp/client-lifecycle` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-ACPL-001

The client's connect, close and terminate transitions **SHALL** be mutually exclusive, so
that a close cannot land between two assignments of the connection's fields.

**Rationale:** the client holds four fields that are set together and cleared together. A
transition that interleaves with another leaves a session bound to a closed transport — a
state no reader of the code would expect and no test would have asked for.

#### Scenario: A close lands during a connect
- A connect is in flight
- A close is requested
- The client ends in one of the two states, and never in a half-updated one

#### Scenario: A terminate lands during a close
- A close is in flight
- A terminate is requested
- The client ends in one of the two states, and never in a half-updated one

---

### Requirement: REQ-ACPL-002

A field that is read outside the lifecycle lock **SHALL** be read safely, so that a reader
cannot observe a value that a transition is midway through writing.

**Rationale:** the lock protects the transitions; it cannot protect a read that happens
outside it. A read of a field that is not itself safe is a race the lock only hides.

#### Scenario: A turn reads the client while a transition runs
- A turn reads the client's session
- A close runs at the same time
- The read sees a whole value, never a torn one

---

### Requirement: REQ-ACPL-003

The client's lifecycle **SHALL** have a test that closes the client while a turn is in
flight, and asserts what the client ends up as.

**Rationale:** the defect is a race, and a race that has no test is a race that comes back.
The test has to name the scenario — a turn in flight, a close during it — or it is testing
something else.

#### Scenario: The client is closed during a turn
- A turn is running against a live agent
- The client is closed
- The turn ends, and the client is left in a state a caller can reason about
