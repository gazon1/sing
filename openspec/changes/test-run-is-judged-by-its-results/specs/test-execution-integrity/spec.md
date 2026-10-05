# test-execution-integrity

## MODIFIED Requirements

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

## ADDED Requirements

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
