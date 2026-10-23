# Every tool says how it writes — Observable Behavior

**capability:** `ai/write-classification` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-WCLS-001

Every tool an agent can call **SHALL** declare exactly one write behaviour: it does not
change state, it changes the user's data by proposing first, or it writes directly as a
declared exception.

**Rationale:** "all writes ask first" is a promise the user is asked to rely on. A promise
that is kept by the discipline of whoever wrote each tool is a promise that decays. Declaring
the behaviour in the registry turns it into something the build checks.

#### Scenario: A tool with no declared behaviour does not build
- A tool is added without declaring how it writes
- The consistency check fails
- The behaviour must be chosen before the tool can ship

#### Scenario: The declared set and the exposed set agree
- The registry lists the tools an agent can call
- Every one of them declares a write behaviour
- A tool present in one and missing from the other fails the check

---

### Requirement: REQ-WCLS-002

A tool that writes directly, rather than by proposing first, **SHALL** carry a stated reason
explaining why it sits outside the proposal pipeline, and that reason **SHALL** be visible in
the registry.

**Rationale:** an exception without a reason is not an exception, it is a hole. The reason is
what makes the exception reviewable — by a person reading the registry, without reading the
tool's code.

#### Scenario: An exception carries a reason
- A tool declares that it writes directly
- It states why it sits outside the proposal pipeline
- The reason appears in the registry

#### Scenario: An exception without a reason does not build
- A tool declares that it writes directly and states no reason
- The consistency check fails
- The reason must be written before the tool can ship

---

### Requirement: REQ-WCLS-003

A tool that changes the user's data by proposing first **SHALL NOT** change that data until
the user confirms the proposal, and a declined proposal **SHALL** leave the data unchanged.

**Rationale:** the proposal pipeline is only a gate if it actually gates. A tool that writes
and then offers a proposal has not asked anything.

#### Scenario: A confirmed proposal is applied
- An agent calls a tool that changes the user's data
- A proposal appears and the user confirms it
- The data changes

#### Scenario: A declined proposal changes nothing
- An agent calls a tool that changes the user's data
- A proposal appears and the user declines it
- The data is exactly as it was

---

### Requirement: REQ-WCLS-004

A write performed by an agent **SHALL** reach the user through the same proposal pipeline as
every other write to the user's data, whatever route the agent took to make it.

**Rationale:** the guarantee is about the user's data, not about which agent produced the
write. An agent connected from outside the application has no privileged path.

#### Scenario: An external agent's write is proposed
- An agent running outside the application asks for a change to the user's data
- A proposal appears in the application
- The change lands only after the user confirms it

---

## MODIFIED Requirements

### Requirement: REQ-TOOLREG-001

The tool registry **SHALL** list every tool an agent can call, and each entry **SHALL** carry
the tool's name, its domain, its annotations, and its write behaviour.

**Rationale:** the registry is the one place that answers "what can an agent do here". A
registry that omits how a tool writes answers half the question.

#### Scenario: The registry is complete
- The registry is compared against the tools actually exposed to agents
- Both sets are the same
- The comparison passes
