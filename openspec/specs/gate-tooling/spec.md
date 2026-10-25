# gate-tooling Specification

## Purpose
The gate suite runs on a machine that has never run it, and a waiver's
premise is a checked fact rather than a sentence. A gate that cannot run
must not look like a gate that has nothing to report.

## Requirements

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

### Requirement: REQ-TOOLING-3

A gate run SHALL measure the checkout it is run in.

The tooling the gates use SHALL resolve to the checkout being verified, and not to
another checkout's copy of it. Where the origin of the tooling can be inspected,
it SHALL be inspected before the suite runs, and when it is not this checkout's
the report SHALL name the path it resolved to and SHALL exit non-zero.

A checkout-local environment SHALL be a supported way to satisfy this.

**Rationale:** the failure this prevents is silent. The suite runs, every gate
passes, and the gates are not the ones this checkout declares. A check that
measures another checkout's registry is worse than no check, because it is green
and it is green about the wrong thing.

#### Scenario: The tooling belongs to another checkout

- **Given** the gate tooling resolves to a path outside this checkout
- **When** the preflight runs
- **Then** it fails
- **And** it names the path the tooling resolved to

#### Scenario: The tooling belongs to this checkout

- **Given** the gate tooling resolves to this checkout's own copy
- **When** the preflight runs
- **Then** it reports the tooling as present
- **And** it exits zero

#### Scenario: The gates run from the shell registry

- **Given** the gate suite is run through the shell registry
- **When** a gate that uses the gate tooling runs
- **Then** the tooling it uses is this checkout's

### Requirement: REQ-TOOLING-4

A file derived from the source SHALL be checked against the source it is derived
from.

A check on a derived file SHALL fail when the derived file no longer matches the
source. It SHALL run before the long-running verification, so that a stale
derived file is a fast failure rather than a late one. When it fails, it SHALL
name the command that brings the derived file up to date.

**Rationale:** a derived file that nothing checks is a file that is always the
stale one, and it is always plausible — it reads like documentation, not like a
bug.

#### Scenario: The derived file is current

- **Given** the derived file matches what the source produces
- **When** the check runs
- **Then** it passes

#### Scenario: The derived file has drifted

- **Given** the derived file no longer matches what the source produces
- **When** the check runs
- **Then** it fails
- **And** it names the command that regenerates the derived file

### Requirement: REQ-TOOLING-5

A failure SHALL name its cause at the place where it broke.

When the cause of a failure is a form of the source that the reader cannot
parse, the reader SHALL report that form, at the point of reading, rather than
leaving the failure to be reported elsewhere as a misreading.

**Rationale:** a message that names the cause turns a hunt into a one-line fix.
The same failure reported from three files away is a hunt, and a hunt costs a
debugging session.

#### Scenario: The source is in a form the reader cannot parse

- **Given** a description is written in a form the catalogue reader does not
      parse
- **When** the catalogue is checked
- **Then** the reader reports which form it found
- **And** it says what a description is expected to look like

### Requirement: REQ-TOOLING-6

A gate run SHALL have a summary that answers what failed and why.

The summary SHALL print one line per failed gate, naming the gate and the reason
it failed, and one line for the result of the run.

**Rationale:** a wall of output is not a diagnosis. The person reading a failed
run wants the name of the gate and the reason in one line, and the runner already
knows both.

#### Scenario: A gate fails

- **Given** at least one gate fails
- **When** the suite is run with the summary
- **Then** one line names the failed gate and the reason it failed

#### Scenario: Every gate passes

- **Given** every gate passes
- **When** the suite is run with the summary
- **Then** one line reports how many gates passed
- **And** no line reports a failure

### Requirement: REQ-TOOLING-7

A rule that is enforced by a gate SHALL have a control that proves the gate can
fail.

The control SHALL mutate a committed input of the gate and the gate SHALL fail as
a result. A control that changes nothing SHALL count as a failed control.

**Rationale:** a gate that cannot fail reports success forever, and a control
that cannot fail reports the gate is covered forever. Both are the same defect
seen from two sides.

#### Scenario: The control runs

- **Given** a gate with a registered control
- **When** the control runs
- **Then** the gate fails on the mutated input
- **And** the mutation is undone afterwards
