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

### Requirement: REQ-23 A declared test class that produced no report fails the build

A verification gate SHALL compare the test classes the sources declare against the
classes that produced a report in the run, and SHALL fail when a declared class is
absent from the results.

The comparison SHALL be scoped to the classes the sources declare as belonging to the
population the run measured. A source set with no such declaration is out of scope for
this requirement and SHALL NOT be reported by it.

The population a run measured SHALL be recorded by the run itself, so that the gate
does not have to infer it. A gate that infers the population from what happened to
execute cannot distinguish a deliberate narrower run from a filter that selected
nothing at all.

The declared-class list SHALL be produced by the same scanner the Kiwi stand uses, so
that "is this class runnable" has one implementation in the repository rather than one
per consumer.

**Rationale:** a count floor compares a run against a number recorded earlier, so it is
blind to a class that did not exist when that number was written. Adding a test and
having it silently skipped lowers nothing the floor can see, which is how two
`@ParameterizedTest` classes sat untagged while CI ran the full selection and
reported success. This requirement asks the direct question instead: not "did fewer
things run than last time" but "did this specific declared class run". That is a fact
about the run rather than a judgement about the source, so a JUnit annotation form no
text predicate knows about is still caught by it.

The population clause replaces a justification that this change made false. The
comparison was scoped to the fast classes because a slow class was excluded from
every local run by design — so requiring one to have run would fail every local run.
Once slow classes run on a schedule, that reason is gone: a slow class that did not
run is a loss of coverage the count floor cannot see, because the count floor is
one-sided and a run that executed fewer slow classes than the previous one is still
above it.

#### Scenario: A declared class is silently skipped

- **Given** a source set whose tests all declare the fast population
- **And** a class in those sources whose report is absent from the run's results
- **When** the verification gates run
- **Then** they fail
- **And** the failure names that class

#### Scenario: Every declared class reports

- **Given** a source set whose tests all declare the fast population
- **When** every one of them produces a report
- **Then** the gates pass

#### Scenario: Report names differ in shape between modules

- **Given** two source sets that write suite names differently, one as a simple class
  name with a per-target suffix and one as a fully-qualified name
- **When** the gates compare declared classes against reports
- **Then** both shapes satisfy a declared class name
- **And** a suite name that differs only in that shape is not reported as missing

#### Scenario: A slow class is not part of the comparison

- **Given** a source set containing a class declared for the slow population
- **And** a run that excluded it
- **When** the verification gates run
- **Then** they do not report that class as missing

#### Scenario: A slow class is missing from a full run

- **Given** a source set containing a class declared for the slow population
- **And** a run that measured the full population
- **And** that class produced no report
- **When** the verification gates run
- **Then** they fail
- **And** the failure names that class

## ADDED Requirements

### Requirement: REQ-24 The JVM tier answers on every pull request, and its population is decided once

The JVM verification tier SHALL run on every pull request, and SHALL run the fast
population there.

The full population SHALL run on a schedule, on a manual request, and on demand
through an opt-in label.

The population SHALL be decided in one place, and every later step in the tier SHALL
read that decision rather than carrying its own copy of it. A tier that decides the
population in several places has several populations, and the disagreement between
them is invisible until one of them measures something the others did not.

The decided value SHALL be one of the known populations, and a step that finds any
other value SHALL fail rather than continue. An empty or unrecognised value would
otherwise be read as a narrower population than any of them, and the run would report
green while measuring less than it claimed.

A pull request SHALL be able to request the full population through the label
without a new commit, and the label SHALL keep requesting it for the pull requests
that follow.

**Rationale:** the device tier already answers every pull request and decides which
of its two modes it is running in one place, with every later step reading that
decision. The JVM tier now follows the same shape, and the same reason applies: a
tier whose steps each carry their own population can have a step that measures a
different set of classes from the one the gate counted, and the gate reports a
verdict it cannot source.

The label needs two triggers, not one. A label added to an open pull request has to
start a run on its own, and it also has to keep applying to the runs that follow —
otherwise the pull request silently returns to the fast population on the next
commit, and the full population is a thing that happened once.

#### Scenario: A pull request is opened

- **Given** a pull request with no opt-in label
- **When** the JVM tier runs
- **Then** it runs the fast population

#### Scenario: A pull request asks for the full population

- **Given** a pull request carrying the opt-in label
- **When** the JVM tier runs
- **Then** it runs the full population

#### Scenario: The label is added to an open pull request

- **Given** an open pull request without the opt-in label
- **When** the label is added
- **Then** a run starts on its own, without a new commit
- **And** that run uses the full population

#### Scenario: The label survives the next commit

- **Given** a pull request carrying the opt-in label
- **When** a commit is pushed to it
- **Then** the run that follows uses the full population

#### Scenario: The scheduled run

- **Given** the schedule fires
- **When** the JVM tier runs
- **Then** it runs the full population

#### Scenario: The population is unrecognised

- **Given** the decided population is neither of the known ones
- **When** the tier validates the decision
- **Then** the tier fails, naming the value it found

#### Scenario: Every step reads the same decision

- **Given** a run of the JVM tier
- **When** any step that can execute tests selects its population
- **Then** it selects the population the tier decided

### Requirement: REQ-25 A population is a name, and the report measures everything it counts

A test population SHALL be selected by NAME, and the mapping from a name to the
JUnit tags it selects SHALL live in one file that the build, the workflows and the
gates all read. A selection SHALL NOT be spelled as a tag list in more than one
place: two spellings of one selection drift apart, and the drift is invisible
until a run measures something else.

The aggregated coverage report SHALL be produced from the test tasks of every
project whose classes are in its denominator. A project that is not measured SHALL
either run its tests in the coverage job or be excluded from the coverage scope
explicitly. A project whose classes are counted and whose tests never ran is
reported as 0% covered — a number that reads like a coverage problem and is a
measurement one.

The run manifest SHALL be written for every module that ran tests, and SHALL
record the population the run selected. The manifest is a fact about the run; a
gate that has to infer the population from which classes happened to produce a
report cannot tell "selected no slow class" from "deliberately fast-only".

The floor of the application SHALL be declared where the coverage plugin reads
it, and a gate SHALL say so when it does not. A floor declared in a place the
plugin never reads is a floor nobody enforces, and the step that reports it
stays green.

#### Scenario: The population is named

- **Given** the named populations in `config/test-populations.properties`
- **When** a job selects a population by name
- **Then** the build, the workflow and the gates resolve that name to the same
  tag list, and no surface spells the tag list itself

#### Scenario: A module's classes are counted but its tests never ran

- **Given** a project whose classes are in the coverage denominator and whose
  test task is not in the report's task list
- **When** the coverage gate runs
- **Then** the gate names the project and says which of the two things is wrong:
  its tests do not run, or it is not part of the app's coverage scope

#### Scenario: A module's run says which population it measured

- **Given** a module that ran tests
- **When** the run finishes
- **Then** the run manifest of that module records the population the run
  selected, and the slow-class gate answers from that record rather than from
  which classes happened to produce a report

#### Scenario: The floor is declared where nothing reads it

- **Given** a verify block outside the settings-level coverage plugin
- **When** the declaration gate runs
- **Then** the gate names the file and says that nothing evaluates the floors
  declared there
