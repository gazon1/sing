# Text Input — Observable Behavior

**capability:** `text-input` | **status:** accepted

---

## MODIFIED Requirements

### Requirement: REQ-TI-SHAPE-3

The rewrite contract of a bridged field site SHALL be checked against the code's

structure.

The sites SHALL be the files that import the bridged helper, the owner's source SHALL

be a property with a type, the counter SHALL be a property with a name, and the

bridge's public parameters SHALL be names.

Where a check cannot be expressed structurally, it SHALL be read from the

**declarations** of the file with the comments stripped, and a preview SHALL be

excluded by its annotation.

The sites SHALL be the files that import the bridged helper, the owner's source SHALL be a property with a type **or** a source the owner's function hands out, the counter SHALL be a property with a name, and the bridge's public parameters SHALL be names.

The counter SHALL be a value that carries its own count, or a plain count of the upstream's writes; and the count SHALL advance through the operation that moves it, or by incrementing the plain count.

A **static** row's owner SHALL declare a value whose writes are all named, and the source it hands the field SHALL be built from that value.

#### Scenario: A static owner that holds a bare string

- **Given** a site whose publication claims a static source
- **When** the file that owns the value holds no value with named writes
- **Then** the check fails

#### Scenario: A static owner that holds a value with named writes

- **Given** a site whose publication claims a static source
- **When** the file that owns the value holds one
- **Then** the check passes

#### Scenario: A site that is not in the publication

- **Given** a file that imports the bridged helper
- **When** it is missing from the publication
- **Then** the check fails

#### Scenario: An owner that declares the wrong kind

- **Given** a site whose publication claims an owned source
- **When** the owner's property declares a static source
- **Then** the check fails

#### Scenario: A count that never advances

- **Given** a site whose publication claims an owned source
- **When** no declaration of the file that advances the count advances it
- **Then** the check fails

#### Scenario: A comment that talks about a count

- **Given** a file whose documentation mentions a count that moves
- **And** no declaration of it advances the count
- **Then** the check fails

#### Scenario: An owner that hands the source out

- **Given** a site whose publication claims an owned source
- **When** the owner's function returns the owned source
- **Then** the check passes

#### Scenario: A counter that is declared

- **Given** a site whose publication claims an owned source
- **When** the file that owns the counter declares no count
- **Then** the check fails

#### Scenario: A counter that never advances

- **Given** a site whose publication claims an owned source
- **When** nothing in the file that advances the counter moves it
- **Then** the check fails

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
