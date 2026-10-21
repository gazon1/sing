# Text Input — Observable Behavior

**capability:** `text-input` | **status:** accepted

---

## MODIFIED Requirements

### Requirement: REQ-TI-REWRITE-1

The rewrite contract of a bridged field site SHALL be checked against the code's
structure.

A **static** row's owner SHALL declare a value whose writes are all named, and the
source it hands the field SHALL be built from that value.

#### Scenario: A static owner that holds a bare string

- **Given** a site whose publication claims a static source
- **When** the file that owns the value holds no value with named writes
- **Then** the check fails

#### Scenario: A static owner that holds a value with named writes

- **Given** a site whose publication claims a static source
- **When** the file that owns the value holds one
- **Then** the check passes

## ADDED Requirements

### Requirement: REQ-TI-FIELD-OWNED-1

A value that the field alone writes SHALL offer exactly three ways to change: the
field's own text, a value the upstream loaded, and an emptying.

There SHALL be no way to make such a value other than by one of those three, and there
SHALL be no way to write it without naming the write.

#### Scenario: The field's own text

- **Given** a value that the field alone writes
- **When** the field's text comes back
- **Then** the value holds it, and the write says it is an echo

#### Scenario: A value the upstream loaded

- **Given** a value that the field alone writes
- **When** the upstream loads a value into it
- **Then** the value holds it, and the write says it is a seed

#### Scenario: An emptying

- **Given** a value that the field alone writes
- **When** the upstream empties it
- **Then** the value holds nothing, and the write says it is a clear

#### Scenario: A value with an unnamed count

- **Given** the type whose writes are all named
- **When** a caller tries to make one with a value of its own
- **Then** it cannot — the only ways to make one name the write
