# Text Input — Observable Behavior

**capability:** `text-input` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-TI-SHAPE-1

The shape of a text field SHALL be derived from the code that owns it, and the
derivation SHALL NOT read the code's text.

A file's shape SHALL be determined by the symbols it **imports**:

- an import of the bridged helper means the file is *bridged*;
- an import of the anchored helper means the file is *anchored*;
- an import of the local state holder means the file is *local*.

A mention of a helper in a comment or in documentation SHALL NOT classify a file, and
a file that owns a field SHALL NOT be able to hide behind such a mention.

#### Scenario: A file that owns a bridged field

- **Given** a file that uses the bridged helper
- **When** the shape is derived
- **Then** the file is *bridged*, because it imports the helper

#### Scenario: A file that only mentions a helper

- **Given** a file whose documentation names the bridged helper
- **And** which does not import it
- **When** the shape is derived
- **Then** the file has no shape — it owns no field

### Requirement: REQ-TI-SHAPE-2

The helpers that decide a field's shape SHALL live in a package that holds no fields.

A file SHALL NOT be able to use a helper without importing it, so that the import is
always the evidence of the shape.

#### Scenario: A field in another package

- **Given** a file that owns a text field
- **When** it calls the bridged helper
- **Then** it imports the helper, and the import names the shape

#### Scenario: The helpers' own package

- **Given** the package that holds the helpers
- **When** its files are scanned for field sites
- **Then** they are excluded by their path, and the package holds no fields

### Requirement: REQ-TI-SHAPE-3

The rewrite contract of a bridged field site SHALL be checked against the code's
structure.

The sites SHALL be the files that import the bridged helper, the owner's source SHALL
be a property with a type, the counter SHALL be a property with a name, and the
bridge's public parameters SHALL be names.

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

#### Scenario: A count that never advances

- **Given** a site whose publication claims an owned source
- **When** no declaration of the file that advances the count advances it
- **Then** the check fails

#### Scenario: A comment that talks about a count

- **Given** a file whose documentation mentions a count that moves
- **And** no declaration of it advances the count
- **Then** the check fails
