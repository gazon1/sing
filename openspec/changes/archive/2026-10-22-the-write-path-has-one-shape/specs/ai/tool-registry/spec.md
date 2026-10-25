# The write path proposes — Observable Behavior

**capability:** `ai/tool-registry` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-TOOLREG-022

A tool that changes the user's data SHALL propose the change instead of
performing it.

The change SHALL be stored as a proposal, and the change itself SHALL happen
only when the user confirms the proposal. A creation SHALL be a proposal like
any other change, and the proposal SHALL name the identifier the thing will
have. A tool's answer SHALL say that a proposal was created, and SHALL NOT
carry an identifier of a thing that does not exist.

**Rationale:** the same act must get the same treatment. When a deletion asks
and a creation does not, the user's "yes" means two different things depending
on which tool the model reached for — and a consent that means two things is
not consent.

A creation needs the same treatment as an edit because a creation is a change:
the model wrote it, nobody has seen it, and the user may want another title.

#### Scenario: The tool proposes a change

- **Given** a tool that changes the user's data
- **When** the model calls it
- **Then** the change is stored as a proposal
- **And** the user's data is unchanged
- **And** the answer names the proposal

#### Scenario: The user confirms the proposal

- **Given** a proposal waiting for a decision
- **When** the user confirms it
- **Then** the change happens
- **And** the thing that comes into being is the thing the proposal named

#### Scenario: A creation is proposed

- **Given** a tool that creates something
- **When** the model calls it
- **Then** a proposal is stored
- **And** the proposal names the identifier the thing will have
- **And** the thing does not exist yet

#### Scenario: The proposal cannot be applied

- **Given** a proposal whose value cannot be read
- **When** the user confirms it
- **Then** nothing is created or changed
- **And** the reason is reported

### Requirement: REQ-TOOLREG-023

A tool SHALL read the values it was given before it stores the proposal, and
SHALL store them the way the entity holds them.

A value the tool cannot read SHALL be reported to the model in the tool's
answer, and no proposal SHALL be stored. A value that has more than one
spelling — a priority, a kind of work, a status — SHALL be read in the
spelling the model writes and stored in the spelling the entity holds.

**Rationale:** a proposal the user approves and then cannot apply is a
proposal that burned the user's attention for nothing. The model is the one
who can fix a value it named wrong, so the value is checked where the model
can still see it.

#### Scenario: A value the model named wrong

- **Given** a tool that changes the user's data
- **When** the model calls it with a value that does not exist
- **Then** no proposal is stored
- **And** the answer names the value and the names that do exist

#### Scenario: A value the model spelled differently

- **Given** a priority the model wrote in lower case
- **When** the tool stores the proposal
- **Then** the proposal carries the spelling the entity holds
- **And** confirming the proposal applies it
