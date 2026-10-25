# ai/write-classification Specification

## Purpose
TBD - created by archiving change acp-client-data-safety. Update Purpose after archive.

## Requirements

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

### Requirement: REQ-WCLS-005

The tool registry **SHALL** list every tool an agent can call, and each entry **SHALL** carry
the tool's name, its domain, its annotations, and its write behaviour.

**Rationale:** the registry is the one place that answers "what can an agent do here". A
registry that omits how a tool writes answers half the question.

#### Scenario: The registry is complete
- The registry is compared against the tools actually exposed to agents
- Both sets are the same
- The comparison passes

### Requirement: REQ-WCLS-010

The write behaviour of every tool **SHALL** be published in the exported tool surface, so
that a reader outside the compiler — a document, an agent, a gate — can see how each tool
writes without reading the catalogue's source.

**Rationale:** a declaration that only exists in the code it describes cannot be checked
against anything else. The export is the one artifact a gate and a reader can both open.

#### Scenario: The export carries the behaviour
- The tool surface is exported from the compiled declarations
- Every tool in it carries its write behaviour
- A tool that writes directly carries its reason too

#### Scenario: The exported surface is regenerated
- A tool's declared behaviour changes
- The surface is regenerated
- The committed surface says what the compiler says

---

### Requirement: REQ-WCLS-011

The catalogue **SHALL** refuse to load when its declaration is malformed: a direct-write
exception without a reason, or a reason on a tool that is not an exception.

**Rationale:** the catalogue's own documentation says the reason is required in one case and
forbidden in the others. A rule that is written down and not checked is a rule that decays
the moment somebody is in a hurry.

#### Scenario: An exception without a reason is refused
- A catalogue entry declares a direct write and carries no reason
- The catalogue is loaded
- The load fails, naming the tool

#### Scenario: A reason that does not apply is refused
- A catalogue entry declares that the tool does not write and carries a reason
- The catalogue is loaded
- The load fails, naming the tool

---

### Requirement: REQ-WCLS-012

The declared write behaviour of every tool **SHALL** be checked against the tools' behaviour,
in both directions: a tool that writes and is not declared an exception is a finding, and a
tool declared an exception that no longer writes is a finding.

**Rationale:** a declaration nobody compares with the code is a belief. The comparison is
what turns "the tool proposes" into something the build knows.

#### Scenario: A tool writes and does not declare it
- A tool writes the user's data directly
- The catalogue does not declare it a direct-write exception
- The check fails, naming the tool

#### Scenario: A declared exception no longer writes
- The catalogue declares a tool a direct-write exception
- The tool no longer writes
- The check fails, naming the tool

---

### Requirement: REQ-WCLS-013

The check **SHALL** name the declared exceptions it could not verify, so that a write the
check cannot see is a visible one rather than a silent gap. A named exception **SHALL NOT**
be failed by the branch that fails the exceptions it cannot verify.

**Rationale:** the check reads sources, and a source scan cannot see every port. A claim of
coverage that is not checked is worse than no claim, because it is believed. And a check
that fails on what it cannot see teaches its readers to ignore it, and a reader who
ignores it stops reading.

#### Scenario: A write is outside the check's reach
- A tool declares that it writes directly
- The check cannot place the write on the user's own data
- The check names the tool among the exceptions it could not verify

#### Scenario: A named exception is not failed by what the check cannot see
- A tool declares that it writes directly
- The check cannot place the write on the user's own data
- The tool is named among the exceptions the check could not verify
- The check does not fail it

#### Scenario: An unnamed exception is not left in silence
- A tool declares that it writes directly
- The check cannot place the write on the user's own data
- The tool is not named among the exceptions the check could not verify
- The check fails, naming the tool

---

### Requirement: REQ-WCLS-014

Every tool declared as proposal-gated **SHALL** be driven through the whole round trip: the
call proposes, the user's data is unchanged, the confirmation writes, and the refusal
changes nothing.

**Rationale:** the refusal is the half that gets forgotten. A tool that proposes and then
writes anyway, or that deletes the row it proposed to delete, passes every test that only
looks at the confirmation path.

#### Scenario: A proposal-gated creation is confirmed
- An agent calls a proposal-gated creation tool
- A proposal appears and the user's data is unchanged
- The user confirms the proposal
- The data changes

#### Scenario: A proposal-gated deletion is refused
- An agent calls a proposal-gated deletion tool
- A proposal appears and the thing is still there
- The user refuses the proposal
- The thing is still there
