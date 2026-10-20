# Text Input — Observable Behavior

**capability:** `text-input` | **status:** accepted

---

## MODIFIED Requirements

### Requirement: REQ-TI-REWRITE-1

The rewrite contract of a bridged field site SHALL be checked against the code's
structure.

The sites SHALL be the files that import the bridged helper, the owner's source SHALL
be a property with a type **or** a source the owner's function hands out, the counter
SHALL be a property with a name, and the bridge's public parameters SHALL be names.

Where a check cannot be expressed structurally, it SHALL be read from the
**declarations** of the file with the comments stripped, and a preview SHALL be
excluded by its annotation.

#### Scenario: A site that is not in the publication

- **Given** a file that imports the bridged helper
- **When** it is missing from the publication
- **Then** the check fails

#### Scenario: An owner that declares the wrong kind

- **Given** a site whose publication claims an owned source
- **When** the owner's property declares a static source
- **Then** the check fails

#### Scenario: An owner that hands the source out

- **Given** a site whose publication claims an owned source
- **When** the owner's function returns the owned source
- **Then** the check passes

#### Scenario: A count that never advances

- **Given** a site whose publication claims an owned source
- **When** no declaration of the file that advances the count advances it
- **Then** the check fails

## REMOVED Requirements

### Requirement: REQ-TI-REWRITE-EXCEPTION

**Reason:** The field site that built its own source no longer exists. The data context
builds the source of a bound field and hands it out whole, so the contract has no
exception left to state.

#### Scenario: The renderer that built its own source

- **Given** a field site whose owner is a server
- **When** the field site is checked
- **Then** the check applies to it like to every other site

## ADDED Requirements

### Requirement: REQ-TI-GENUI-SOURCE-1

The data context SHALL hand out the source of a field bound to a data-model path.

The source SHALL carry the value at the path and the count of the server's writes, and
the two SHALL arrive together.

#### Scenario: A field bound to a path

- **Given** a surface with a field bound to a path
- **When** the field is composed
- **Then** it is handed a source, and the source carries the server's count

#### Scenario: A value and a count from two different moments

- **Given** a server write that lands while the field is composed
- **When** the field is handed its source
- **Then** the value and the count in it belong to the same write

### Requirement: REQ-TI-GENUI-SOURCE-2

A server rewrite of a bound field SHALL replace the field's text, even when the value is
one the field itself sent.

#### Scenario: A rewrite onto a value the field sent

- **Given** a field that sent the value `seedx` and then shows `seedxy`
- **When** the server rewrites the field to `seedx`
- **Then** the field shows `seedx`

#### Scenario: A frozen count

- **Given** a field whose source carries a count that never moves
- **When** the server rewrites the field to a value the field sent
- **Then** the rewrite is an echo, and the field keeps the text the user typed
