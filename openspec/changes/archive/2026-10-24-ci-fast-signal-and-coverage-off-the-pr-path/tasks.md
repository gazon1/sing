# Tasks — a fast signal on the pull-request path

Each task names the result that exists afterwards, the files it owns, what it depends
on, the test that proves it, and the acceptance criterion it serves. The order is
technical: the tool's contract first, then the workflow that depends on it, then the
record that describes both.

The gate suite is run with `PYTHONPATH=scripts/src`, because the suite imports the
gate scripts as `scripts.*` and the registry as `sgate.*`:

```bash
PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests
```

## Phase 1 — the foundation: the build's opt-out and the map's contract

- [x] T001 The coverage agent is attached to a test JVM only when the build's
  configuration says so, and the pull-request path says no.
  - Files: `settings.gradle.kts`, `scripts/tests/test_ci_pr_path.py`
  - Depends on: none
  - Verification: `scripts/tests/test_ci_pr_path.py::PrPathHasNoCoverage::test_the_pull_request_path_runs_without_the_coverage_agent` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_pull_request_path_runs_without_the_coverage_agent'`
  - Acceptance: AC-01
  - Scenarios: V-14
  - Note: this is the change the plan's economics rest on, and it is a **build** change, not a job-graph change. Measured 2026-10-23: `./gw :shared:jvmTest --tests '…CapabilityFlagTest' --info` prints a test-worker command line containing `-javaagent:…/build/kover/kover-jvm-agent-0.9.9.jar`, in an invocation that names no coverage task at all; `./gw :shared:jvmTest --dry-run` lists `:koverAgentSearch`. The cause is `settings.gradle.kts:214`, where `enableCoverage()` is unconditional — the agent is attached to `tasks.withType(Test)` at configuration time, so dropping `koverReport` from an invocation moves the report and leaves the cost.
  - Note: the empirical half of this task is not a unit test. After the opt-out lands, the same probe must show a test-worker command line **without** the agent. Until that observation exists, the saving is a claim, not a measurement.
  - Note: `scripts/tests/test_ci_pr_path.py` is created here and grows in the tasks below.

- [x] T002 The affected-test map names a population instead of spelling out a tag
  list, so the command it emits selects one declared population.
  - Files: `scripts/test-impact-map.py`, `scripts/tests/test_test_impact_map.py`
  - Depends on: none
  - Verification: `scripts/tests/test_test_impact_map.py::CommandTest::test_the_command_names_a_population_not_a_tag_list` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_command_names_a_population_not_a_tag_list'`
  - Also verified by: `scripts/tests/test_test_impact_map.py::CommandTest::test_a_missing_base_ref_is_refused` — the same command builder, reached through the failure path
  - Acceptance: AC-04, AC-08
  - Scenarios: V-02, V-04
  - Note: the existing test `test_the_command_names_the_tag_selection_that_runs_the_slow_half` is replaced by this one — it pins the behaviour this task changes.

- [x] T003 The affected-test map refuses a base reference it cannot resolve, naming
  the reference, instead of reporting an empty selection and exiting zero.
  - Files: `scripts/test-impact-map.py`, `scripts/tests/test_test_impact_map.py`
  - Depends on: T002
  - Verification: `scripts/tests/test_test_impact_map.py::CommandTest::test_a_missing_base_ref_is_refused` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_missing_base_ref_is_refused'`
  - Acceptance: AC-05
  - Scenarios: V-03

- [x] T004 The affected-test map drops a selected class that the chosen population
  does not contain, and names the dropped class in its report.
  - Files: `scripts/test-impact-map.py`, `scripts/tests/test_test_impact_map.py`
  - Depends on: T002
  - Verification: `scripts/tests/test_test_impact_map.py::CommandTest::test_the_command_names_a_population_not_a_tag_list` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_command_names_a_population_not_a_tag_list'`
  - Also verified by: `scripts/tests/test_test_impact_map.py::CommandTest::test_a_class_outside_the_population_is_dropped` — the same command; a finer-grained assertion than the spec's binding
  - Acceptance: AC-08
  - Scenarios: V-06

- [x] T005 The affected-test map reports an empty selection on a channel its caller
  can read, so "the change selects nothing" is distinguishable from a failure.
  - Files: `scripts/test-impact-map.py`, `scripts/tests/test_test_impact_map.py`
  - Depends on: T003
  - Verification: `scripts/tests/test_ci_pr_path.py::QuickJobReportsItsOwnOutcome::test_the_quick_job_names_which_of_its_three_outcomes_happened` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_quick_job_names_which_of_its_three_outcomes_happened'`
  - Also verified by: `scripts/tests/test_test_impact_map.py::CommandTest::test_an_empty_selection_says_so` — the same command; the map's own side of the empty selection
  - Acceptance: AC-06
  - Scenarios: V-05

## Phase 2 — the pull-request path's job graph

- [x] T006 The coverage task leaves the pull-request path, and the suite job names
  every test task it runs.
  - Files: `.github/workflows/ci.yml`, `scripts/tests/test_ci_pr_path.py`, `scripts/src/sgate/commands/_registry.py`
  - Depends on: T001, T002
  - Verification: `scripts/tests/test_ci_pr_path.py::PrPathRunsTheWholeSuite::test_the_tests_job_names_every_test_task_it_runs` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_tests_job_names_every_test_task_it_runs'`
  - Also verified by: `scripts/tests/test_ci_pr_path.py::PrPathHasNoCoverage::test_no_pr_path_job_names_a_coverage_task` — the same command; and `scripts/tests/test_check_gate_wiring.py::PartITest::test_a_covered_task_must_still_be_named_locally` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_covered_task_must_still_be_named_locally'`
  - Acceptance: AC-01, AC-02, AC-16
  - Scenarios: V-14, V-15
  - Note: `scripts/tests/test_ci_pr_path.py` grows here; one file, because the pull-request path's shape is one fact.
  - Note: the expected set of test tasks is **not** the implementer's. It is the coverage entry point's `dependsOn` list (`build.gradle.kts:390-399`) minus the tasks another job owns. Six of the seven stay with the suite job — `:shared:jvmTest`, `:shared:testAndroidHostTest`, `:desktopApp:test`, `:mcp-server:test`, `:androidApp:test`, `:detekt-rules:test`; only `:adr-core:jvmTest` goes, because the ADR job owns it (`ci.yml:745-751`).
  - Note: the `Kover verify (detekt-rules)` step (`ci.yml:887-888`) keeps its **exact two lines**. The `waiver-references` control's `old` text is that block verbatim (`_registry.py:1274`), and `_replace` refuses a no-op — a reformat would make the control phase error, not fail quietly.
  - Note: the invocation's **non-test** parts stay. `:mcp-server:jar` must remain in the same invocation (`ci.yml:434-437`): `McpServerEndToEndTest` skips itself when the jar is absent — a silent pass. `:shared:verifyToolSurface` stays too.
  - Note: the step's **name** stays `Gradle (tests + coverage + detekt)`, because `scripts/src/sgate/commands/_registry.py:1215` anchors a control at it and `scripts/check-waiver-references.py:31-38` requires the anchor to resolve to a step name. The name becomes false; renaming it breaks the anchor. The debt is recorded, not paid silently.
    (Paid later: this task re-points that anchor at the coverage job, and once it had moved the name was free. The follow-up change renamed the step to `Gradle (tests)` and the job to `JVM tests`.)
  - Note: the `GRADLE_TASK_PARITY` entries for `:shared:jvmTest` and `:desktopApp:test` change from `covered` to `both`, because CI now names them. The gate passes either way (`scripts/check-gate-wiring.py:2343-2349` only asks that `check.sh` names a `covered` task), but an entry that stops describing the graph is a declaration that rots.

- [x] T007 A coverage job carries the instrumented suite, the floor, the report and
  the table, and declares the timeout the published measurement derives.
  - Files: `.github/workflows/ci.yml`, `scripts/tests/test_ci_pr_path.py`, `scripts/tests/test_check_coverage_timeout.py`
  - Depends on: T006
  - Verification: `scripts/tests/test_check_coverage_timeout.py::CoverageTimeoutTest::test_the_coverage_job_declares_the_timeout_of_the_larger_population` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_coverage_job_declares_the_timeout_of_the_larger_population'`
  - Also verified by: `scripts/tests/test_check_build_output_readers.py::OrderingIsTheRule::test_reader_before_the_invocation_passes` — the same command; the report and the floor are read in the order the rule requires
  - Acceptance: AC-10, AC-15
  - Scenarios: V-18, V-19
  - Note: the job is declared **before** the suite job in the workflow file, and its `Coverage floors` step keeps its exact text — three gates resolve their target by the first match.
  - Note: the **population is decided once**. The `Select test population` step (`ci.yml:357-374`) and the "Test population is one of the known ones" step (`ci.yml:388-394`) move to the `changes` job, which publishes the value as an output; the jobs receive it as `env: TEST_POPULATION: ${{ needs.changes.outputs.population }}`. The literal `-Ptest.population="$TEST_POPULATION"` in the `run:` lines stays verbatim — `scripts/src/workflow_gradle.py:145-159` resolves a `$`-prefixed value as an opaque token, and three controls embed that exact string. Carrying the decision into a second job by copy is what `REQ-24` forbids.

- [x] T008 Detekt is judged in one pull-request job, over the same eight modules
  the record names, and the hook keeps its own verdict over them.
  - Files: `.github/workflows/ci.yml`, `scripts/tests/test_ci_pr_path.py`
  - Depends on: T006
  - Verification: `scripts/tests/test_ci_pr_path.py::DetektIsJudgedOnce::test_exactly_one_pr_path_job_judges_detekt` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_exactly_one_pr_path_job_judges_detekt'`
  - Also verified by: `scripts/tests/test_check_hook_gates.py::HookGatesTest::test_a_record_that_matches_the_code_has_no_errors` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_record_that_matches_the_code_has_no_errors'`
  - Acceptance: AC-03, AC-13
  - Scenarios: V-16, V-20

- [x] T009 An affected-test job runs beside the suite job and reports which of its
  three outcomes happened.
  - Files: `.github/workflows/ci.yml`, `scripts/tests/test_ci_pr_path.py`
  - Depends on: T006
  - Verification: `scripts/tests/test_ci_pr_path.py::QuickJobRunsTheAffectedSubset::test_the_quick_job_runs_beside_the_full_suite` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_quick_job_runs_beside_the_full_suite'`
  - Also verified by: `scripts/tests/test_ci_pr_path.py::QuickJobReportsItsOwnOutcome::test_the_quick_job_names_which_of_its_three_outcomes_happened` — the same command; and `scripts/tests/test_ci_pr_path.py::OnePopulationPerJob::test_the_quick_job_names_one_population_in_one_spelling` — the same command
  - Acceptance: AC-06, AC-07, AC-08
  - Scenarios: V-01, V-07, V-09

- [x] T010 The merge gate requires the affected-test job and the detekt job.
  - Files: `.github/workflows/ci.yml`, `scripts/tests/test_ci_pr_path.py`
  - Depends on: T008, T009
  - Verification: `scripts/tests/test_ci_pr_path.py::MergeGateRequiresTheNewJobs::test_the_merge_gate_needs_the_quick_and_detekt_jobs` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_merge_gate_needs_the_quick_and_detekt_jobs'`
  - Acceptance: AC-09
  - Scenarios: V-17

- [x] T011 The control phase runs when the gate corpus changed or on the schedule,
  and the cheap gate pass runs otherwise.
  - Files: `.github/workflows/ci.yml`, `scripts/tests/test_ci_pr_path.py`
  - Depends on: T006
  - Verification: `scripts/tests/test_ci_pr_path.py::ControlHarnessPlacement::test_the_control_phase_runs_on_a_gate_change_and_not_otherwise` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_control_phase_runs_on_a_gate_change_and_not_otherwise'`
  - Acceptance: AC-11
  - Scenarios: V-10, V-11
  - Note: the gate corpus is `scripts/**`, `config/**` and `.github/**`. The `changes` job gains a `gates` output for it; the existing `scripts` output is left alone, because it also decides whether the `static` job runs.

- [x] T012 Every registry control still hits the line it names after the workflow's
  job graph moved.
  - Files: `scripts/src/sgate/commands/_registry.py`, `scripts/tests/test_sgate_controls.py`
  - Depends on: T007
  - Verification: `scripts/tests/test_sgate_controls.py::TestTheControlThatMustBeAbleToFail::test_every_control_still_hits_the_line_it_claims_to_prove` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_every_control_still_hits_the_line_it_claims_to_prove'`
  - Acceptance: AC-12
  - Scenarios: V-12, V-13
  - Note: a control whose literal `old` text no longer matches the line it names is a control that proves nothing. **Three** of the four controls that read `ci.yml` move to the coverage job's lines — `coverage-timeout`, `gradle-test-population` and `build-output-readers`. The fourth, `waiver-references`, stays anchored at the `Kover verify (detekt-rules)` step, which stays in the suite job; its `old` text must remain byte-identical there, and `_replace` asserts it (`gate_controls.py:52`) — a reformat makes the control phase error rather than fail quietly.
  - Note: a control has **two** halves, and only one of them moves by itself. The `old` text resolves by first occurrence (`scripts/src/sgate/gate_controls.py:39-55`), but each control's `tracked_by` anchor names a step, and `scripts/check-waiver-references.py:31-38` requires that anchor to resolve to a step name. Three of the four controls that read `ci.yml` carry such an anchor (`ci.yml#Gradle (tests + coverage + detekt)`, and `ci.yml#Normalise desktop results into the scenario matrix` twice). This task re-points the anchors whose steps moved, so that each control's anchor and its sabotage name the same step — a control that proves a step it does not claim is a green proof of nothing.

- [x] T013 The wider population's floor is not below the narrower one's, and the two
  are declared in the place the floor gate expects.
  - Files: `settings.gradle.kts`, `scripts/check-coverage-floor-declaration.py`, `scripts/tests/test_check_coverage_floor_declaration.py`
  - Depends on: T007
  - Verification: `scripts/tests/test_check_coverage_floor_declaration.py::CoverageFloorDeclarationTest::test_the_wider_populations_floor_is_not_below_the_narrower_one` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_the_wider_populations_floor_is_not_below_the_narrower_one'`
  - Also verified by: `scripts/tests/test_check_coverage_floor_declaration.py::CoverageFloorDeclarationTest::test_a_clean_tree_has_no_findings` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_clean_tree_has_no_findings'`
  - Acceptance: AC-14
  - Scenarios: V-21
  - Note: `FULL_BRANCH_COVERAGE_FLOOR` (20 → 23) and `FULL_LINE_COVERAGE_FLOOR` (27 → 31) rise; `FULL_INSTRUCTION_COVERAGE_FLOOR` (27) stays. The `require` blocks at `settings.gradle.kts:183-201` move with them.

- [x] T014 A pull request reports no coverage verdict.
  - Files: `.github/workflows/ci.yml`, `scripts/tests/test_ci_pr_path.py`
  - Depends on: T007
  - Verification: `scripts/tests/test_ci_pr_path.py::PrPathHasNoCoverage::test_a_pull_request_reports_no_coverage_verdict` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_pull_request_reports_no_coverage_verdict'`
  - Acceptance: AC-17
  - Scenarios: V-22

## Phase 3 — the record

- [x] T015 The change's contract is written down where a reader will find it: the
  decision record, the tier documentation, the gate-cost table and the CI skill.
  - Files: `docs/decisions/2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path.md`, `docs/testing/what-each-tier-proves.md`, `docs/testing/gate-costs.md`, `docs/scripts/gate-registry.md`, `.agents/skills/singularity-todo-ci-cd/SKILL.md`
  - Depends on: T007, T008, T011, T012, T013
  - Verification: `scripts/tests/test_check_doc_dead_refs.py::GeneratedReferenceDetection::test_a_real_dead_reference_is_not_reported_as_generated` — `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests -k 'test_a_real_dead_reference_is_not_reported_as_generated'`
  - Also verified by: `python3 scripts/check-doc-links.sh` — the link checker the `doc links` gate runs
  - Acceptance: AC-13
  - Scenarios: none — this task records the decisions the other tasks made
  - Note: the spec has no criterion for "the contract is written down", so this task's own verification is a documentation gate rather than a criterion's binding. The `AC-13` above is here because the record the task writes is the one the hook's gate checks. The review-spec gate is the place to decide whether the spec should carry a documentation criterion instead.
  - Note: `docs/scripts/gate-registry.md` is generated from the registry, not written by hand: `PYTHONPATH=scripts/src python3 -m sgate render-registry`.
  - Note: this is the one task whose verification is **not** named in `spec.md`. The spec
    has no criterion for "the contract is written down", so the documentation gate is
    named here directly. The review-spec gate is the place to decide whether the spec
    should carry that criterion, or whether a documentation task is allowed to stand on
    its own gate.

## After the tasks

The measurement, not a criterion. After the first real pull request with this
change:

```bash
gh api repos/gazon1/sing/actions/runs/<run-id>/jobs \
  --jq '.jobs[]|"  \(.name) \(.conclusion)"'
```

Compare the total runner-minutes against the 58.9 measured in `investigation.md` and
record the result in `docs/testing/gate-costs.md`. The number is a measurement; the
budget decision keeps it out of the contract.
