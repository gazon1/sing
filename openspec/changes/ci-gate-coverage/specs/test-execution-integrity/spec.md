# test-execution-integrity

## ADDED Requirements

### Requirement: REQ-9 A gate that is never invoked is not a gate

Every verification script under `scripts/` SHALL be invoked from at least one
CI workflow as a **blocking** step — a step whose failure fails the job. A script
reachable only from `check.sh` does not satisfy this requirement, because
`check.sh` is executed by no workflow and is the local loop a contributor runs on
their own machine.

CI SHALL additionally verify gate coverage itself: it SHALL fail when a script
matching the gate naming convention has no invocation in any workflow. The
coverage check is what stops the next gate from inheriting this silently, and it
SHALL refuse to pass when it inspected zero scripts — the same rule
`check-test-task-inputs.py` follows, for the same reason.

**Rationale:** four scripts are currently in exactly this state at `953b802e` —
`check-adr-references.py`, `check_adr_status.py`, `check_skill_frontmatter.py` at
zero invocations, and `check-detekt-registrations.sh` reachable only from
`check.sh`. All four are tested, which is why the gap survived: `scripts/tests/`
proves each gate detects its own failure and says nothing about whether anything
calls it. `check-detekt-registrations.sh` is the only check that a custom detekt
rule is registered and active, and two no-op rules reached `main` before it ran.

#### Scenario: A new gate script is added and wired

- **Given** a new `scripts/check-*.py` that is added to a CI workflow as a blocking step
- **When** the coverage check runs
- **Then** it passes, having inspected a non-zero number of scripts

#### Scenario: A new gate script is added and forgotten

- **Given** a new `scripts/check-*.py` that no workflow invokes
- **When** the coverage check runs
- **Then** it exits non-zero and names the script, before the gate it was added
  to protect can be the thing that fails

#### Scenario: The coverage check inspects nothing

- **Given** a change that breaks the discovery of gate scripts so the check
  inspects none
- **When** the coverage check runs
- **Then** it exits non-zero rather than reporting that every gate is covered —
  a gate that cannot fail is worse than no gate

### Requirement: REQ-10 A budget has one number, and the number is referenced

Each numeric limit in this repository — a document line budget, a coverage floor,
an executed-count floor — SHALL have exactly one authority: a named constant in
the script or build file that enforces it. Every other place that mentions the
limit SHALL reference or derive from that constant rather than restate the number.

A limit that appears in two places with different values SHALL be treated as a
defect, not as a warning. The two values define a band in which the enforcing
check is green and the reporting check says nothing useful, and that band is
unguarded by construction.

**Rationale:** `DIGEST.md` is currently checked at 1250 by
`scripts/check-doc-sizes.py` (blocking, exits non-zero, runs in `check.sh` and
`ci.yml`) and at 1500 by `.github/workflows/docs-audit.yml` (a bare `echo` that
cannot fail at any size). The file measures 1196 lines, so the next 250 lines can
accumulate with every surface green. The 1250 carries a comment naming its
sibling constant in `refresh-decisions-digest.py`; the 1500 is a stale copy of an
earlier limit.

#### Scenario: A limit is restated in a second file

- **Given** `DIGEST.md` is 1400 lines
- **When** CI runs
- **Then** `check-doc-sizes.py` fails at 1250, and no second check reports the
  same file as within limits

#### Scenario: A budget moves

- **Given** the digest budget is deliberately raised
- **When** the change is made
- **Then** the constant is changed once and the reporting step derives its
  threshold from it, so the two cannot disagree afterwards

#### Scenario: The advisory check is the only one that would notice

- **Given** a file inside the band between an enforcing limit and a restated one
- **When** both checks run
- **Then** the run fails on the enforcing limit, rather than passing while an
  advisory step prints a warning
