# Text Input — Observable Behavior

**capability:** `text-input` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-TI-REWRITE-1

A bridged text field SHALL be handed a **source**, and the source's type SHALL
say who may replace the field's value.

The bridge SHALL offer exactly two shapes of source, and they SHALL be
unrelated types:

- a **static** source, declaring that the upstream replaces the value only with
  one the field never produced — it loads the value once, or clears it;
- an **owned** source, declaring that the upstream owns the value and may
  replace it with anything, including a value the field produced earlier, and
  carrying the count of the replacements.

A field SHALL NOT be able to express a third answer by accident: adding a shape
of source SHALL require the bridge to say what that shape means.

#### Scenario: A static upstream

- **Given** a field whose upstream only loads or clears the value
- **When** the upstream replaces the value
- **Then** the field takes the value, and no count is involved

#### Scenario: An owned upstream

- **Given** a field whose upstream owns the value
- **When** the upstream replaces the value with one the field produced earlier
- **And** the count of the replacements has moved
- **Then** the field takes the value

#### Scenario: An owned upstream that has not moved its count

- **Given** a field whose upstream owns the value
- **When** the upstream hands back a value the field produced earlier
- **And** the count of the replacements has not moved
- **Then** the field keeps its own text — the lagging upstream is not applied

### Requirement: REQ-TI-REWRITE-2

The bridge SHALL NOT take a count of replacements as an argument.

A call site SHALL NOT be able to freeze the count, to omit it, or to leave it
to a default. The count SHALL be a property of the source, and the source
SHALL be required.

#### Scenario: A call site cannot write a count down

- **Given** the bridge's public declarations
- **When** their parameters are read
- **Then** none of them is a count of replacements

#### Scenario: A call site cannot omit the answer

- **Given** a bridged field
- **When** the field is wired
- **Then** it is handed a source, whose type says who may rewrite it

### Requirement: REQ-TI-REWRITE-3

The **owner** of a value SHALL build the source, and a field SHALL pass it
through.

The owner is the component that holds the value and knows who else writes it. A
field that built its own source would be answering a question it cannot know the
answer to, and it would answer it wrongly in the direction that loses the
user's work.

The one exception SHALL be a renderer whose owner is a server the renderer does
not own: the renderer is then the boundary at which the server's value and the
server's count are read, and the count SHALL still come from the server's side.

#### Scenario: A field that builds its own source

- **Given** a bridged field site
- **When** the source it hands the bridge is inspected
- **Then** it is a source it received, not one it constructed

#### Scenario: The GenUI renderer

- **Given** the GenUI text field, whose owner is the surface's server
- **When** the renderer builds the source
- **Then** the count in it is the server's count

### Requirement: REQ-TI-REWRITE-4

The rewrite contract of every bridged field site SHALL be published, and the
publication SHALL be checked against the code.

The publication SHALL name, per site: the fields it owns, the upstream that may
write them, the kind of source the site is handed, and — for an owned source —
the file that declares the count and the file that advances it.

The check SHALL fail when:

- a site that owns a bridged field is missing from the publication;
- the owner does not declare a source of the claimed kind;
- an owned source's count is not declared, or is never advanced;
- a field site builds its own source;
- the publication and the code disagree.

#### Scenario: A site missing from the publication

- **Given** a file that calls the bridged helper
- **When** it is missing from the publication
- **Then** the check fails

#### Scenario: An owner that declares the wrong kind

- **Given** a site whose publication claims an owned source
- **When** the owner declares a static source
- **Then** the check fails

#### Scenario: A count that never advances

- **Given** a site whose publication claims an owned source
- **When** the file that advances the count never advances it
- **Then** the check fails

#### Scenario: The publication and the code agree

- **Given** a site whose publication matches the code
- **When** the check runs
- **Then** it passes
