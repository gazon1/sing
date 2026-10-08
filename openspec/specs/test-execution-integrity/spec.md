# test-execution-integrity

## Purpose

A test run is a measurement. A green build says *the assertions that ran passed* —
not that the suite ran at all, not that its coverage held, and not that the gates
watching it still work.

That distinction is not theoretical here. `-Ptest.tags=fast,slow` selected 16 of 218
test classes and CI stayed green for months: no navigation test and no desktop flow
test had ever run. The suite count is now gated, but the same class of defect has
three more faces — a test silently skipped, coverage silently lost, and a gate script
silently broken — and this spec pins all of them.

These requirements constrain the **verification posture**, not the product. No
requirement here changes what the app does.

## Requirements

### Requirement: REQ-1 Executed test counts are a floor, not a report

CI SHALL compare the number of executed tests per source set against a committed
floor, and SHALL fail when a source set runs fewer than the floor records.

The floor SHALL be the smallest count any legitimate run produces — the default
local run, which excludes `@Tag("slow")` — so that a fuller CI run is never
mistaken for a regression.

The number of executed test *classes* is no longer part of the floor. It was
subsumed by the by-results check in REQ-13, which fails when a declared class
produced no report — the same defect the class count existed to catch, caught by
name rather than by subtraction. Every other way a class count moves (a class
deleted, a class emptied, two classes merged) moves the test count too, so the
test count fires wherever the class count would have.

A tool that updates the floor SHALL refuse to record a lower count without an
explicit override, and SHALL say which source sets dropped. A drop means tests
stopped being selected, which is a defect to investigate; a tool that regenerates
one converts a caught defect into an accepted one.

**Rationale:** `includeTags` matches per class. A class can stop being selected —
an untagged class, a JUnit 4 class on the Vintage engine, a narrowed filter —
with no error and a green task. Gradle reports `BUILD SUCCESSFUL` for a run that
executed nothing. The class count was the blunt instrument for that; the
by-results check is the same guarantee stated per class, and keeping both meant
raising a floor whenever a test was added, which is friction that trains people
to regenerate it without reading it.

#### Scenario: A class loses its tag

- **Given** the baseline records 1785 tests for `shared:jvmTest`
- **When** a test class's `@Tag` is removed
- **Then** the verification gates fail, naming the class that produced no report
- **And** the test-count floor is also below its record

#### Scenario: Tests are added

- **Given** the run executes more tests than the floor records
- **Then** the gates pass, and the floor is raised in the same commit

#### Scenario: A partial run is mistaken for a measurement

- **Given** a results directory holding only the suites a filtered `--tests` run
  selected
- **When** the floor is updated
- **Then** the tool refuses, naming the source set and the drop
- **And** the recorded floor is unchanged

### Requirement: REQ-2 No test may be skipped silently

The same gate SHALL treat the skipped-test count as a **ceiling of zero** for every
source set, and SHALL fail when any test reports as skipped.

A skipped test is invisible to a count floor because JUnit counts a skipped testcase
inside `tests=` exactly like a passing one. `@Disabled` on a class, or an assumption
guard that starts failing, removes real coverage while the task stays green.

**Rationale:** `TaskOutgoingLinksTest` sat `@Disabled` for a month behind a fully
green task, with an ADR explaining why the cause had been misdiagnosed.

#### Scenario: A class is disabled

- **Given** a class covering 15 tests is annotated `@Disabled`
- **When** the suite runs
- **Then** the gate exits non-zero, naming the source set and the skipped count

#### Scenario: A conditional test stops applying

- **Given** a test guarded by an assumption that no longer holds
- **When** the suite runs
- **Then** the gate fails for the same reason — a missing test is still a missing test

### Requirement: REQ-3 Coverage is a floor over the project's own packages

CI SHALL compute instruction, branch and line coverage over the project's own packages
from the Kover XML report, and SHALL fail when any of the three falls below its
committed floor.

Coverage SHALL be measured from a report in which the main test task is instrumented.
Excluding a test task from instrumentation does not reduce coverage, it stops the
measurement — and the resulting number describes the Gradle configuration rather than
the code. Kover's instrumentation SHALL be filtered to the project's own packages
instead of being switched off wholesale, so third-party bytecode never enters the
accumulator.

Coverage SHALL be measured from a single report aggregated across every module's
test JVM in the build, not from a per-module report. A per-module report measures
that module's own test tasks against that module's own classes, which in a
multi-module build means a test in one module that drives another module's code
contributes nothing to the number.

The build SHALL expose one entry point that both runs the test tasks and produces
the report. The aggregation plugin only merges the test tasks that are already in
the task graph and never depends on them itself, so a task that only renders the
report would succeed on a clean checkout and write a nearly empty file.

**Rationale:** With `jvmTest` excluded from instrumentation the report claimed 10.5%
instruction coverage; with instrumentation filtered to `com.singularity.todo.*` the
same suite measured 23.0%. The first number was an artefact of the Gradle
configuration. Aggregating moved the same suite to 38.5% and brought 32 packages off
0% — 108,096 instructions of Compose surface that 27 passing flow tests had been
exercising all along while the per-module report could not see any of it.

#### Scenario: Coverage drops

- **Given** the recorded floor for instruction coverage
- **When** the measured value falls below it
- **Then** the gate exits non-zero and names the metric and the delta

#### Scenario: A test source set stops being measured

- **Given** a change that silently excludes a test task from instrumentation
- **When** the gate runs
- **Then** coverage falls below the floor and CI fails, because a vanished test task
  looks exactly like lost coverage

#### Scenario: The report is requested without the tests

- **Given** a clean checkout with no previous test run in the build directory
- **When** only the report task is requested
- **Then** the report is still produced and the gate passes, unless the entry point
  that runs the tests is the one used — a report merged from nothing is not a
  measurement, and a gate that accepts it is not a gate

### Requirement: REQ-4 The gates are themselves tested

Every verification script under `scripts/` SHALL have unit tests under `scripts/tests/`,
and the suite SHALL run as part of both `./check.sh` and CI.

A regression inside a gate script disables that gate silently — the same failure shape
the gates exist to detect, one level up. The tests SHALL cover the failure direction
(a drop, a skipped test, a missing report) as well as the passing direction, so a
gate that has stopped detecting cannot report success.

**Rationale:** `scripts/tests/` already existed with tests for
`find-unwired-surfaces.py`, and nothing ran them.

#### Scenario: A gate stops detecting

- **Given** a change that makes the test-count gate always return success
- **When** the gate's own test suite runs
- **Then** the drop-detection test fails

### Requirement: REQ-5 Flakiness is measured between runs, not assumed

The CI job SHALL publish the JUnit XML of every run, and SHALL compare each run against
the previous successful run on the same branch, reporting tests whose status flipped.

A test that fails in one run and passes in the next has proven itself nondeterministic
regardless of what either run reported. The comparison SHALL annotate the run rather
than mask it: a failure still fails the build.

Failures that are understood SHALL be recorded in
`config/docs/flaky-baseline.txt` with the reason. An unacknowledged failure SHALL fail
the analysis; an acknowledged one SHALL be reported.

**Rationale:** Two flakes in this repository's recent history were each observed once,
never reproduced, and ended up as prose in the deferred backlog rather than as a
signal.

#### Scenario: A test failed in the previous run and passes now

- **Given** the previous run recorded a failure for `TaskDetailCoordinatorGraphTest`
- **When** this run passes it
- **Then** the run summary lists it as a flake witness, and the build still reports its
  own result

#### Scenario: A new failure appears

- **Given** a test fails that is not in the flaky baseline
- **When** the analysis runs
- **Then** it exits non-zero and names the test

### Requirement: REQ-13 A claimed scenario that ran without reporting is a failure

A result matrix SHALL distinguish a target that **was never run** from a claimed
scenario that **ran and produced no result**, and SHALL fail only the second.

The decision this encodes: *a claim is a claim when the spec lists the target and the
invocation was given result directories for it.* A scenario whose target is not
claimed is a coverage gap to render, not an error. A target this invocation was never
asked to cover is a fact, not a failure — a desktop-only local run must not fail over
Android, or a partial run becomes unexpressible.

**Rationale:** The per-target rule catches a suite that ran and produced nothing, but
it is blind to the shape a tag filter actually produces: a target that produced 200
testcases passes it, even when the one testcase carrying a scenario id was filtered
out. The build is green, the coverage cell renders as not-run, and nothing ever
fails — the same quiet-green class as REQ-1, one level down.

A **reported** result is what counts, not a passing one. A scenario that failed or was
skipped has given a real answer about the code and SHALL NOT be reported as missing;
a quarantined test and a class that silently vanished warrant different responses, and
collapsing them would hide the second.

#### Scenario: The scenario's class is filtered out of a run that otherwise passed

- **Given** a spec claims `desktop` and a run for `desktop` produced results
- **And** the test carrying that scenario's id is absent from the results
- **Then** normalisation exits non-zero and names the scenario and target

#### Scenario: The scenario ran and failed

- **Given** the test carrying that scenario's id is present and failed
- **When** normalisation runs
- **Then** it succeeds, and the matrix renders the failure

#### Scenario: The target was never part of this invocation

- **Given** a spec claims both `android` and `desktop`
- **And** results were supplied for `desktop` only
- **Then** normalisation succeeds, and the `android` cell renders as not-run

### Requirement: REQ-13 A declared test class that produced no report fails the build

A verification gate SHALL compare the test classes the sources declare against the
classes that produced a report in the run, and SHALL fail when a declared class is
absent from the results.

The comparison SHALL be scoped to classes the sources declare as `@Tag("fast")`. A
source set with no such declaration is out of scope for this requirement and SHALL NOT
be reported by it.

The declared-class list SHALL be produced by the same scanner the Kiwi stand uses, so
that "is this class runnable" has one implementation in the repository rather than one
per consumer.

**Rationale:** a count floor compares a run against a number recorded earlier, so it is
blind to a class that did not exist when that number was written. Adding a test and
having it silently skipped lowers nothing the floor can see, which is how two
`@ParameterizedTest` classes sat untagged while CI ran `-Ptest.tags=fast,slow` and
reported success. This requirement asks the direct question instead: not "did fewer
things run than last time" but "did this specific declared class run". That is a fact
about the run rather than a judgement about the source, so a JUnit annotation form no
text predicate knows about is still caught by it. `fast` and not `slow` is a
correctness argument, not convenience: CI always passes an explicit tag list, so
"did a declared `fast` class run" is the question that matters there, and
`TestTagCoverageTest` separately holds the untagged population at zero — which
is what makes `fast` the whole of what CI should have run. The default local run
excludes `slow` but also runs untagged classes, so it is a superset of `fast`
rather than equal to it; that is why `slow` is out of scope, since a `slow` class
is excluded from every local run by design.

#### Scenario: A declared class is silently skipped

- **Given** a source set whose tests all declare `@Tag("fast")`
- **And** a class in those sources whose report is absent from the run's results
- **When** the verification gates run
- **Then** they fail
- **And** the failure names that class

#### Scenario: Every declared class reports

- **Given** a source set whose tests all declare `@Tag("fast")`
- **When** every one of them produces a report
- **Then** the gates pass

#### Scenario: Report names differ in shape between modules

- **Given** two source sets that write suite names differently, one as a simple class
  name with a per-target suffix and one as a fully-qualified name
- **When** the gates compare declared classes against reports
- **Then** both shapes satisfy a declared class name
- **And** a suite name that differs only in that shape is not reported as missing

#### Scenario: A slow class is not part of the comparison

- **Given** a source set containing a class declared `@Tag("slow")`
- **And** a run that excluded it
- **When** the verification gates run
- **Then** they do not report that class as missing

### Requirement: REQ-14 A gate that cannot evaluate its check fails the build

A verification gate SHALL fail when it cannot evaluate a check the build requires,
and SHALL NOT report success for a check it did not run.

"Cannot evaluate" SHALL be distinguishable from "has nothing to evaluate". A source
set the gate has no opinion about SHALL be skipped; a source set the gate is required
to judge and is unable to judge SHALL be a failure.

**Rationale:** the check in REQ-13 depends on a scanner in another module. An earlier
implementation represented an unavailable scanner as "nothing to check", and the caller
skipped the source set — so appending one failing `import` line to that module made the
gate print that its floors were met and exit zero, with the check not running at all.
A gate that reports success for work it skipped is indistinguishable, from outside, from
a gate that passed, and it is the cheapest possible way to reintroduce the exact defect
the gate exists to catch. This is the same lesson REQ-4 records for the gate scripts
themselves, applied to a gate's dependencies.

#### Scenario: The check's dependency cannot be loaded

- **Given** a required scanner that cannot be imported
- **When** the verification gates run
- **Then** they fail
- **And** the failure states that the check could not run and why

#### Scenario: A source set outside the gate's scope

- **Given** a source set the gate has no configured sources for
- **When** the verification gates run
- **Then** they do not report that source set as unchecked
- **And** they do not report it as a failure

### Requirement: REQ-15 A floor entry is rewritten only from a measurement

A tool that updates the recorded test-run floor SHALL write an entry only for a source
set that produced results in the invocation, and SHALL preserve the existing entry for
every source set that did not.

When an entry is written with counts higher than the entry it replaces, the tool SHALL
report that increase and SHALL state which run configuration the floor requires.

**Rationale:** an earlier implementation emitted only the source sets it happened to
observe, so running it on a machine without a device deleted the floor for a source set
it had never run. That is the failure the floor file's own header already documents — a
number that was never a measurement of a real run, which then failed a CI job on its
first use. A floor that can be deleted by regenerating it is not a floor. The second
property exists because the recorded value must be the *smallest* count any legitimate
run produces: a value taken from a wider run makes every ordinary local run look like a
regression, which is how an unreachable floor was recorded in the first place.

#### Scenario: Regenerating without a device

- **Given** a floor file with an entry for a source set that produces results only on
  a device
- **When** the floor is updated on a machine with no device
- **Then** that entry is preserved unchanged
- **And** the tool reports that it was carried over rather than measured

#### Scenario: Counts rise

- **Given** an existing floor entry
- **When** the floor is updated from a run whose counts are higher
- **Then** the tool reports the increase
- **And** the report states the run configuration a floor requires

#### Scenario: Counts fall

- **Given** an existing floor entry
- **When** the floor is updated from a run whose counts are lower
- **Then** the tool does not report it as a rise
