# gate-tooling — Observable Behavior

**capability:** `gate-tooling` | **status:** accepted

---

## ADDED Requirements

### Requirement: REQ-TOOLING-1

The gate suite's tools SHALL be installable with one command, and that command
SHALL use the same installer and the same versions CI uses.

The installer SHALL create one virtual environment and put the gate CLI, the
Junit parser, the link checker, the YAML linter, the shell linter and the
workflow linter into it.

The local package SHALL be **installed**, not merely reachable through
`PYTHONPATH`: the gate scripts' own unit tests run the scripts with a minimal
environment, so a package that is only on `PYTHONPATH` is invisible to them
and they fail with an import error that reads like a broken test.

#### Scenario: A machine that has never run the gates

- **Given** a checkout with uv installed
- **When** the local tools script runs
- **Then** one virtual environment holds every tool the gate suite shells out
      to
- **And** the gate CLI is importable from that environment

#### Scenario: The unit tests run the scripts with a minimal environment

- **Given** the gate CLI is installed into the environment
- **And** it is not on `PYTHONPATH`
- **When** the gate scripts' own unit tests run
- **Then** the scripts import the gate CLI successfully

### Requirement: REQ-TOOLING-2

A missing tool SHALL be a loud failure that names the fix, never a skip.

Before the gate suite runs, the tools it shells out to SHALL be checked. A
missing tool SHALL be reported by name, SHALL say what needs it, and SHALL
print the command that installs it. The check SHALL exit non-zero.

#### Scenario: A tool that is not installed

- **Given** the link checker is not on `PATH`
- **When** the preflight runs
- **Then** it reports the link checker as missing
- **And** it prints the command that installs it
- **And** it exits non-zero

#### Scenario: Every tool is installed

- **Given** every tool the gate suite needs is present
- **When** the preflight runs
- **Then** it reports every tool as present
- **And** it exits zero

### Requirement: REQ-WAIVER-1

A waiver's premise SHALL be machine-checked.

A control that is waived from the clean-run guard points at the thing that
has to change. That reference SHALL resolve: the file it names SHALL exist,
and the anchor it names SHALL be in that file.

For a workflow, the anchor SHALL name a **step**. A string that occurs in the
file but only inside a comment is not the step the reference points at.

#### Scenario: The anchored step has been renamed

- **Given** a waived control's reference names a step in a workflow
- **And** the step has been renamed, and the old name survives only in a
      comment
- **When** the waiver gate runs
- **Then** it fails
- **And** it says that no step carries that name

#### Scenario: The referenced file is gone

- **Given** a waived control's reference names a file
- **And** the file does not exist
- **When** the waiver gate runs
- **Then** it fails
- **And** it names the file that is missing

### Requirement: REQ-WAIVER-2

The compensating pass a waiver was granted for SHALL still be there.

A waiver that promises "the task runs on the other surface, a step later"
makes a claim about two surfaces. Both halves SHALL hold: the task SHALL run
in the anchored step, and the local surface SHALL name it too.

#### Scenario: The step no longer runs the task

- **Given** a waived control proves a Gradle task
- **And** the anchored step exists but does not run that task
- **When** the waiver gate runs
- **Then** it fails
- **And** it says the compensating pass is gone

#### Scenario: The local surface no longer names the task

- **Given** a waived control proves a Gradle task
- **And** the local check script does not name that task
- **When** the waiver gate runs
- **Then** it fails
- **And** it says the local rehearsal of the compensating pass is gone

### Requirement: REQ-WAIVER-3

A waiver with an expiry date SHALL be reported once the date has passed, and
the two copies of the controls SHALL agree about which controls are waived.

#### Scenario: The expiry date has passed

- **Given** a waived control's expiry date is in the past
- **When** the waiver gate runs
- **Then** it fails
- **And** it says the waiver has to be renewed or the gate fixed

#### Scenario: The two copies disagree

- **Given** the registry waives a control the wiring twin does not
- **When** the waiver gate runs
- **Then** it fails
- **And** it names the controls that differ
