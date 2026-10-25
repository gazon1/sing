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

An **owned** row's counter SHALL count the replacements of the value the row is about, and SHALL move only when that value was replaced from outside.

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
