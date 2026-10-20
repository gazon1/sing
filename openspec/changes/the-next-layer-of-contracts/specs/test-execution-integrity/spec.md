# test-execution-integrity

**capability:** `test-execution-integrity` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-15 A version pin lives in one file

Each tool version the project pins SHALL live in exactly one file, and every
consumer of that version SHALL read it from there. A pin written out in two
places is a fact with two owners, and the two owners drift: the developer's
tool and the runner's tool disagree, and the disagreement shows up as "it
passes here and fails there".

The single source SHALL be machine-readable and SHALL be checked: a test SHALL
fail when a pinned version literal appears anywhere but in that file. A single
source that nothing checks is a single source that the next contributor edits
in place, which is the moment the second copy comes back.

#### Scenario: A version is bumped

- **Given** the pins file declares the version of a tool
- **When** the version is bumped
- **Then** the local install path and the CI install path both pick it up
- **And** the test that guards the single source still passes

#### Scenario: A pin is written in a second place

- **Given** a pinned version literal appears outside the pins file
- **When** the test runs
- **Then** it fails and names the file that holds the duplicate

### Requirement: REQ-16 The tooling preflight runs where the tooling is used

A preflight that checks the gate tooling is installed SHALL run on every
surface that installs the tooling and then runs the gate suite — the local loop
and CI alike.

A preflight that runs only on the local loop turns a broken tooling install
into a traceback inside a gate that was checking something else, several steps
after the cause. The preflight names the missing tool, before the first gate
runs.

The preflight SHALL run in the CI job that installs the complete toolset and
runs the gate suite. A job that deliberately installs a smaller toolset SHALL
not run the full preflight; the reason SHALL be written down where the job is.

#### Scenario: A tool is missing on a runner

- **Given** a CI job installed the gate tooling and one of the tools is absent
- **When** the preflight runs
- **Then** the job fails and names the missing tool
- **And** it fails before the gate suite runs

### Requirement: REQ-17 A waiver points at something that resolves

Every exemption in the gate registry — a gate allowed to have no positive
control — and every declared CI/local asymmetry SHALL carry a **reference** to
the thing that makes it acceptable: the file that does the proving, or the
place where the asymmetry is visible.

The reference SHALL be machine-checked. A file that does not exist, and a
workflow anchor that is not a step name, SHALL be a gate failure. A sentence
that names a proving file does not break when the file is renamed; a pointer
does.

The same applies to a rule about source code: the rule SHALL be read from the
source, not from its text. A rule enforced by a substring search reports the
file that documents the rule as well as the file that breaks it, and the gate
that owns it then cannot be proven to fail — which is how a rule stops being
enforced while the build stays green.

A gate that is exempt because it "is red on a clean tree" SHALL have that
exemption **replaced by a control** once the gate is green, so that the claim
becomes "breaking this makes it fail".

#### Scenario: The proving file is renamed

- **Given** an exemption names the file that does the proving
- **When** that file is renamed
- **Then** the gate that resolves the references fails and names the exemption

#### Scenario: A rule about imports is checked

- **Given** a gate script that imports argparse
- **When** the conventions gate runs
- **Then** it reports the file and the line
- **And** a file that only mentions the words "import argparse" in a comment is
  not reported

#### Scenario: A gate that was exempt because it was red

- **Given** a gate is green on a clean tree
- **And** it has a sabotage control
- **When** the control's mutation is applied
- **Then** the gate fails

### Requirement: REQ-18 A test double lives with the other test doubles

A test double SHALL live in the package the architecture scans read as "not
production" — `test/fakes/` — and not in a production package. The scans that
read the production tree exclude the doubles' home; a double in a production
package is scanned as production code, and the unwired-surface detector has to
carry an exemption for it to keep its finding count honest.

An exception that only a test double throws SHALL live with that double, and
the port it used to sit next to SHALL describe what the platform actually
does — not what one implementation happened to do.

#### Scenario: A double is in a production package

- **Given** a test double is declared in a production package
- **When** the unwired-surface detector runs
- **Then** it reports the double as a surface with no production reference
- **And** the double's home is `test/fakes/`, where the other doubles live
