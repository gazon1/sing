# test-execution-integrity

## ADDED Requirements

### Requirement: REQ-7 A verification gate reports no finding that the codebase's own conventions cause

A gate in this repository SHALL NOT require a standing exemption for code that
conforms to a convention the same repository enforces. Where a detector's
notion of a defect does not match the language's, the detector is what changes.

The unwired-surface detector SHALL treat a symbol declared under
`shared/src/commonMain/.../test/fakes/` as production-referenced, because a test
double placed there is reachable from `commonTest` and is not dead code. The
three exemptions that arrangement required SHALL be removed from
`scripts/find-unwired-surfaces-baseline.txt`.

The gate SHALL keep a positive control: a test that declares a genuine unwired
surface and asserts the detector reports it. A detector that finds nothing and a
detector that is looking in the wrong place produce the same output, and only
the control distinguishes them.

**Rationale:** `MapFileSystem`, `FakeSecureStorage` and `FakeDraftStore` were
reported as unwired surfaces purely because they are doubles in `commonMain`.
Keeping them quiet cost a backlog entry whose only purpose was to give the
baseline lines a live reference. A gate that can only be satisfied by keeping a
document alive is a gate whose maintenance is the enforcement mechanism, and it
inverts the reason the gate exists.

#### Scenario: A genuine unwired surface is added

- **Given** `scripts/find-unwired-surfaces.py` exempts `test/fakes/`
- **When** a public `@Composable` is declared in `commonMain` with no call site
- **Then** the detector reports it
- **And** the gate fails

#### Scenario: A test double is added under test/fakes

- **Given** `scripts/find-unwired-surfaces.py` exempts `test/fakes/`
- **When** a `FakeWidgetStore` is declared in `shared/src/commonMain/.../test/fakes/`
  and is referenced only from `commonTest`
- **Then** the detector does not report it
- **And** no baseline line is required for it

#### Scenario: The exemption is removed but the positive control still passes

- **Given** the exemption in the detector has been deleted
- **When** the gate runs against a tree containing a genuine unwired surface
- **Then** the positive control fails
- **And** the removal cannot be mistaken for the gate working

#### Scenario: A baseline line is deleted by hand

- **Given** a baseline line is removed from
  `scripts/find-unwired-surfaces-baseline.txt` while its finding persists
- **When** the gate runs
- **Then** it fails
- **And** it names the file and symbol, so the exemption cannot be dropped
  silently
