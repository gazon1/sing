# Bulk Import Boundary — Observable Behavior

**capability:** `bulk-import` | **status:** proposed

---

## ADDED Requirements

### Requirement: REQ-BI-001

A bulk import **MUST** write only to the data of the owner it was given, and
**MUST NOT** read, infer, or alter which owner is currently active.

**Rationale:** the importer deliberately bypasses the ordinary per-write
ownership guard, because a restore legitimately targets an owner other than the
active one. That exemption is only safe if the target is explicit and the active
owner is genuinely irrelevant — including during the import.

#### Scenario: Import for a non-active owner
- An import is given owner B while owner A is active
- Every written row belongs to owner B
- Owner A remains active throughout, and owner A's data is untouched

---

### Requirement: REQ-BI-002

A bulk import **MUST** be all-or-nothing: if any part of it cannot be
completed, **MUST** leave the system as it was before the import began.

**Rationale:** the previous behavior wrote row by row and could not fail partway.
Introducing a boundary that can fail must not introduce partial state, because a
half-restored install plus an intact backup is a worse outcome than no restore
at all.

#### Scenario: A failure leaves no partial state
- An import fails partway through
- The system contains none of the imported data
- The pre-import state is intact

---

### Requirement: REQ-BI-003

A bulk import **MUST NOT** reject imported content for violating domain rules.
Imported content **MUST** be stored as supplied.

**Rationale:** the backup is the user's data. A restore that refuses data because
it fails an invariant leaves the user with neither the backup nor the data, and
the invariant that rejected it is one the application itself may later relax.

#### Scenario: Content that the application would not accept is still restored
- A backup contains a row that does not satisfy a current domain rule
- The import completes
- The row is present as supplied, and the application reports it as invalid
  rather than discarding it

---

### Requirement: REQ-BI-010

Any code path that performs a bulk import **MUST** go through the declared
import boundary. A path that bypasses it **MUST** be named explicitly in the
registry of sanctioned bypasses.

**Rationale:** the exemption cannot be detected syntactically — a legitimate
owner-scoped write and an illegitimate cross-owner write look identical at the
call site, which is why a detection rule was measured and rejected. What *can* be
detected is a new bulk path appearing without a decision. The registry fails when
the set of callers changes, not when a new cross-owner write appears.

#### Scenario: A new bulk path is noticed
- A new code path performs a bulk write outside the import boundary
- The check fails and names the path
- The path is either routed through the boundary or added to the registry with a
  stated reason

---

### Requirement: REQ-BI-011

The registry of sanctioned bypasses **MUST** state, for each entry, what the
bypass is for.

**Rationale:** a name with no reason is a suppression. Every entry records why the
ordinary guard does not apply, so a reader can tell a deliberate exemption from an
accumulated one.

#### Scenario: Every registered bypass is justified in place
- The registry is read
- Each entry carries a statement of why the ordinary write guard does not apply
