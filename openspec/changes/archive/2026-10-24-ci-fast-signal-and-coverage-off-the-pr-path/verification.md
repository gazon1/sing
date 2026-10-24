# How correctness will be proven — the pull-request path's three verdicts

## Verification objectives

This change moves work between jobs and changes what a green pull request means. The
evidence therefore has to answer three questions, in this order:

1. **Does the pull-request path still judge the change?** Every test task the suite
   job used to inherit through the coverage entry point must still be named and run,
   and a job that ran nothing must not report success.
2. **Does the new fast signal say what it did?** The affected-test job must
   distinguish a selection that ran, a change that selects nothing, and a selection
   that could not be computed — three facts, not two.
3. **Did moving the work cost the properties it was carrying?** The gate-control
   proof must still hit the line it claims to prove; the coverage floor must still be
   enforced against the measurement that produced it; the record and the workflow
   must still agree.

Everything below is **designed, not run**. Nothing has been implemented; no test in
this document has been executed. The gate scripts' own suite is **red today**: it runs
2239 tests and fails one — `scripts/tests/test_check_adr_references.py` reports two
unbaselined dangling ADR references, the two citations of this change's decision
record, which does not exist yet. The baseline the new tests join is that red suite,
and the decision record closes the failure.

## Acceptance criteria coverage

| AC-NN | Scenarios | Where the evidence comes from |
|---|---|---|
| AC-01 | V-14, V-22 | a gate that reads the workflow, plus a measurement of the test worker's command line — the agent is attached by the build's configuration, so naming no coverage task is not enough |
| AC-02 | V-14, V-15 | the new shape assertion, against the coverage entry point's `dependsOn` list |
| AC-03 | V-16 | a gate that counts the pull-request jobs that judge detekt |
| AC-04 | V-02, V-04 | a unit test of the map's command builder |
| AC-05 | V-03 | a unit test of the map's base-reference handling |
| AC-06 | V-03, V-05, V-06, V-07, V-08 | a gate that reads the affected-test job's outcome and names which of the three it was |
| AC-07 | V-01, V-07, V-09 | a gate that reads the job graph's shape |
| AC-08 | V-02, V-06 | a unit test of the map's population handling |
| AC-09 | V-17 | a gate that reads the merge gate's `needs` |
| AC-10 | V-18, V-19 | the coverage-timeout gate, and the floor comparison |
| AC-11 | V-10, V-11 | a gate that reads the control phase's condition |
| AC-12 | V-12, V-13 | the control harness's own run |
| AC-13 | V-20 | the hook-gates gate |
| AC-14 | V-21 | the floor-declaration gate |
| AC-15 | V-15 | the build-output-readers gate |
| AC-16 | V-15 | the gate-wiring parity check |
| AC-17 | V-22 | a gate that reads the pull-request path's reported verdicts |

No criterion is without a scenario. Two of them lean on a gate rather than on a new
unit test — AC-10 and AC-14 — and `## Known limitations` says so.

## Unit tests

The unit-level evidence lives in the Python gate suite under `scripts/tests/`, which
runs on the CI host and in `./check.sh`. It is where the map's behaviour is pinned,
because the map is the piece whose failure mode is silent.

| Test id | What it pins |
|---|---|
| `test_test_impact_map.py::CommandTest::test_the_command_names_a_population_not_a_tag_list` | the emitted command names the fast population and carries no tag list |
| `test_test_impact_map.py::CommandTest::test_a_missing_base_ref_is_refused` | an unresolvable base reference is an error, not an empty selection |
| `test_test_impact_map.py::CommandTest::test_a_class_outside_the_population_is_dropped` | a selected class the chosen population does not contain is dropped from the selection and named in the report |
| `test_test_impact_map.py::CommandTest::test_an_empty_selection_says_so` | an empty selection is reported as an empty selection, on a channel the caller can read |
| `test_ci_pr_path.py::PrPathHasNoCoverage::test_no_pr_path_job_names_a_coverage_task` | no pull-request job names a coverage task |
| `test_ci_pr_path.py::PrPathHasNoCoverage::test_a_pull_request_reports_no_coverage_verdict` | the pull-request path has no step that reports a coverage verdict |
| `test_ci_pr_path.py::PrPathRunsTheWholeSuite::test_the_tests_job_names_every_test_task_it_runs` | the suite job names every test task it runs |
| `test_ci_pr_path.py::OnePopulationPerJob::test_the_quick_job_names_one_population_in_one_spelling` | the affected-test job's invocation carries one population spelling |
| `test_ci_pr_path.py::DetektIsJudgedOnce::test_exactly_one_pr_path_job_judges_detekt` | exactly one pull-request job judges detekt |
| `test_ci_pr_path.py::QuickJobReportsItsOwnOutcome::test_the_quick_job_names_which_of_its_three_outcomes_happened` | the affected-test job's outcome names which of the three it was |
| `test_ci_pr_path.py::QuickJobRunsTheAffectedSubset::test_the_quick_job_runs_beside_the_full_suite` | the affected-test job and the suite job are parallel, and the merge gate waits for both |
| `test_ci_pr_path.py::MergeGateRequiresTheNewJobs::test_the_merge_gate_needs_the_quick_and_detekt_jobs` | the merge gate's `needs` names the new jobs |
| `test_ci_pr_path.py::ControlHarnessPlacement::test_the_control_phase_runs_on_a_gate_change_and_not_otherwise` | the control phase's condition is the gate corpus and the schedule |

The new file `scripts/tests/test_ci_pr_path.py` is where the pull-request path's shape
is pinned. One file, because the shape is one fact: which jobs the pull request runs,
what each of them names, and what each of them reports. Splitting it per job would
make the shape readable only by assembling three files.

## Integration tests

There is no separate integration tier for this change. The change *is* the workflow,
and the workflow's own run is the integration test: a pull request that exercises the
new jobs is the proof that the jobs exist, that their steps are wired, and that the
merge gate reads them. The scenarios that touch the workflow are verified by reading
the workflow file and running the gates against it — which is what the gate suite
already does for the workflow it has today.

The one thing no local run can prove is the end-to-end timing. That is a limitation,
and it is also the measurement the plan's step 0 exists to take.

## UI / E2E tests

**Not applicable.** No screen, no navigation, no user-visible surface changes. The
device tier and the end-to-end tier are separate workflows that this change does not
touch; the device tier does not block a merge today and does not after this change.

## State-transition coverage

Every transition in `model.md` has a scenario.

| Scenario | Transition |
|---|---|
| V-01 | `Pending --start()--> Selecting` |
| V-02 | `Selecting --select(baseRef)--> SelectionKnown` |
| V-03 | `Selecting --select(baseRef)--> NotComputable` (base reference) |
| V-04 | `Selecting --select(baseRef)--> NotComputable` (two population spellings) |
| V-05 | `SelectionKnown --runSelection()--> SelectionEmpty` (empty selection) |
| V-06 | `SelectionKnown --runSelection()--> SelectionEmpty` (class outside the population) |
| V-07 | `SelectionKnown --runSelection()--> Ran` |
| V-08 | `SelectionKnown --runSelection()--> Failed` |
| V-09 | `cancel()` from any non-terminal state |
| V-10 | `GatesRan --diffGateCorpus()--> ControlDue` |
| V-11 | `GatesRan --diffGateCorpus()--> ControlSkipped` |
| V-12 | `ControlDue --runControl()--> ControlRan` |
| V-13 | `ControlDue --runControl()--> ControlVacuous` |
| V-14 | `InvocationDeclared --declareTasks()--> InvocationDeclared` |
| V-15 | `Running` → `Reported` |
| V-16 | the detekt verdict's placement — a shape assertion, no model row |
| V-17 | the merge gate's `needs` — a shape assertion, no model row |
| V-18 | the coverage job's timeout — a declaration assertion, no model row |
| V-19 | the coverage floor's locus — a declaration assertion, no model row |
| V-20 | the hook record's agreement with the workflow — a declaration assertion, no model row |
| V-21 | the two floors' order — a declaration assertion, no model row |
| V-22 | the pull-request path's reported verdicts — a shape assertion, no model row |

### V-01 — the affected-test job starts, beside the suite job

- **Acceptance:** AC-07, AC-09
- **Transition:** `Pending` --`start()`--> `Selecting` (model.md)
- **Preconditions:** a pull-request event; the workflow file as it will be after the
  change
- **Sequence:** read the workflow; find the affected-test job and the suite job; read
  the merge gate's `needs`
- **Expected state:** the affected-test job and the suite job are siblings — neither
  is in the other's `needs`; the merge gate's `needs` names both
- **Expected side effects:** none; the file is read, not written
- **Invariants checked:** INV-10
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_quick_job_runs_beside_the_full_suite'`

### V-02 — the selection is computed and names one population

- **Acceptance:** AC-04, AC-08
- **Transition:** `Selecting` --`select(baseRef)--> SelectionKnown` (model.md)
- **Preconditions:** a repository with one changed feature path and one changed core
  path; a base reference that resolves
- **Sequence:** run the map in `--command` mode against a populated working tree
- **Expected state:** the emitted command names `-Ptest.population=fast`; it carries
  no `-Ptest.tags`
- **Expected side effects:** none; the map writes nothing
- **Invariants checked:** INV-02
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_command_names_a_population_not_a_tag_list'`

### V-03 — the base reference cannot be resolved

- **Acceptance:** AC-05, AC-06
- **Transition:** `Selecting` --`select(baseRef)--> NotComputable` (model.md)
- **Preconditions:** a repository whose base reference does not exist
- **Sequence:** run the map in `--command` mode with `--base` naming a reference that
  is not in the repository
- **Expected state:** the map exits non-zero; the message names the reference it could
  not read; the message does not say that the change selects no test class
- **Expected side effects:** none
- **Invariants checked:** INV-05, INV-06
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_missing_base_ref_is_refused'`

### V-04 — the emitted command carries two population spellings

- **Acceptance:** AC-04
- **Transition:** `Selecting` --`select(baseRef)--> NotComputable` (model.md)
- **Preconditions:** a repository with one changed feature path; a base reference that
  resolves
- **Sequence:** call the map's command builder with both a population name and a tag
  list
- **Expected state:** the builder refuses; the refusal names both spellings; no
  command is emitted
- **Expected side effects:** none
- **Invariants checked:** INV-02
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_command_names_a_population_not_a_tag_list'`

### V-05 — the change selects no test class

- **Acceptance:** AC-06
- **Transition:** `SelectionKnown` --`runSelection()--> SelectionEmpty` (model.md)
- **Preconditions:** a repository whose only changed file is a documentation file
- **Sequence:** run the map in `--command` mode
- **Expected state:** the map reports that the change selects no test class; the
  caller can tell that from a failure; the exit code is 0
- **Expected side effects:** none — no Gradle invocation is made
- **Invariants checked:** INV-05, INV-06
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_an_empty_selection_says_so'`

### V-06 — a selected class is outside the chosen population

- **Acceptance:** AC-06, AC-08
- **Transition:** `SelectionKnown` --`runSelection()--> SelectionEmpty` (model.md)
- **Preconditions:** a repository whose changed path maps to a `slow`-tagged test
  class; the chosen population is `fast`
- **Sequence:** run the map in `--command` mode
- **Expected state:** the slow class is not in the emitted command; the report names
  it as dropped
- **Expected side effects:** none — no Gradle invocation is made that would run
  nothing and report green
- **Invariants checked:** INV-02, INV-05
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_class_outside_the_population_is_dropped'`

### V-07 — the selection runs and passes

- **Acceptance:** AC-06, AC-07
- **Transition:** `SelectionKnown` --`runSelection()--> Ran` (model.md)
- **Preconditions:** a pull request that changes a feature path whose tests are
  `fast`-tagged
- **Sequence:** the affected-test job runs the map, receives a command, and runs it
- **Expected state:** the job's verdict names the classes it ran; the job's exit code
  is the command's exit code
- **Expected side effects:** a Gradle invocation runs the selected classes; test
  reports are written into the job's own checkout
- **Invariants checked:** INV-05, INV-06, INV-10
- **Test level:** integration
- **Platform:** jvm
- **Deterministic reproduction:** the pull request that exercises the job; locally,
  `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_quick_job_names_which_of_its_three_outcomes_happened'`

### V-08 — a selected class fails

- **Acceptance:** AC-06
- **Transition:** `SelectionKnown` --`runSelection()--> Failed` (model.md)
- **Preconditions:** a pull request that changes a feature path whose test fails
- **Sequence:** the affected-test job runs the map, receives a command, and runs it
- **Expected state:** the job exits non-zero and names the failing class; the verdict
  is `Failed`, not `Ran` and not `Cancelled`
- **Expected side effects:** the failing test's report is written
- **Invariants checked:** INV-06
- **Test level:** integration
- **Platform:** jvm
- **Deterministic reproduction:** the pull request that exercises the job; locally,
  `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_quick_job_names_which_of_its_three_outcomes_happened'`

### V-09 — the job is cancelled

- **Acceptance:** AC-07
- **Transition:** `Pending`, `Selecting`, `SelectionKnown` --`cancel()`--> `Cancelled`
  (model.md)
- **Preconditions:** the workflow's job vocabulary
- **Sequence:** read the job's outcome vocabulary and the merge gate's accounting
- **Expected state:** `Cancelled` is an outcome of its own; the merge gate does not
  read it as `Ran`, and does not read it as "the change selects nothing"
- **Expected side effects:** none
- **Invariants checked:** INV-05, INV-06
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_quick_job_names_which_of_its_three_outcomes_happened'`

### V-10 — the control phase is due

- **Acceptance:** AC-11
- **Transition:** `GatesRan` --`diffGateCorpus()--> ControlDue` (model.md)
- **Preconditions:** a pull request that changes a file under `scripts/`, `config/` or
  `.github/`
- **Sequence:** read the control phase's condition and the `changes` job's gate-corpus
  filter
- **Expected state:** the condition is satisfied when the gate corpus changed; the
  filter covers `config/**`, which the existing `scripts` filter does not
- **Expected side effects:** none
- **Invariants checked:** INV-08
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_control_phase_runs_on_a_gate_change_and_not_otherwise'`

### V-11 — the control phase is skipped

- **Acceptance:** AC-11
- **Transition:** `GatesRan` --`diffGateCorpus()--> ControlSkipped` (model.md)
- **Preconditions:** a pull request that changes no file under the gate corpus, and
  is not the schedule
- **Sequence:** read the control phase's condition
- **Expected state:** the cheap gate pass runs, and the control phase does not
- **Expected side effects:** none
- **Invariants checked:** INV-08
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_control_phase_runs_on_a_gate_change_and_not_otherwise'`

### V-12 — the control phase runs and every control proves its gate

- **Acceptance:** AC-12
- **Transition:** `ControlDue` --`runControl()--> ControlRan` (model.md)
- **Preconditions:** the workflow as it will be after the change
- **Sequence:** run the control harness against the changed workflow
- **Expected state:** every control's gate fails on the sabotaged tree and passes on
  the restored one; the phase exits 0
- **Expected side effects:** the phase edits `ci.yml` and puts it back
- **Invariants checked:** INV-08
- **Test level:** integration
- **Platform:** jvm
- **Deterministic reproduction:** `python3 scripts/check-gate-wiring.py --can-fail`

### V-13 — a control hits the line it does not name

- **Acceptance:** AC-12
- **Transition:** `ControlDue` --`runControl()--> ControlVacuous` (model.md)
- **Preconditions:** a workflow in which a control's literal `old` text matches a
  different line than the one the control names
- **Sequence:** move the line the control names, so that its literal text now matches
  elsewhere; run the control harness
- **Expected state:** the phase reports the vacuity and fails; it does not report a
  passing control
- **Expected side effects:** the phase edits a line and restores it
- **Invariants checked:** INV-08
- **Test level:** integration
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_every_control_still_hits_the_line_it_claims_to_prove'`

### V-14 — the suite job declares its tasks, and no coverage task

- **Acceptance:** AC-01, AC-02
- **Transition:** `InvocationDeclared` --`declareTasks()--> InvocationDeclared`
  (model.md)
- **Preconditions:** the workflow as it will be after the change
- **Sequence:** read the suite job's Gradle invocation; collect the test tasks it
  names; collect the coverage tasks it names
- **Expected state:** every test task the job runs is named in the invocation; no
  coverage task is named
- **Expected side effects:** none; the file is read
- **Invariants checked:** INV-01, INV-04, INV-10
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_tests_job_names_every_test_task_it_runs'`

### V-15 — the count and the floors read the same run

- **Acceptance:** AC-02, AC-15, AC-16
- **Transition:** `Running` → `Reported` (model.md)
- **Preconditions:** the workflow as it will be after the change; the gate-wiring
  parity table
- **Sequence:** run `check-test-runs.py` against a tree with the suite job's results;
  run the gate-wiring Part I comparison
- **Expected state:** the count gate finds the reports the suite job's invocation
  produced; the two surfaces name the same tasks
- **Expected side effects:** none beyond the gate's own output
- **Invariants checked:** INV-01, INV-03, INV-04
- **Test level:** integration
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_real_surfaces_are_in_parity'`

### V-16 — exactly one pull-request job judges detekt

- **Acceptance:** AC-03
- **Transition:** shape assertion — no model row
- **Preconditions:** the workflow as it will be after the change
- **Sequence:** read every pull-request job; find the ones that judge detekt
- **Expected state:** exactly one does; the `static` job and the suite job do not
- **Expected side effects:** none
- **Invariants checked:** INV-07, INV-10
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_exactly_one_pr_path_job_judges_detekt'`

### V-17 — the merge gate requires the new jobs

- **Acceptance:** AC-09
- **Transition:** shape assertion — no model row
- **Preconditions:** the workflow as it will be after the change
- **Sequence:** read the merge gate's `needs`
- **Expected state:** it names the affected-test job and the detekt job; a skipped
  job remains a valid outcome
- **Expected side effects:** none
- **Invariants checked:** INV-06
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_merge_gate_needs_the_quick_and_detekt_jobs'`

### V-18 — the coverage job's timeout covers the published measurement

- **Acceptance:** AC-10
- **Transition:** declaration assertion — no model row
- **Preconditions:** the workflow as it will be after the change;
  `config/docs/coverage-cost.properties`
- **Sequence:** run `scripts/check-coverage-timeout.py` against the changed workflow
- **Expected state:** it exits 0; the job it identifies is the coverage job, and that
  job's timeout covers the larger published population doubled
- **Expected side effects:** none
- **Invariants checked:** INV-09
- **Test level:** integration
- **Platform:** jvm
- **Deterministic reproduction:** `python3 scripts/check-coverage-timeout.py --quiet`

### V-19 — the coverage floor is enforced where the coverage is measured

- **Acceptance:** AC-10, AC-01
- **Transition:** declaration assertion — no model row
- **Preconditions:** the workflow as it will be after the change
- **Sequence:** read the job that runs `:koverVerify`; read the job that runs the
  instrumented suite
- **Expected state:** they are the same job; no pull-request job runs `:koverVerify`
- **Expected side effects:** none
- **Invariants checked:** INV-04
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_no_pr_path_job_names_a_coverage_task'`

### V-20 — the hook's record matches the workflow

- **Acceptance:** AC-13
- **Transition:** declaration assertion — no model row
- **Preconditions:** the workflow as it will be after the change;
  `config/hooks/pre-push.json`; `.githooks/pre-push`
- **Sequence:** run `scripts/check-hook-gates.py`
- **Expected state:** it exits 0; the record names the modules the workflow judges;
  the hook still calls the delegate
- **Expected side effects:** none
- **Invariants checked:** INV-07
- **Test level:** integration
- **Platform:** jvm
- **Deterministic reproduction:** `python3 scripts/check-hook-gates.py`

### V-21 — the two populations' floors are ordered

- **Acceptance:** AC-14
- **Transition:** declaration assertion — no model row
- **Preconditions:** `settings.gradle.kts` after the change
- **Sequence:** read the `FULL_*` and `FAST_*` floor constants; compare them
- **Expected state:** the wider population's floor is not below the narrower one's, in
  each of the three metrics
- **Expected side effects:** none
- **Invariants checked:** INV-03
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_wider_populations_floor_is_not_below_the_narrower_one'`

### V-22 — a pull request reports no coverage verdict

- **Acceptance:** AC-17, AC-01
- **Transition:** shape assertion — no model row
- **Preconditions:** the workflow as it will be after the change
- **Sequence:** read every step on the pull-request path; find the ones that render a
  coverage report or compare a coverage floor
- **Expected state:** there are none
- **Expected side effects:** none
- **Invariants checked:** INV-04, INV-10
- **Test level:** unit
- **Platform:** jvm
- **Deterministic reproduction:** `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_pull_request_reports_no_coverage_verdict'`

## Concurrency and cancellation tests

- **V-09** covers cancellation. The observable is that a cancelled job's outcome is
  `Cancelled` and never reads as `Ran` or as "the change selects nothing". It is
  verified by reading the job's outcome vocabulary, not by racing a real cancel —
  the workflow's cancellation is GitHub's, and a test that tried to reproduce it would
  be a timing test with a timing test's flakiness.
- **The parallel pair.** V-01 checks that the affected-test job and the suite job are
  siblings in the graph and that the merge gate needs both. The property under test is
  the *shape*, which is what the withdrawn six-leaf split broke: a job that waited on
  another and therefore measured the wrong thing.
- **The control phase's serialization.** V-12 checks that the control phase runs
  after the gate pass and alone. This is a property of the phase's position in
  `run_all.py`, not of a race.
- **No Kotlin coroutine scope is involved**, so the cancellation tooling this project
  uses for coroutines (`AutoCloseableCoroutineScope`, `DebugProbes`) does not apply.
  The cancellation that matters here is a job cancellation.

## Failure injection and recovery

Each failure transition in `model.md` gets a named injection.

| Failure transition | Injection |
|---|---|
| `Selecting` → `NotComputable` | the map is run with a base reference that does not exist in the checkout — the same call the scenario makes |
| `Selecting` → `NotComputable` (two spellings) | the map's command builder is called with both a population and a tag list |
| `SelectionKnown` → `Failed` | a selected test class fails; the scenario selects a class whose test is made to fail, or asserts against a known-red class in the gate suite's own fixtures |
| `ControlDue` → `ControlVacuous` | the control's literal `old` string is pointed at a line that is not the one the control names — the scenario asserts the phase reports the vacuity instead of a pass |
| the suite invocation fails | a Gradle task that fails, observed through the count gate's three-way verdict |
| no report was produced | the count gate's own `EXIT_NO_RUN` path, with the results directory absent |
| the control phase's deadline fires | not injected: the phase's timeout is a property of the job's `timeout-minutes`, and a test that waits for a timeout to fire is a test that waits |

Recovery: every failure here is recovered by the next push, which starts a new run.
There is no resumption and no state carried between runs, so there is no recovery path
to test — only the property that a failed run does not leave a false green behind,
which V-03, V-05, V-09 and V-13 cover.

## Regression tests

The gates that already read the workflow are the regression net for this change, and
they are the reason the design keeps the new jobs inside `ci.yml`:

- `scripts/check-coverage-timeout.py` — the coverage job's timeout against the
  published measurement.
- `scripts/check-gradle-test-population.py` — one population per job, across every
  workflow file.
- `scripts/check-build-output-readers.py` — a reader of a build output precedes the
  invocation that can rewrite it.
- `scripts/check-gate-wiring.py` — the surfaces agree; a gate is reachable; a control
  is a real edit.
- `scripts/check-hook-gates.py` — the record and the workflow agree in both
  directions.
- `scripts/check-workflow-exit-status.py` — a piped step reports its command's status.
- `scripts/check-ci-workflow-shape.py` — the three-member cluster still passes on the
  repository's own `ci.yml`.
- `scripts/check-waiver-references.py` — the step names the waivers point at still
  exist.
- `scripts/check-coverage-floor-declaration.py` — the floors are still declared where
  they are declared.

Each of these is a gate in the registry, so each already has a control that proves it
can fail. The change's own risk is that a move breaks one of them; V-12 and V-13
cover that risk directly, and V-14 covers the one that moves the most text.

Two further regressions are already covered and must keep passing unchanged:
`scripts/tests/test_check_gate_wiring.py::PartITest::test_a_covered_task_must_still_be_named_locally`
and `scripts/tests/test_check_gate_wiring.py::PartITest::test_the_real_surfaces_are_in_parity`.

## Observability and diagnostics

The change adds no logging of its own. It moves three existing signals and makes one
of them legible:

- **What to detect:** a pull request whose verdict was vacuous — the affected-test job
  reported "nothing to run" because its comparison failed, or a control proved
  nothing. Both are already detectable by their exit code and message; the change
  only makes them distinguishable.
- **Where it is observed:** the job summary and the job's exit code. The affected-test
  job's outcome is one line in the summary; the control phase's failure is the
  phase's exit code.
- **How events belonging to one operation are correlated:** the workflow's run id and
  job name. The affected-test job names the classes it selected, so a reader can see
  which selection produced a verdict.
- **What must be redacted:** nothing new is logged. The selection names test classes
  and file paths, which are already public in the repository.
- **How the diagnostic will be checked:** V-03, V-05 and V-13 assert that each of the
  three cases produces its own message rather than a shared one. A diagnostic whose
  value is unproven is unproven cost — so each message is asserted by a test.

## Commands and expected outcomes

All of these are the commands the scenarios name. **None of them has been run for this
change**; the outputs below are what each must produce once the change is implemented.

```bash
# The gate suite, as CI runs it.
PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests

# One scenario.
PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests \
  -k 'test_a_missing_base_ref_is_refused'
```

| Command | Expected outcome once implemented |
|---|---|
| `... -k 'test_the_command_names_a_population_not_a_tag_list'` | the test exits 0; the emitted command names `-Ptest.population=fast` and carries no `-Ptest.tags` |
| `... -k 'test_a_missing_base_ref_is_refused'` | the test exits 0; the map exits non-zero and names the reference |
| `... -k 'test_a_class_outside_the_population_is_dropped'` | the test exits 0; the dropped class is named in the report and is not in the command |
| `... -k 'test_no_pr_path_job_names_a_coverage_task'` | the test exits 0; no pull-request job names `koverReport`, `koverHtml`, `:koverVerify` or `:koverMarkdownTable` |
| `... -k 'test_the_tests_job_names_every_test_task_it_runs'` | the test exits 0; the suite job's invocation names every test task the job runs |
| `... -k 'test_exactly_one_pr_path_job_judges_detekt'` | the test exits 0; exactly one pull-request job judges detekt |
| `... -k 'test_the_quick_job_names_which_of_its_three_outcomes_happened'` | the test exits 0; the three outcomes are three distinct, named outcomes |
| `... -k 'test_the_control_phase_runs_on_a_gate_change_and_not_otherwise'` | the test exits 0; the control phase's condition names the gate corpus and the schedule |
| `python3 scripts/check-coverage-timeout.py --quiet` | exits 0; the coverage job's timeout covers the larger published population doubled |
| `python3 scripts/check-gate-wiring.py --can-fail` | exits 0; the controls hit the lines they name |
| `sgate run-all --with-controls` | exits 0; every control proved its gate can fail |
| `python3 -m unittest discover -s scripts/tests` | exits 0; the whole suite is green |

## Known limitations

1. **There is no Kotlin test here.** The change touches no Kotlin source, so the
   project's Kotlin test conventions — `@Tag("fast")`/`@Tag("slow")`, `runTest` +
   `advanceUntilIdle()`, the fakes in `FakeRepositories.kt` — have no subject. The
   evidence is the Python gate suite. The `slow` tag's meaning ("crosses a process
   boundary") does not apply to these tests; the gate suite runs as one unit.
2. **No coroutine cancellation probe.** `DebugProbes.dumpCoroutines()` is JVM-only
   and needs the ByteBuddy agent, and it asserts on Kotlin coroutines. There are no
   Kotlin coroutines in this change, so V-09 verifies the outcome vocabulary rather
   than a suspended coroutine.
3. **AC-10 and AC-14 lean on a gate rather than on a new unit test.**
   `scripts/check-coverage-timeout.py` already computes the required timeout from
   the published measurement, and `scripts/check-coverage-floor-declaration.py`
   already owns where the floors live. The evidence that the coverage job's timeout
   and the floors' locus are right is those gates running against the changed
   workflow — V-18 and V-19 — rather than a new assertion written for this change.
   A dedicated assertion that the wider population's floor is not below the narrower
   one's does not exist yet: V-21 is where it would live.
4. **The end-to-end timing is not verified by any scenario.** No scenario can prove
   that the pull-request path got faster, because the number depends on the runner
   and on the un-instrumented cost of the suite, which is unmeasured (the plan's
   step 0). The measurement is taken from the API after the first real pull request,
   and it is a measurement, not a criterion — the budget decision says so
   explicitly.
5. **The interaction between the workflow's text and the registry's controls cannot
   be fully verified ahead of the change.** V-13 covers the vacuity case
   deliberately, by moving a line. The remaining risk — that a control's literal
   `old` string matches a line nobody expected — is only fully exercised by the
   real control phase, which is what the `static` job runs.
6. **The archive order with the open change `ci-fast-only-pr-full-on-schedule` is
   outside this document's reach.** It decides which version of `REQ-3` survives.
   The delta in `specs/test-execution-integrity/spec.md` records the dependency.
