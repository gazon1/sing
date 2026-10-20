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
subsumed by the by-results check in REQ-23, which fails when a declared class
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

### Requirement: REQ-14 A gate that cannot evaluate its check fails the build

A verification gate SHALL fail when it cannot evaluate a check the build requires,
and SHALL NOT report success for a check it did not run.

"Cannot evaluate" SHALL be distinguishable from "has nothing to evaluate". A source
set the gate has no opinion about SHALL be skipped; a source set the gate is required
to judge and is unable to judge SHALL be a failure.

**Rationale:** the check in REQ-23 depends on a scanner in another module. An earlier
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

### Requirement: REQ-16 A CI step's reported status is its command's status

A CI job step SHALL report failure when the command it runs fails.

Where a step's command is a pipeline, the step SHALL arrange for the pipeline's
exit status to be the status of the **command that can fail**, and not of a
trailing `tee`, `cat`, or similar. Enabling `pipefail`, or asserting
`${PIPESTATUS[0]}` explicitly, are the two forms that satisfy this.

A step that is intended to be advisory SHALL declare it as advisory — through
`continue-on-error` or an explicit `|| true` — and SHALL NOT use a declaration
as a substitute for fixing a failure the step surfaced.

**Rationale:** `ci.yml` ran its entire Gradle phase as
`./gradlew … 2>&1 | tee build.log`. GitHub executes `run:` with `bash -e {0}`,
which sets errexit but **not** `pipefail`, so the pipeline returned `tee`'s exit
status — always 0. The step reported success for every Gradle outcome, including
`:shared:detekt FAILED` and `:androidApp:detekt FAILED`; run `38902346697` is
green and contains both. Every other requirement in this spec constrains a check
that *runs* — and all of them were downstream of a step whose failures could not
be reported at all.

This is the same defect class as REQ-13 and REQ-14 one level up. Those make a
check that runs be unable to fail; this makes the *report* of the check unable to
be wrong. It is strictly worse, because every other invariant here depends on it.

The second property exists because `|| true` was doing two jobs at once. On
`ci.yml` it marked a summary-rendering step advisory, and it also swallowed a
genuine Gradle validation error
(`:adr-core:koverGenerateArtifact` using `:adr-core:compileKotlinJvm`'s output
without a declared dependency). The marker was not lying — the step is advisory —
but it was hiding a real defect behind a declaration that looked honest.

#### Scenario: A failing build behind a pipe

- **Given** a CI step whose command is `./gradlew … 2>&1 | tee build.log`
- **When** `./gradlew` exits non-zero
- **Then** the step SHALL be reported as failed
- **And** the log file SHALL still contain the build output

#### Scenario: A pipeline with an intentionally failing member

- **Given** a step whose pipeline contains a command expected to fail
- **When** the step runs
- **Then** the step SHALL report the status of that command unless it declares
  itself advisory

#### Scenario: An advisory step surfaces a real failure

- **Given** an advisory step whose underlying command reports a genuine defect
- **When** the step runs
- **Then** the defect SHALL be recorded where a later step or issue can act on it
- **And** an advisory marker SHALL NOT be the only record of it

### Requirement: REQ-17 One Gradle invocation's test population is the job's population

Within a CI job, every Gradle invocation capable of running test tasks SHALL select
the same test population. An absent `-Ptest.tags` is a selection of its own — the
Gradle default, which every module in this repository maps to `excludeTags("slow")`
— and SHALL NOT be used where the job selects another population.

A step that reads `*/build/test-results/` to decide anything SHALL NOT be preceded
by a Gradle invocation that can rewrite it without the job's selection.

**Rationale:** measured 2026-10-16 on run `38933517902`. `check-test-runs` counted
`desktopApp:test: 44 classes, 135 tests, 0 skipped`, and the traceability step,
nine steps later, exited 2 with "declared target produced zero testcases" for
`desktop`. Both were right: the advisory `:koverMarkdownTable` step (`|| true`) was
the first invocation whose test task was out of date, it re-ran `:desktopApp:test`
without `-Ptest.tags`, and the `@Tag("slow")` scenario classes were **skipped
rather than executed**. The suite stayed green and the directory the traceability
step was handed contained no scenario testcase. The traceable property is parity —
which invocation executes the task is Gradle's up-to-date check to decide, and no
workflow can be reordered into determinism.

Enforced by `scripts/check-gradle-test-population.py`
("gradle invocations keep one test population" in the shared registry), whose own
positive control drops the selector from a `:koverVerify` step in `ci.yml`.

#### Scenario: A later invocation drops the job's selection

- **Given** a job whose test run selects `-Ptest.tags=fast,slow`
- **And** a later step in that job runs a Gradle task that can execute the same
  test task
- **When** the later step omits `-Ptest.tags`
- **Then** the population gate SHALL fail
- **And** the report SHALL name the surface and both invocations

#### Scenario: Every invocation selects the default

- **Given** a job or `check.sh` whose test invocations all omit `-Ptest.tags`
- **When** the population gate runs
- **Then** it SHALL pass, because they agree
- **And** it SHALL report that the agreed population is Gradle's default

#### Scenario: A compile-only invocation after a test run

- **Given** a step naming only compile or assemble tasks
- **When** it follows a test run in the same job
- **Then** the population gate SHALL NOT treat it as a second population

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

---

### Requirement: REQ-23 A declared test class that produced no report fails the build

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
