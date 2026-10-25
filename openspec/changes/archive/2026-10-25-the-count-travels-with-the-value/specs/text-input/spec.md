# Text Input — Observable Behavior

**capability:** `text-input` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-TI-REVISIONED-1

The count of a value's replacements SHALL travel inside the value.

A value that carries its count SHALL offer exactly two ways to change: one that leaves
the count where it was, and one that moves it. There SHALL be no way to make a value
whose count does not match it.

#### Scenario: A keystroke

- **Given** a value that carries a count
- **When** the field's own change is applied to it
- **Then** the value changes and the count does not

#### Scenario: A replacement from outside

- **Given** a value that carries a count
- **When** the upstream replaces it
- **Then** the value changes and the count moves

#### Scenario: A count that does not match its value

- **Given** the type that carries a value and its count
- **When** a caller tries to make one with an arbitrary count
- **Then** it cannot — the only ways to make one say what kind of change it is

### Requirement: REQ-TI-REVISIONED-2

An owner of a bridged field SHALL hold the value and its count in one object, and the
source it hands the field SHALL be built from the two together.

#### Scenario: The owner of a task's title

- **Given** a task draft
- **When** the task is rewritten from outside
- **Then** the draft's title is replaced and its count moves

#### Scenario: The owner of a task's title, typed into

- **Given** a task draft
- **When** the user types in the title
- **Then** the draft's title changes and its count does not

#### Scenario: The source the field is handed

- **Given** a draft that holds a title
- **When** the field is handed the source
- **Then** the source carries the title and the count of its replacements

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

#### Scenario: A counter that is declared

- **Given** a site whose publication claims an owned source
- **When** the file that owns the counter declares no count
- **Then** the check fails

#### Scenario: A counter that never advances

- **Given** a site whose publication claims an owned source
- **When** nothing in the file that advances the counter moves it
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
