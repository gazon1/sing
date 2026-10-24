# CI restructure — a fast signal on the pull-request path, coverage off it

## Summary

The pull request path shall carry a fast merge signal and shall not carry the work
that only has to happen once per change. Three things move:

- **Coverage leaves the pull-request path.** The instrumented suite, the coverage
  report and the coverage floor move to a job that runs on a push to the trunk, on the
  schedule, and on the pull request that asks for the full population.
- **Detekt is judged once.** One job on the pull-request path carries the detekt
  verdict, instead of the auto-fix in two jobs plus eight detekt tasks inside a third
  Gradle invocation.
- **An affected-test signal appears beside the full suite.** A job runs the test
  classes the change can affect, in parallel with the full fast suite, and reports
  what it ran and what it could not compute.

The four open decisions in the plan were answered before this document was written:
the gate-control proof runs when the gate corpus changed or on the schedule (D3-A);
detekt stays in the local pre-push hook (D4-A); the pull-request path carries both
the affected-test signal and the full fast suite (D5-A); and no numeric time budget
is part of this contract (the budget answer).

## Problem

A pull request costs 58.9 runner-minutes and 29.4 minutes of wall clock on the
critical path, and the largest single item in it is a measurement the pull request
does not need. The facts are in `investigation.md`; the four that shape this change:

1. `:shared:jvmTest` under the Kover agent occupies 1040 s of the 1376 s Gradle step
   (75.6 %). The coverage number is the reason the suite is instrumented, and the
   pull request does not enforce that number — the floor belongs to the run that
   measures it.
2. The control proof — "the gates can actually fail" — costs 400 s of the pull
   request and proves a property of the gate corpus, which changes on a different
   schedule than the code does.
3. The affected-test map exists, and it already answers the question it was written
   for, but it currently fails in two ways that make its answer unusable: a missing
   base reference is swallowed into an empty selection that exits zero, and its
   emitted command selects the fast and slow half together.
4. Detekt is analysed twice per pull request, not three times: the third occurrence
   is `UP-TO-DATE`. The redundancy is in the job graph, not in the analysis.

The cost lands on the wrong party. The person waiting is an agent with nothing to
do for the length of the pull request, and the measurement that is slow is the one
that has the least to say about whether the change is safe to merge.

## Scope

**In scope**

- The job graph of `.github/workflows/ci.yml` on the pull-request path: which jobs
  run, which of them measure what, and which of them the merge gate requires.
- The Gradle invocation of the suite job, and the task graph it depends on.
- The coverage job: where the instrumented suite, the report and the floor live, and
  the timeout it declares.
- The affected-test job: what it selects, how it selects it, and what it reports.
- The placement of the gate-control proof.
- The gate scripts that read the workflow as text, and the registry controls that
  mutate it — because moving a step changes what they hit.
- The pre-push hook's detekt verdict, which this change keeps.
- The committed coverage floors, and the declaration that records them.
- The documentation that states the workflow's shape: `docs/testing/`,
  `docs/scripts/gate-registry.md`, the CI/CD skill, and the decision log.

**Out of scope**

- The `main`/`develop` branch-protection rule. It is a separate act with its own
  risks, and the merge gate's existing shape does not change.
- The device tier, which is a separate workflow and does not block a merge.
- The end-to-end tier.
- The product. Nothing here changes what the application does.
- Any change to the gate corpus itself, to the rules the gates enforce, or to the
  population definitions.

**Capability spec.** This change alters `test-execution-integrity`: the locus where
the coverage floor is enforced, the ordering of two populations' floors, and the
verdict semantics of a job that runs a subset. The delta is in
`specs/test-execution-integrity/spec.md`, and `.openspec.yaml` no longer carries
`skip_specs`.

**Dependency.** The open change `ci-fast-only-pr-full-on-schedule` touches four
requirements: it modifies `REQ-3` and `REQ-23`, and adds `REQ-24` and `REQ-25`. This
delta is written against the state **after** that change is archived, and this
change's new requirement takes `REQ-26` — `REQ-25` is already taken by the sibling's
own "A population is a name, and the report measures everything it counts".
Archiving order matters: archiving this change first would let the other change's
`REQ-3` replace the enforcement-locus clause.

## Non-goals

- Making the full suite faster. The suite's cost is what it is; this change moves
  the measurement, it does not shrink the measurement.
- Making the affected-test signal a verdict. It is a candidate set — the classes
  worth running first — and it says so.
- Re-deciding what the gates enforce. Only where they run changes.
- Changing the gate registry's contents. Only the surface the registry reads changes,
  and where a control hits that surface.
- Moving work out of the pull-request path without a place to move it to. Every step
  that leaves the pull-request path gains a path where it still runs.

## Current behavior

All of this is measured; the citations are in `investigation.md`.

- `.github/workflows/ci.yml` declares ten jobs. The `tests` job ("JVM tests, detekt,
  coverage") is the critical path at 29.4 minutes on a code pull request.
- The `tests` job runs one Gradle invocation (`ci.yml:520-537`). It names
  `koverReport` and `koverHtml` and exactly one test task (`:detekt-rules:test`); the
  other six test tasks reach the graph through `koverReport`
  (`build.gradle.kts:390-399`, `build.gradle.kts:471-472`).
- The coverage floor is enforced at `ci.yml:841-842` (`:koverVerify`), the report is
  rendered at `ci.yml:847-849` and `ci.yml:858-878`, both advisory
  (`|| true`).
- The floors are `FAST_*` = 27/23/31 (`settings.gradle.kts:179-181`) and `FULL_*` =
  27/20/27 (`settings.gradle.kts:156-158`), with the `require` blocks at
  `settings.gradle.kts:183-203`. The measured full-population values are
  55.88/40.44/57.23 (`config/docs/coverage-measurements.properties`).
- The `static` job runs the control proof: `sgate run-all --with-controls`
  (`ci.yml:187-188`), 400 s. The `tests` job runs the other half,
  `check-gate-wiring.py --except-can-fail` (`ci.yml:909-913`), and the `meta` job
  runs `Gates are alive` (`ci.yml:270`), 147 s.
- Detekt is named in three places on the pull-request path: the `static` job's
  auto-fix (`ci.yml:208-211`, 196 s), the `tests` job's auto-fix (`ci.yml:423-426`,
  231 s), and eight detekt tasks inside the `tests` job's Gradle invocation
  (`ci.yml:531`). Measured, the third occurrence is `UP-TO-DATE`.
- The affected-test map exists at `scripts/test-impact-map.py`. Its emitted command
  carries `-Ptest.tags=fast,slow` (`:87`, `:299`); a change under `scripts/`,
  `.github/`, `docs/`, `config/`, `.specify/` or `openspec/` selects no test class
  (`:68-75`, `:253-255`); a missing base reference is swallowed (`:157`) and yields
  an empty selection with exit code 0 (`:341-343`).
- The merge gate requires `[static, meta, tests, android, changes, agent-review]`
  (`ci.yml:1114`) and accepts `success` or `skipped` for each (`ci.yml:1125-1130`).
- The pre-push hook delegates the detekt verdict to `scripts/pre-push-verdict.py`
  (`.githooks/pre-push:637-648`), the module set is computed by
  `scripts/changed-modules.py`, and the record is checked against the CI workflow in
  both directions (`scripts/check-hook-gates.py:179-190`, `:199-203`).
- The pull request costs 58.9 runner-minutes on a code change and 37.3 on a docs
  change.

## Required behavior

### R1 — The pull-request path carries no instrumented suite

**The pull-request path** is the set of jobs that run when the event is a pull
request. A job belongs to it when its `if:` admits that event; a job that runs only
on a push to the trunk, only on the schedule, or only on a pull request carrying the
`run-full-tests` label does not.

No job on the pull-request path shall run an **instrumented** test task, and no job
on it shall run a coverage task. The instrumentation is a property of the build's
configuration, not of the task graph: the Kover agent is attached to every `Test`
task when the settings-level plugin enables coverage, and it is attached whether or
not the invocation names a coverage task. Removing a coverage task from an
invocation therefore moves the report; it does not un-instrument the suite.

The coverage report, the coverage floor and the coverage table are produced by a job
that runs on a push to the trunk, on the schedule, and on the pull request that asks
for the full population. **A pull request carrying `run-full-tests` is an
exception**: it runs the coverage job, it reports a coverage verdict, and a
measurement below the floor fails it.

Every other pull request carries no measurement, reports no coverage verdict, and is
not failed for a coverage breach — because nothing was measured to breach a floor
with.

### R2 — The suite job names every test task it runs

The suite job's Gradle invocation shall name every test task the job runs. The
coverage entry point brought **seven** test tasks into the graph transitively
(`build.gradle.kts:390-399`); when it is removed from the pull-request path, those
tasks shall be named directly, or the job runs no tests and reports green.

The expected set is not the implementer's: it is the coverage entry point's
`dependsOn` list (`build.gradle.kts:390-399`), minus the tasks another job owns.
`:adr-core:jvmTest` belongs to the ADR job (`ci.yml:745-751`); `:detekt-rules:test`
stays with the suite job, because today it is named there explicitly
(`ci.yml:529`) and dropping it would move the custom rules' own tests off the
pull-request path.

This is the correction the plan's step 3 needed: removing `koverReport` from the
invocation is not by itself the removal of coverage — it is also the removal of the
suite.

### R3 — The coverage floor is enforced where the coverage is measured

The floor is checked against the measurement the same job produced. The job that
computes the coverage enforces the floor; a path that computes no coverage enforces
no floor.

The two populations' floors are ordered: the wider population's floor shall not be
below the narrower one's. The values are `FULL_*` = 27 / 23 / 31, equal to the
narrower population's `FAST_*` = 27 / 23 / 31 (`settings.gradle.kts:156-158` and
`:179-181` today). They are a policy, recorded in the decision log and declared in
`settings.gradle.kts`; the measurement they are checked against is unchanged.

### R4 — Detekt is judged once on the pull-request path

Exactly one job on the pull-request path shall judge detekt. No other pull-request
job shall name a detekt task, and the auto-fix shall not run in more than one job.

The judgment keeps its current shape: the auto-fix runs first, the verdict is a diff
of the already-fixed tree (`ci.yml:419-422`), and the eight modules the record names
(`config/hooks/pre-push.json:13-22`) are the ones judged.

### R5 — An affected-test signal runs beside the full suite

A job on the pull-request path shall run the test classes the change can affect,
selected from the fast population, in parallel with the full fast suite. Its purpose
is to answer "what is worth running first"; it does not replace the full suite and it
does not claim to.

The selection shall come from a named population. The job's Gradle invocation shall
carry the population name and not a raw tag list, and shall not carry both — the
build refuses that combination (`build.gradle.kts:80-85`), and two spellings of one
selection are two facts that can drift apart.

### R6 — The affected-test signal reports three distinct outcomes

The job shall distinguish a selection that ran and passed, a change that selected no
test class, and a selection that could not be computed. The third is not the second:
a missing base reference is a failure to compute, and it shall be reported as one.

A missing base reference shall fail the job or produce a verdict of its own, and
shall name the reference it could not read. It shall never be reported as "the
change selects no test class".

### R7 — The control proof runs where the gate corpus changed, and on the schedule

**The gate corpus** is `scripts/**`, `config/**` and `.github/**`: the gate
scripts, the declarations that configure them, and the workflow that runs them.

The gate-control proof shall run on a pull request that changes the gate corpus, and
on the schedule. Every other pull request shall run the cheap gate pass, which
exercises the same gates without the control phase.

The two halves of the proof stay two halves: the pull-request path continues to run
`check-gate-wiring.py --except-can-fail`, and the control phase runs where the corpus
it proves has changed.

### R8 — The pre-push hook keeps the detekt verdict

The hook continues to delegate the detekt verdict to the script that computes the
module set, the record continues to name the eight modules CI judges, and the record
continues to agree with the CI workflow in both directions. This change does not
touch the hook's contract; it is named here because the merge judgement depends on
it and because the workflow it is checked against changes.

### R9 — The merge gate requires the new jobs

The merge gate shall require the affected-test job and the detekt job, so a pull
request cannot merge while a job that was supposed to judge it has not reported. A
skipped job remains a valid outcome.

## Invariants

**INV-01 — A job that names a test task names all of them.**
*Why it must hold:* the coverage entry point was the only thing that put six test
tasks into the job's graph, so removing it from the invocation removes the suite.
A job that names some of its test tasks and inherits the rest is a job whose size
depends on a task it no longer names.

**INV-02 — One Gradle invocation, one population spelling.**
*Why it must hold:* the build rejects a population name and a tag list in the same
invocation (`build.gradle.kts:80-85`), and the floors are chosen by the resolved tag
list (`settings.gradle.kts:124`). Two spellings of one selection are two facts about
the same run, and only one of them is the project's declaration.

**INV-03 — The floor that applies is the one belonging to the population the run
measured.**
*Why it must hold:* a run that measured fewer classes reaches a lower number by
construction, so a floor taken from a wider run is unreachable by a narrower one, and
the gate becomes a permanent failure rather than a measurement.

**INV-04 — The coverage floor is enforced where the coverage is computed.**
*Why it must hold:* a measurement that was never taken cannot fail a floor. A path
that enforces a floor it does not measure either blocks every change or, if it skips
the check, reports a verdict it cannot source.

**INV-05 — "Nothing ran" is its own verdict, and it is not "nothing to run".**
*Why it must hold:* an empty selection that was computed and an empty selection that
could not be computed are different facts — the first is a change that breaks
nothing, the second is a tool that did not look. The tool's current behaviour
swallows the difference and exits zero, which is how a broken tool becomes a green
build.

**INV-06 — A job's reported status is its command's status.**
*Why it must hold:* a job that reports green while its command failed is worse than
a red job, because it is believed.

**INV-07 — The gate record and the CI workflow agree in both directions.**
*Why it must hold:* the record names the modules CI judges and the hook runs; a
disagreement in either direction means one of the two is describing a workflow that
no longer exists.

**INV-08 — A control proof hits the line it claims to prove, and names the step it
claims to prove.**
*Why it must hold:* a registry control has two halves — the literal text it replaces,
and the `tracked_by` anchor that says which step it is about. The text resolves by the
registry's `count=1` (the first occurrence); the anchor resolves by step name, and
`scripts/check-waiver-references.py:31-38` requires it to resolve. After the
workflow's job graph moves, the two halves can point at different steps: a control
whose anchor names one step while its sabotage lands in another passes while proving
nothing, which is the worst state a control can be in: green, and vacuous.

**INV-09 — The job that runs the coverage declares a timeout derived from the
published measurement.**
*Why it must hold:* the timeout gate compares the declared timeout against the
larger of the two published populations, doubled. A timeout picked by feel is a
timeout that truncates the measurement it was meant to protect.

**INV-10 — The pull-request path carries no measurement it does not enforce.**
*Why it must hold:* every minute spent measuring is a minute the person waiting
does not spend working, and the measurement has to earn it by being acted on.

## Failure semantics

- **A job in the pull-request path fails** → the merge gate fails. A skipped job is
  a valid outcome; a failed one is not.
- **The affected-test job cannot compute its selection** → the job fails and names
  the reference it could not read. It does not report an empty selection.
- **The affected-test job selects no test class** → the job reports that outcome
  explicitly. It does not fail, and it does not report success.
- **The coverage job's measurement falls below the floor** → that run fails and
  names the metric and the delta. The pull request is unaffected; the failure lands
  on the path that measured.
- **A registry control hits a line other than the one it names** → the control
  phase fails, because a vacuous control is a silent loss of the property it was
  written to protect.
- **The coverage job exceeds its declared timeout** → the run fails as a timeout. The
  measurement is incomplete, and an incomplete measurement must not be read as a
  passing floor.
- **The cheap gate pass fails** → the pull request fails, exactly as it does today.

## Cancellation and lifecycle

The workflow's concurrency group is `ci-<pull-request number || ref>` with
`cancel-in-progress: true` (`ci.yml:29-30`). A superseded push cancels the previous
run of the same pull request.

- Every job added by this change belongs to the same workflow and is covered by the
  same concurrency group. A job outside it would outlive the run it belongs to.
- A cancelled job is a cancelled job. Its result is not "success", and the merge
  gate's accounting treats a superseded run as superseded rather than as a verdict.
- The affected-test job and the coverage job are separate runs of the same
  repository, not stages of one. Cancelling one does not cancel the other.
- The coverage job's report and table are uploaded as artifacts before the job
  ends. A cancelled coverage job leaves no report, and the absence is the absence of
  a measurement, not a measurement of zero.

## Concurrency and ordering

- The affected-test job and the full suite job run **in parallel**, and neither
  waits for the other. They are two answers to two questions: "what is worth running
  first" and "does the whole suite still pass". The merge gate waits for both.
- The detekt job is independent of both. It judges a different property of the tree.
- Within a job, the Gradle invocation stays `--no-parallel` (`ci.yml:463-465`); the
  parallelism in this design comes from jobs, not from tasks inside one.
- The control phase runs **after** the gates it proves, in the same job, and alone —
  it edits the tree the gates read (`scripts/src/sgate/commands/run_all.py:435-437`,
  `:562-564`; `ci.yml:180-186`). Splitting it into its own job preserves that
  ordering: the control phase's job runs after the gate pass's job, not alongside it.
- The coverage job's report and its floor check are read from the same run's
  output. A step that reads a build output precedes the invocation that can rewrite
  it (`scripts/check-build-output-readers.py`).

## Persistence and recovery

- The affected-test job needs the base reference the selection compares against. The
  checkout shall provide it, and a checkout that cannot provide it shall be a
  failure of the job, not an empty selection. This is the single change the map
  tool needs.
- The coverage report, the coverage table and the test reports are uploaded as
  artifacts, and the coverage job's verdict is recomputable from them afterwards.
- The coverage floor's measurement and its timeout are published in
  `config/docs/coverage-cost.properties` and
  `config/docs/coverage-measurements.properties`; the published numbers are the
  ones the timeout gate and the comparison use, so a change of population changes
  them in one place (`scripts/check-published-measurements.py`).
- Nothing on the pull-request path is resumable. A cancelled pull-request run starts
  again from the beginning, which is why its cost matters.

## Compatibility constraints

- **`./check.sh` and the workflow shall name the same tasks.** The gate-wiring
  Part I compares them (`ci.yml:884-886`).
- **The registry's surfaces shall stay reachable.** The registry owns the gate
  surfaces and the control set; a gate that is reachable from no surface is a
  finding (`scripts/check-gate-wiring.py` Part E).
- **`scripts/ci/static-gates.sh` is generated.** It is regenerated from the
  registry, not edited (`PYTHONPATH=scripts/src python3 -m sgate
  render-static-gates --write`).
- **The workflow file's text is load-bearing.** Four registry controls mutate it by
  literal string with `count=1`; a step whose text changes, moves or duplicates can
  change which line a control hits. A step name is also an anchor for the waiver
  references (`scripts/check-waiver-references.py`).
- **The hook's module set is computed, not listed.** The hook names no detekt
  module (`scripts/check-hook-gates.py:199-203`), and the record is checked against
  the CI workflow in both directions (`:179-190`).
- **The floor is declared in `settings.gradle.kts`**, not in the root build script
  (`scripts/check-coverage-floor-declaration.py`).
- **The coverage timeout is derived from the published measurement**, and the
  published measurement is the larger of the two populations
  (`scripts/check-coverage-timeout.py`).
- **One population per job** (`scripts/check-gradle-test-population.py`), and the
  population is named, not spelled out as tags (`build.gradle.kts:80-85`).
- **`./gw`, not `./gradlew`**, in the repository's own tooling (`AGENTS.md`).
- **The population decision stays in one place.** Today the `tests` job decides it
  once and every later step reads it (`ci.yml:357-394`). The new jobs need the same
  decision, and a job cannot read another job's step state — so the decision moves
  to the job both of them already depend on, and is published from there. The
  literal `-Ptest.population="$TEST_POPULATION"` stays as it is: the build resolves
  a `$`-prefixed value as an opaque token (`scripts/src/workflow_gradle.py:145-159`),
  so the variable's name is the identity the gates compare.

## Acceptance criteria

- [ ] AC-01: When a pull request runs, the system must run its test tasks without
  the coverage agent attached, and must name no coverage task on its path.
  Verification: `scripts/tests/test_ci_pr_path.py::PrPathHasNoCoverage::test_no_pr_path_job_names_a_coverage_task` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_no_pr_path_job_names_a_coverage_task'`
  Also verified by: `scripts/tests/test_ci_pr_path.py::PrPathHasNoCoverage::test_the_pull_request_path_runs_without_the_coverage_agent` — the same command; the agent is attached by the build's configuration, so naming no coverage task is not enough

- [ ] AC-02: When the suite job runs, the system must name every test task it runs
  in its own Gradle invocation.
  Verification: `scripts/tests/test_ci_pr_path.py::PrPathRunsTheWholeSuite::test_the_tests_job_names_every_test_task_it_runs` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_tests_job_names_every_test_task_it_runs'`
  Also verified by: `scripts/tests/test_check_gate_wiring.py::PartITest::test_a_covered_task_must_still_be_named_locally` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_covered_task_must_still_be_named_locally'` (existing test — it pins that a task CI reaches only through a covered entry point must still be named locally)

- [ ] AC-03: When a pull request runs, the system must judge detekt in exactly one
  job and name a detekt task in no other pull-request job.
  Verification: `scripts/tests/test_ci_pr_path.py::DetektIsJudgedOnce::test_exactly_one_pr_path_job_judges_detekt` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_exactly_one_pr_path_job_judges_detekt'`

- [ ] AC-04: When the affected-test map emits its command, the system must name the
  fast population and must not spell the tags.
  Verification: `scripts/tests/test_test_impact_map.py::CommandTest::test_the_command_names_a_population_not_a_tag_list` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_command_names_a_population_not_a_tag_list'`

- [ ] AC-05: When the map cannot read its base reference, the system must exit
  non-zero and name the reference it could not read.
  Verification: `scripts/tests/test_test_impact_map.py::CommandTest::test_a_missing_base_ref_is_refused` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_missing_base_ref_is_refused'`

- [ ] AC-06: When the affected-test job runs, the system must report which of its
  three outcomes happened: the selection ran, the change selected nothing, or the
  selection could not be computed.
  Verification: `scripts/tests/test_ci_pr_path.py::QuickJobReportsItsOwnOutcome::test_the_quick_job_names_which_of_its_three_outcomes_happened` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_quick_job_names_which_of_its_three_outcomes_happened'`

- [ ] AC-07: When a pull request runs, the system must run the affected-test job
  alongside the full suite job, not after it, and the merge gate must wait for both.
  Verification: `scripts/tests/test_ci_pr_path.py::QuickJobRunsTheAffectedSubset::test_the_quick_job_runs_beside_the_full_suite` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_quick_job_runs_beside_the_full_suite'`

- [ ] AC-08: When the affected-test job runs, the system must select one population
  in one spelling.
  Verification: `scripts/tests/test_ci_pr_path.py::OnePopulationPerJob::test_the_quick_job_names_one_population_in_one_spelling` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_quick_job_names_one_population_in_one_spelling'`

- [ ] AC-09: When the merge gate evaluates a pull request, the system must require
  the affected-test job and the detekt job.
  Verification: `scripts/tests/test_ci_pr_path.py::MergeGateRequiresTheNewJobs::test_the_merge_gate_needs_the_quick_and_detekt_jobs` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_merge_gate_needs_the_quick_and_detekt_jobs'`

- [ ] AC-10: When the coverage job is declared, the system must declare a timeout
  that covers the larger published population doubled.
  Verification: `scripts/tests/test_check_coverage_timeout.py::CoverageTimeoutTest::test_the_coverage_job_declares_the_timeout_of_the_larger_population` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_coverage_job_declares_the_timeout_of_the_larger_population'`

- [ ] AC-11: When a pull request changes the gate corpus, the system must run the
  control phase; when it does not, the system must run the cheap gate pass.
  Verification: `scripts/tests/test_ci_pr_path.py::ControlHarnessPlacement::test_the_control_phase_runs_on_a_gate_change_and_not_otherwise` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_control_phase_runs_on_a_gate_change_and_not_otherwise'`

- [ ] AC-12: When the control phase runs, the system must hit the line each control
  claims to prove.
  Verification: `scripts/tests/test_sgate_controls.py::TestTheControlThatMustBeAbleToFail::test_every_control_still_hits_the_line_it_claims_to_prove` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_every_control_still_hits_the_line_it_claims_to_prove'`

- [ ] AC-13: When the hook runs, the system must still delegate the detekt verdict,
  and the record must still match the CI workflow.
  Verification: `scripts/tests/test_check_hook_gates.py::HookGatesTest::test_a_record_that_matches_the_code_has_no_errors` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_record_that_matches_the_code_has_no_errors'`

- [ ] AC-14: When the two populations' floors are declared, the system must not let
  the wider population's floor sit below the narrower one's.
  Verification: `scripts/tests/test_check_coverage_floor_declaration.py::CoverageFloorDeclarationTest::test_the_wider_populations_floor_is_not_below_the_narrower_one` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_wider_populations_floor_is_not_below_the_narrower_one'`

- [ ] AC-15: When the coverage job renders its report and checks its floor, the
  system must read both from the same run.
  Verification: `scripts/tests/test_check_build_output_readers.py::OrderingIsTheRule::test_reader_before_the_invocation_passes` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_reader_before_the_invocation_passes'`

- [ ] AC-16: When the surfaces are compared, the system must find the same task on
  both sides of the gate-wiring check.
  Verification: `scripts/tests/test_check_gate_wiring.py::PartITest::test_the_real_surfaces_are_in_parity` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_real_surfaces_are_in_parity'` (existing test)

- [ ] AC-17: When a pull request runs, the system must report no coverage verdict for
  it, and must not fail it for a coverage breach — unless the pull request asks for
  the full population, in which case it reports a coverage verdict and a measurement
  below the floor fails it.
  Verification: `scripts/tests/test_ci_pr_path.py::PrPathHasNoCoverage::test_a_pull_request_reports_no_coverage_verdict` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_pull_request_reports_no_coverage_verdict'`

Each criterion is backed by an invariant: AC-01/AC-17 by INV-10, AC-02 by INV-01,
AC-03 by INV-10 and INV-07, AC-04/AC-08 by INV-02, AC-05/AC-06 by INV-05, AC-07 by
INV-10, AC-09 by INV-06, AC-10 by INV-09, AC-11 by INV-08, AC-12 by INV-08,
AC-13 by INV-07, AC-14 by INV-03, AC-15 by INV-04, AC-16 by INV-01.

Tests named above do not exist yet except where the binding says **existing test**:
AC-02 (second binding), AC-13, AC-15 and AC-16. AC-12's and AC-14's ids name a new
method in a class that already exists
(`scripts/tests/test_sgate_controls.py::TestTheControlThatMustBeAbleToFail`,
`scripts/tests/test_check_coverage_floor_declaration.py::CoverageFloorDeclarationTest`).
One existing test **changes**:
`scripts/tests/test_test_impact_map.py::CommandTest::test_the_command_names_the_tag_selection_that_runs_the_slow_half`
asserts `-Ptest.tags=fast,slow` and is replaced by AC-04's binding.

`verify-design` designs the rest and `implement` writes them; binding to a named test
is what makes the binding checkable later.

The suite they live in is **red today**: `PYTHONPATH=scripts/src python3 -m unittest
discover -s scripts/tests` runs 2239 tests and fails one —
`scripts/tests/test_check_adr_references.py` reports two unbaselined dangling ADR
references, the two citations of this change's decision record, which does not exist
yet (`scripts/check-adr-references.py:12`: a reference that resolves to no file in
`docs/decisions/` is an error). The record closes that failure; until it lands, the
suite is red for this reason alone.

## Open questions

1. **Where does the new work live — a new job inside `ci.yml`, or a new workflow
   file?** Nothing reads a job's `needs:` list, but `check-gate-wiring.py`'s Part E
   reads the CI surface as `ci.yml` plus `static-gates.sh`, and
   `check-hook-gates.py` hardcodes `ci.yml`. A new workflow file would shrink the
   CI detekt module set the record is checked against. This is a design decision
   with a known answer; it is recorded here because the spec's wording does not
   depend on it and the plan must not silently assume one.
2. **In what order is the `coverage` job placed in the file?**
   `check-coverage-timeout.py` takes the *first* job in file order that runs
   `:koverVerify`, and two registry controls sabotage the *first* occurrence of
   their literal text. A `coverage` job placed before the suite job changes which
   line each of them reads.
3. **What is the affected-test job's timeout, and what is its floor?** The plan sets
   neither. The job's cost depends on the selection, which the map cannot bound
   from the outside.
4. **What happens to the affected-test job when the change is in the gate corpus?**
   The map selects no test class for `scripts/`, `config/`, `.github/`, `docs/`,
   `openspec/` and `.specify/`. Under R6 that is the "selects nothing" outcome, but
   it is also the change class D3 singles out as needing the most attention. Whether
   that is one outcome or two is a design question the spec leaves open.
5. **Does the `run-full-tests` label remain the only way to get the full population
   onto a pull request?** D1 removes coverage from the pull-request path, and the
   label already selects the full population (`ci.yml:370`). The label's meaning
   therefore widens from "also run the slow half" to "also measure and enforce
   coverage". That is a change to a user-visible label's contract and is not
   written down anywhere yet.
6. **What is the non-instrumented cost of the suite on the runner?** Unmeasured.
   The plan's own step 0 exists for it, and the target of "≤ 40 runner-minutes"
   cannot be checked without it. Under the budget decision this is a measurement,
   not a requirement — but it is the measurement this change is judged by.
7. **Does the prior open change get archived first?** See Scope. The two deltas
   modify the same requirement, and the order decides whether the enforcement-locus
   clause survives.

## References

- `investigation.md` — the measurements, the wiring, and the plan's own corrections
- `openspec/changes/ci-fast-only-pr-full-on-schedule/` — the open change this one
  builds on
- `openspec/specs/test-execution-integrity/spec.md` — the capability this delta
  modifies
- `.github/workflows/ci.yml` — the job graph, the population decision, the Gradle
  invocation, the control proof, the merge gate
- `build.gradle.kts:57-100`, `:385-400`, `:470-477` — the population handoff, the
  coverage entry point
- `settings.gradle.kts:107-124`, `:156-203` — the selection and the floors
- `scripts/test-impact-map.py`, `scripts/changed-modules.py` — the affected-test
  signal
- `scripts/src/sgate/commands/_registry.py`, `scripts/src/sgate/commands/run_all.py`,
  `scripts/ci/static-gates.sh` — the registry and the control phase
- `scripts/check-gate-wiring.py`, `scripts/check-coverage-timeout.py`,
  `scripts/check-gradle-test-population.py`, `scripts/check-build-output-readers.py`,
  `scripts/check-hook-gates.py`, `scripts/check-workflow-exit-status.py`,
  `scripts/check-waiver-references.py` — the gates that read the workflow as text
- `.githooks/pre-push`, `scripts/pre-push-verdict.py`, `config/hooks/pre-push.json` —
  the local verdict
- `config/test-populations.properties`,
  `config/docs/coverage-cost.properties`,
  `config/docs/coverage-measurements.properties` — the published numbers
- `docs/decisions/2026-10-22-ci-fast-only-pr-full-on-schedule.md`,
  `docs/decisions/2026-10-23-a-population-is-a-name.md`,
  `docs/decisions/2026-10-22-the-test-cycle-has-a-map.md`,
  `docs/decisions/2026-10-23-the-local-verdict-judges-what-the-change-reaches.md`,
  `docs/decisions/2026-10-05-ci-checks-run-in-parallel.md` — the decisions this
  change sits on
- The decision this change is recorded in:
  `docs/decisions/2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path.md` (to be
  written; it does not exist yet)
