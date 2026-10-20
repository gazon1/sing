# test-execution-integrity

## MODIFIED Requirements

### Requirement: REQ-18 "Nothing ran" is reported as its own verdict

A gate that compares a measurement against a floor SHALL distinguish, in its
reported verdict and in its exit code, between:

- a run that happened and breached the floor (**regression**),
- a run that happened and reported **zero tests**, and
- the absence of a run (**nothing ran**).

A report that **exists** and reports zero tests is a regression, not an absence: the
run happened, the run was empty, and an empty run is the most severe form of a
breach — the whole suite went missing and the report says so. It SHALL be reported
with the regression's exit code, and the message SHALL say that the report exists.

Absence SHALL NOT be reported as a regression, and SHALL NOT be reported as the
absence of individual test classes derived from the same absence.

**Rationale:** measured 2026-10-16 (#683). `check-test-runs.py`, run in a worktree
with no results on disk, reported `Test runs below the recorded floor — a suite
stopped running` and then named all 326 declared `@Tag("fast")` classes as having
produced no JUnit report. Every sentence was true and none of them was the fact:
no run had happened, so no floor was compared and no class was individually
skipped. The volume of true-but-irrelevant output is what makes a message read as
catastrophe, and it sends the reader to investigate a regression that does not
exist. `EXIT_NO_RUN` is distinct from the regression code so a caller that reads
only the exit code is not misled either.

The three-way split was not visible in the two-way one, because "no report" and
"a report with no tests" both arrived as a count of zero. They are different
investigations: the first is a missing run — a machine that never got to it — and
the second is a run that executed nothing, which is either a suite that stopped
selecting its tests or a report written by a process that died before its first
assertion.

#### Scenario: A required source set has no results

- **Given** a source set passed to `--require` with no result directory on disk
- **When** the gate runs
- **Then** it SHALL exit `EXIT_NO_RUN`
- **And** it SHALL say that nothing ran and name the command that would produce
  the results
- **And** it SHALL NOT report a floor breach

#### Scenario: A required source set has an empty results directory

- **Given** a source set passed to `--require` whose results directory exists but
  holds no report
- **When** the gate runs
- **Then** it SHALL exit `EXIT_NO_RUN`
- **And** it SHALL NOT describe the count as `0 tests` against a floor, which
  reads as a loss of the entire suite

#### Scenario: Nothing ran and the by-results check would also fire

- **Given** a required source set with no results
- **When** the gate runs
- **Then** it SHALL NOT additionally report every declared class as missing
- **And** the missing-class check SHALL be skipped for that source set

#### Scenario: A run happened and breached the floor

- **Given** a source set whose results are present and below the recorded floor
- **When** the gate runs
- **Then** it SHALL exit `1` and report the regression

#### Scenario: A report exists and reports zero tests

- **Given** a source set whose results are present and whose report says zero
  tests
- **When** the gate runs
- **Then** it SHALL exit `1` and report the regression
- **And** the message SHALL say that the report exists
- **And** it SHALL NOT be reported as "nothing ran"

## ADDED Requirements

### Requirement: REQ-19 A floor that the suite has outgrown is a finding too

A committed floor SHALL be enforced in **both** directions. A run that delivers
less than the floor SHALL fail, and a run that delivers **more** than the floor
SHALL fail as well, naming the direction and the numbers.

Raising the floor SHALL be a deliberate act, performed by a tool that writes the
measured counts into the floor's generated block, and the tool SHALL refuse to
lower the floor without an explicit override, SHALL refuse a run with skipped
tests, and SHALL leave the floor's prose untouched.

**Rationale:** the instrumented floor recorded five classes and nineteen tests
while the tier delivered six and twenty-one. The gate compared 21 against 19,
found no drop, and stayed green. A floor is a statement about the present, and a
statement that is false is not evidence — it is the absence of evidence with the
authority of a record. The instrumented tier has exactly one configuration, so a
run that delivers more than the floor cannot be a fuller run: it can only mean
the floor is behind. The JVM floor keeps its one-sided semantics, because there
a fuller run is legitimate.

#### Scenario: The suite grew and the floor did not

- **Given** the floor records six classes and twenty-one tests
- **When** a run delivers seven classes and twenty-four tests
- **Then** the gate SHALL fail and say that the floor is behind the suite
- **And** it SHALL name the measured counts and the recorded ones

#### Scenario: The floor is raised deliberately

- **Given** a run that delivered more than the floor
- **When** the floor is updated with the tool that writes it
- **Then** the recorded counts become the measured ones
- **And** the floor's prose is left as it was

#### Scenario: The floor is lowered

- **Given** a run that delivered less than the floor
- **When** the floor is updated without the override
- **Then** the tool SHALL refuse and the recorded floor SHALL be unchanged

#### Scenario: The run skipped a test

- **Given** a run that skipped a test
- **When** the floor is updated
- **Then** the tool SHALL refuse

### Requirement: REQ-20 The device tier answers on every pull request, and never blocks a merge

Every push to a pull request SHALL run the device tier in a **smoke** shape: one
test class, and the questions that class asks — does the app start, is the shell
reachable, do the test tags reach the accessibility tree.

The smoke SHALL be **advisory**. It SHALL NOT fail the pull request, and it SHALL
declare itself advisory where a reader of the workflow looks.

The full instrumented suite SHALL run on the nightly, on every push to `main`, on
tags, on manual dispatch, and on a pull request that asks for it by label. The
workflow SHALL decide which of the two it is running in **one** place, and every
later step SHALL read that decision.

The instrumented source set SHALL be compiled on every pull request. A source set
that nothing compiles is a source set that rots, and the discovery of a broken
instrumented test is then the nightly's job — days later, and blamed on the
nightly.

**Rationale:** the tier used to run on a timer only, so a pull request that broke
the app on a device was indistinguishable from one that did not until the next
nightly, and the instrumented sources were compiled by nothing in the pull-request
pipeline at all. The emulator stays out of the merge decision — it is the
flakiest thing in this CI, and a flaky gate teaches people to ignore it — but the
information reaches the pull request while the pull request is still open.

#### Scenario: A pull request is pushed

- **Given** a pull request without the `run-device-tests` label
- **When** the push is received
- **Then** the device tier runs the smoke tier
- **And** the smoke tier cannot fail the pull request

#### Scenario: A pull request asks for the full suite

- **Given** a pull request carrying the `run-device-tests` label
- **When** the label is added
- **Then** the device tier runs the full instrumented suite
- **And** the tier that is running is decided in one place, not per step

#### Scenario: The label is missing from the repository

- **Given** a repository in which the `run-device-tests` label does not exist
- **When** a pull request is pushed
- **Then** the workflow SHALL run the smoke tier
- **And** it SHALL NOT fail for the label's absence

#### Scenario: The instrumented sources do not compile

- **Given** a pull request that changes an instrumented test
- **When** the pull-request pipeline compiles the Android module
- **Then** the instrumented source set is compiled as well
- **And** a broken instrumented test fails the pipeline

#### Scenario: The smoke tier runs

- **Given** the smoke tier ran one class on purpose
- **When** the instrumented floor is checked
- **Then** the floor is not measured against the smoke's report

### Requirement: REQ-21 A wait's budget is a property of the element's kind

Every wait in the Maestro flows that names an element SHALL carry a timeout, and
that timeout SHALL be the budget of the element's **kind**. The budget for a kind
SHALL be written in one committed catalogue, together with the mapping from the
flows' element ids to kinds, and a gate SHALL fail when a wait disagrees with its
kind's budget, when a wait has no timeout, or when an element has no kind.

The catalogue SHALL name the *kind* of element — a menu, a text field, a list item,
a dialog — and not the flow that happens to wait for it.

**Rationale:** the flows carried 471 waits with eleven different timeouts, and the
same element was waited for with 5 s, 8 s, 10 s, 15 s and 30 s in different flows.
None of those numbers was derived from anything: they were copied, and a copy
carries no reason. A budget that belongs to the element's kind is the same number
everywhere, which is what makes it a property rather than a habit — and it makes
the outlier visible, because a wait that disagrees with its kind is either a
deliberate exception that can be written down, or a copy that nobody made
decisions about.

#### Scenario: A wait disagrees with its kind

- **Given** a kind whose budget is 8000 ms
- **When** a flow waits for an element of that kind with a timeout of 15000 ms
- **Then** the gate SHALL fail and name the element, the kind, the budget and the
  timeout used

#### Scenario: An element has no kind

- **Given** a flow that waits for an element that the catalogue does not classify
- **When** the gate runs
- **Then** the gate SHALL fail and name the element

#### Scenario: A wait has no timeout

- **Given** a flow that waits for an element without a timeout
- **When** the gate runs
- **Then** the gate SHALL fail and name the element

#### Scenario: Two flows wait for the same kind

- **Given** two flows that wait for elements of the same kind
- **When** the gate runs
- **Then** both waits carry the same timeout, because both carry the kind's budget

### Requirement: REQ-22 A flow's fixtures belong to that flow

The data a Maestro flow seeds SHALL be removed before the next flow starts, and the
removal SHALL be **selective**: it removes the rows that flow created, and leaves
the session, the profile and the other rows alone.

The cleanup SHALL name the seeded rows by a prefix that the app can match, and
the prefix that reaches the app SHALL be the prefix the titles actually start
with. A cleanup that names rows no title starts with removes nothing, and a
cleanup that removes nothing is indistinguishable from a cleanup that was never
sent.

A flow that fails SHALL keep its fixtures until the next flow starts, so that
the failure can be triaged against the data that caused it. It SHALL NOT keep them
for the rest of the run.

**Rationale:** the nightly of 2026-10-20 failed 43 flows across all four shards.
The screenshots showed tasks carrying the run ids of flows that had already
passed, in the same shard, minutes earlier. The cleanup link was built with shell
quotes and a pre-encoded space and then URL-quoted again, so the app read the
prefixes `"'r4797858390"` and `"Buy%20milk'"` — and the app matches titles by
prefix, so neither matched anything. Every fixture stayed on the device for the
rest of the shard, and a flow that asserts an empty list failed on the previous
flow's data. A suite whose failures cascade reports one defect as forty-three.

#### Scenario: A flow passes

- **Given** a flow that seeded fixtures under its own run id
- **When** the flow passes
- **Then** the fixtures are removed
- **And** the session, the profile and every other row are left alone

#### Scenario: A flow fails

- **Given** a flow that seeded fixtures and failed
- **When** the next flow starts
- **Then** the failed flow's fixtures have been removed
- **And** the artifacts of the failure — the screenshot, the screen tree, the
  logcat — have been captured

#### Scenario: The cleanup link is built

- **Given** a run id and a fixed fixture title
- **When** the cleanup link is assembled
- **Then** the prefixes the app reads are prefixes of the titles that were seeded
- **And** they carry no quoting that the app would not strip
