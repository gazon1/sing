# Every write tool proposes, and the value it proposes is typed — Observable Behavior

**capability:** `ai/tool-registry` | **status:** accepted

---

## ADDED Requirements

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
