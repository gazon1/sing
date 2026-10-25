# Text Input — Observable Behavior

**capability:** `text-input` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-TI-SOURCE-KIND-1

The kind of a field's source SHALL be a compile-time property of the parameter that carries
it.

A component that serves two kinds of upstream SHALL be split, and each half SHALL take a
source of one kind. The bridge SHALL NOT offer an overload that takes the sealed parent and
decides the kind at runtime.

#### Scenario: A component that serves two kinds

- **Given** a component that serves an upstream that loads the value and an upstream that
  owns it
- **When** its parameter carries the sealed parent of the two kinds
- **Then** the check fails

#### Scenario: A caller with the wrong kind

- **Given** an editor whose upstream owns the value
- **When** a caller hands it a source of the other kind
- **Then** the call does not compile

#### Scenario: A bridge that dispatches at runtime

- **Given** a bridge with an overload that takes the sealed parent
- **When** the overload decides which kind of source it was given
- **Then** the check fails
