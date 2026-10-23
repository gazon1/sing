# Test Execution Integrity — Observable Behavior

**capability:** `test-execution-integrity` | **status:** proposed

---

## MODIFIED Requirements

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

**A floor SHALL belong to a named population, not to the project.** A run that
selected fewer classes measures less coverage than a run that selected more, so a
single floor cannot describe both: it is either trivially satisfied by the smaller
run or unreachable by it. Each population SHALL therefore carry its own floor, and the
floor that applies SHALL be the one belonging to the population the run measured.

A population's floor SHALL be rewritten only from a measurement of that same
population. A floor taken from a wider run is unreachable by a narrower one by
construction, which turns the gate into a permanent failure rather than a
measurement.

The comparison SHALL stay one-sided in both directions: a run that measured MORE than
its floor SHALL pass, and a run that measured less SHALL fail. A fuller population
passing a narrower population's floor is a legitimate outcome, not a defect.

**The floor SHALL be enforced on the paths that compute the measurement, and the
pull-request path SHALL NOT carry an instrumented suite.** A measurement that is
never taken cannot fail a floor, and a pull request that carries no measurement has
no coverage verdict to report — reporting one anyway would be a claim about a run
that did not happen. The paths that compute coverage — a push to the trunk, the
schedule, and the pull request that asks for the full population — SHALL enforce the
floor against the measurement they produce, and a breach there SHALL fail that run.

**A population's floor SHALL NOT be lower than the floor of a narrower population.**
Measuring more classes and demanding less of them is a weaker guarantee, not a
stronger one: the wider run is the one that saw the most code, and it is the run
that would otherwise be judged most leniently. The floors of two populations SHALL
therefore be ordered, with the wider population's floor at least as high as the
narrower one's, and each population's measurement SHALL still be compared only to
its own floor.

**Rationale:** With `jvmTest` excluded from instrumentation the report claimed 10.5%
instruction coverage; with instrumentation filtered to `com.singularity.todo.*` the
same suite measured 23.0%. The first number was an artefact of the Gradle
configuration. Aggregating moved the same suite to 38.5% and brought 32 packages off
0% — 108,096 instructions of Compose surface that 27 passing flow tests had been
exercising all along while the per-module report could not see any of it.

The population clause is the same discipline applied one level up. A floor is a claim
about a measurement, and "which measurement" was left unstated — so the number could
be taken from a run that no longer happens and committed as a floor that only one
population can satisfy. The one-sided rule is what lets two populations coexist: the
wider one does not have to be lowered to accommodate the narrower one.

The locus clause is that discipline applied once more. "CI computes coverage" did not
say *which* CI, and a pull request that ran an instrumented suite paid for a
measurement it did not enforce, while a run that enforced the floor elsewhere had to
be told apart from one that never ran. Naming the paths removes the ambiguity: a
pull request that carries no measurement reports no coverage verdict, and the run
that measures the coverage is the run that judges it.

The ordering clause is the last of the same family. Two floors, two populations, and
no statement of which is stricter, is how the stricter bar ends up attached to the
run that measures less.

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

#### Scenario: A run measures the fast population

- **Given** a run that selected the fast population
- **When** the coverage gate compares the measurement against the floors
- **Then** it compares against the fast population's floors
- **And** a measurement above them passes

#### Scenario: A run measures the full population

- **Given** a run that selected the full population
- **When** the coverage gate compares the measurement against the floors
- **Then** it compares against the full population's floors

#### Scenario: A floor is taken from the wrong population

- **Given** a fast population's floor is rewritten from a measurement of the full
  population
- **When** a fast population run is compared against it
- **Then** the gate fails, because that floor describes a run this population never
  produces

#### Scenario: A pull request carries no coverage measurement

- **Given** a pull request whose jobs run no instrumented suite
- **When** the pull request is judged
- **Then** no coverage verdict is reported for it
- **And** the pull request is not failed for a coverage breach, because no
  measurement was taken to breach a floor with

#### Scenario: A push to the trunk measures the coverage

- **Given** a push to the trunk that runs the instrumented suite
- **When** the measured value falls below the floor of the population it measured
- **Then** the run fails and names the metric and the delta

#### Scenario: The wider population carries the lower floor

- **Given** two populations whose floors are declared
- **And** the wider population's floor is below the narrower one's
- **When** the floors are read
- **Then** they are rejected, because measuring more classes and demanding less of
  them is the weaker guarantee

## ADDED Requirements

### Requirement: REQ-26 A signal that runs a subset names what it ran and what it could not compute

A job that runs only the test classes a change can affect SHALL report, as three
distinct outcomes, which of these happened:

- the selected classes ran and passed,
- the change selected no test class at all, and
- the selection could not be computed.

The third outcome is the one that matters: a selection that could not be computed is
not a selection with nothing in it. A missing base reference, an unreadable
comparison, or a map that cannot see the change produces an empty set, and an empty
set reported as "nothing to run" is indistinguishable from a change that genuinely
breaks nothing. The job SHALL fail, or report a verdict of its own, and SHALL name
the reason.

**The selected classes SHALL come from a named population.** A selection that spells
its classes out as a raw tag list is a second spelling of the population selection,
and the two spellings drift: the population the project declares and the tag list a
tool happens to emit are then different facts about the same run. A selection SHALL
therefore name the population and filter within it, and SHALL NOT carry both a
population name and a tag list.

**Rationale:** the affected-test signal was written to answer a question the full
suite answers slowly: "can this change break something, and which test would say
so". Its first failure mode was silent rather than wrong. A missing base reference
made the comparison fail, the failure was swallowed, and the tool reported an empty
selection with a zero exit code — indistinguishable from a change that breaks
nothing, and indistinguishable from a change nobody had looked at. The second
failure mode was a spelling: the tool emitted a raw tag list that happened to select
the fast and the slow half together, so a job that was supposed to answer in minutes
was measuring the population the schedule measures, against the wider population's
floor.

Three outcomes rather than two is the same shape as the report-versus-absence split
the gates already make. A report that exists and reports zero tests is a
regression; a report that does not exist is an absence. An empty selection that was
computed is the first kind; an empty selection that could not be computed is the
second, and reading it as the first is how a broken tool becomes a green build.

#### Scenario: The selection ran and passed

- **Given** a change that selects test classes
- **When** the selected classes run and pass
- **Then** the job reports that the selection ran and passed
- **And** it names the classes it ran

#### Scenario: The change selects nothing

- **Given** a change that selects no test class
- **When** the job runs
- **Then** it reports that the change selected no test class
- **And** it does not report that tests ran and passed

#### Scenario: The selection could not be computed

- **Given** a comparison that cannot be made because a reference the selection needs
  is absent
- **When** the job runs
- **Then** it fails or reports a verdict of its own
- **And** it names the reference it could not read
- **And** it does not report that the change selected no test class

#### Scenario: The selection names a population and a tag list

- **Given** a selection that carries both a population name and a raw tag list
- **When** the job runs
- **Then** the selection is refused, because the two spellings can disagree and only
  one of them is the project's declaration
