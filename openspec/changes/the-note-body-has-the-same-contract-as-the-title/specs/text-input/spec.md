# Text Input — Observable Behavior

**capability:** `text-input` | **status:** accepted

---

## MODIFIED Requirements

### Requirement: REQ-TI-REWRITE-1

The rewrite contract of a bridged field site SHALL be checked against the code's
structure.

A **static** row's owner SHALL declare a value whose writes are all named, and the
source it hands the field SHALL be built from that value.

An **owned** row's counter SHALL count the replacements of the value the row is about,
and SHALL move only when that value was replaced from outside.

#### Scenario: A static owner that holds a bare string

- **Given** a site whose publication claims a static source
- **When** the file that owns the value holds no value with named writes
- **Then** the check fails

#### Scenario: A static owner that holds a value with named writes

- **Given** a site whose publication claims a static source
- **When** the file that owns the value holds one
- **Then** the check passes

#### Scenario: A counter that counts another field's replacements

- **Given** a site whose publication claims an owned source
- **When** the count that the field reads moves for a value other than the one the row is about
- **Then** the check fails

## ADDED Requirements

### Requirement: REQ-TI-REVISIONED-3

A value the upstream owns SHALL count only the replacements of that value.

The count SHALL move when the value was replaced from outside, and SHALL stay where it
was when the upstream wrote the value the owner already held.

#### Scenario: A replacement of another field

- **Given** a value the upstream owns
- **When** the upstream replaces a different value of the same owner
- **Then** this value's count does not move

#### Scenario: A replacement of this value

- **Given** a value the upstream owns
- **When** the upstream replaces it with a value it did not hold
- **Then** this value's count moves

#### Scenario: A write of the value the owner already holds

- **Given** a value the upstream owns
- **When** the upstream writes the value the owner already holds
- **Then** this value's count does not move
