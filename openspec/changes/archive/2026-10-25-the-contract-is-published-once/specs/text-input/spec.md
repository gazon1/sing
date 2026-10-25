# Text Input — Observable Behavior

**capability:** `text-input` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-TI-REGISTRY-1

The text-field contract SHALL be published in exactly one place, and the tests that
check it SHALL read that place.

A test SHALL NOT hold a second copy of the contract.

#### Scenario: A field that is not classified

- **Given** a file that owns a text field
- **When** it is missing from the published table
- **Then** the check fails

#### Scenario: A classification that does not match the code

- **Given** a row that claims *local* for a file
- **When** the file imports the bridged helper
- **Then** the check fails

### Requirement: REQ-TI-REGISTRY-2

The published rewrite table SHALL name the file that builds the source, and a row
without an owner SHALL fail the check.

A field site that builds its own source SHALL say so by naming itself as its owner.

#### Scenario: An owner that declares the wrong kind

- **Given** a row that claims an owned source
- **When** the owner's property declares a static source
- **Then** the check fails

#### Scenario: A row without an owner

- **Given** a row with no owner
- **When** the contract is checked
- **Then** the check fails

#### Scenario: The site that builds its own source

- **Given** a row whose owner is the field site itself
- **When** the site constructs the source
- **Then** the check accepts it, and the exception is read off the row

### Requirement: REQ-TI-REGISTRY-3

A row that names a file which no longer owns a field SHALL fail the check.

A stale row is a contract with nobody: it describes a file that has moved on.

#### Scenario: A row for a file that no longer owns a field

- **Given** a row in the inventory for a file that owns no text field
- **When** the contract is checked
- **Then** the check fails

#### Scenario: A row for a file that no longer owns a bridged field

- **Given** a row in the rewrite table for a file that owns no bridged text field
- **When** the contract is checked
- **Then** the check fails

### Requirement: REQ-TI-REGISTRY-4

A published table SHALL be read by its header.

A reader SHALL NOT read a row of another table, and a table whose header changed SHALL
fail loudly rather than yield no rows.

#### Scenario: Two tables in one document

- **Given** a document that holds several tables
- **When** a table is read by its header
- **Then** only the rows of that table are returned

#### Scenario: A header that changed

- **Given** a table whose header is not the one the reader looks for
- **When** the reader looks for it
- **Then** the read fails, naming the header it looked for
