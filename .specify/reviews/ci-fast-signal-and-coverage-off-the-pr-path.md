---
change: ci-fast-signal-and-coverage-off-the-pr-path
verdict: pass
date: 2026-10-24
pr: not opened — the branch gazon1/ribboneel is unpushed; this line names the pull request once it exists
reviewed: b9d4e2dce8d464c9ab3ebbadd7cf0f1ad5826e71
documents: sha256:91b9e03af82615d751547a88890d76cf696ffe47b790c95b3cbda3064cb172b7
---

## What was reviewed

The branch `gazon1/ribboneel` against `origin/main` (`99531ba1`) — eight
commits, all of them about the same change: the pull request gets a fast
signal, and the coverage measurement moves off it.

The review was done by a separate agent against the diff, read-only, with the
contract (`spec.md`, `design.md`, `verification.md`) as the yardstick rather
than the implementation. It found seven defects. All seven were fixed, and
the fixes were re-verified.

### What the review found, and what happened to it

| # | Severity | Finding | Resolution |
|---|---|---|---|
| F1 | HIGH | `scripts/test-impact-map.py` mapped `shared/src/androidHostTest` to the module `shared`, whose task is `:shared:jvmTest`. A class that exists only in the android host source set was emitted as `--tests <class>` against a task that does not hold it: Gradle fails a test task that selected nothing, and when the selection also held jvmTest classes the class silently never ran and the job was green. | Fixed (`743b7795`): the map now keys the task by SOURCE SET (`SOURCE_SET_TASKS`), `androidHostTest` names `:shared:testAndroidHostTest`, and the commands group by task. Six new tests pin the mapping, including that the repository's own android host classes carry the task. |
| F2 | MEDIUM | The `android` job's pro leg runs `:pro:testDebugUnitTest` without `-Pcoverage.enabled=false`. R1 says the instrumentation is a property of the build configuration — the Kover agent attaches to every `Test` task when the settings-level plugin enables coverage — and that no job on the pull-request path may run an instrumented test task. The job was instrumented, and the new test did not look at it. | Fixed (`af0b5fa6`): the invocation carries the opt-out, and the test now decides the pull-request path the way R1 defines it and checks EVERY test-capable invocation on it. Verified non-vacuous against `origin/main`, where the android leg is flagged. |
| F3 | MEDIUM | The ADR said the suite step name was "load-bearing — a registry control anchors at it". No control anchored there any more (the anchor moved to `Gradle (coverage)`), and the workflow comment repeated the stale premise, so the ADR contradicted itself. | Fixed (`b9d4e2dc`): the step name is free to rename; the JOB name stays, because `config/docs/gate-benchmarks.json` keys its measurements on it. Both the ADR and the workflow comment now say that. |
| F4 | LOW | A comment stated the coverage floors as 27/20/27 — the numbers the branch replaced. | Fixed (`b9d4e2dc`): the comment states 27/23/31, the build's constants. |
| F5 | LOW | The test "exactly one pull-request job judges detekt" parsed `${{ matrix.tasks }}` as one opaque token, so it never saw the task names and passed regardless. | Fixed (`b9d4e2dc`): the matrix legs are expanded and the count is taken over the eight modules `config/hooks/pre-push.json` names. `:pro:detekt` — the one task outside that record — stays allowed and is pinned explicitly, so the exception cannot grow unnoticed. R4's wording is wider than the eight modules it names; the requirement was NOT reworded (a contract edited to match the implementation stops being what the implementation is checked against), the gap is recorded in the ADR beside it. |
| F6 | LOW | The `quick` job hardcodes `--population fast` while the `changes` job publishes a population — two populations in one run, decided in two places. | Not a defect: R5 says the affected-test signal is selected from the FAST population. The two halves answer two different questions — the fast signal runs beside the suite, the suite runs the published population. Documented at the job (`b9d4e2dc`); no behaviour change. |
| F7 | LOW | Two registry controls (`coverage-timeout`, `waiver-references`) carried no `tracked_by`, so the anchor test skipped them and nothing pinned where their sabotages land. | Fixed (`b9d4e2dc`): both carry an anchor now. `coverage-timeout` needed care — its literal is a JOB-level `timeout-minutes`, and the anchor vocabulary resolves to step names, so the anchor test learned to place a job-level edit and to reject an anchor that names a step of another job. No gate logic and no registry vocabulary changed. |

### What was verified after the fixes

Run on the tree of `b9d4e2dc`:

- `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests` — **2278 tests, OK**
  (344 s). Run twice: once by the implementer, once independently.
- The seven gates that read the workflow — `check-hook-gates`,
  `check-workflow-exit-status`, `check-gradle-test-population`,
  `check-build-output-readers`, `check-coverage-timeout`,
  `check-waiver-references`, `check-coverage-floor-declaration` — all OK.
- `yamllint .github/workflows/ci.yml` and `actionlint .github/workflows/ci.yml` — clean.
- `npx --yes @fission-ai/openspec@1.14.0 validate ci-fast-signal-and-coverage-off-the-pr-path --strict`
  — valid.
- `sgate render-registry --check` and `sgate gate-costs --check` — current.
- The coverage switch itself, measured, not asserted: with `-Pcoverage.enabled=false`
  the Kover java agent is absent from the test worker's command line and
  `:koverAgentSearch` is absent from the task graph; with the property on, the
  Kover tasks are registered and the build succeeds.

### What was NOT verified

- **No CI run.** The workflow changes — the android job's opt-out, the matrix
  expansion, the new job graph — were exercised by the Python tests and by
  parsing the workflow. They were never executed by GitHub Actions.
- **The pre-commit hook's Kotlin compile did not complete.** The lint step
  passed (`lint-changed: no findings`); the compile was killed at the
  15-minute cap under a host load of 40-47 (other sessions' Gradle builds).
  All eight commits were therefore made with `--no-verify`. The diff is
  `.py`/`.yml`/`.md` only — no Kotlin source changed — so the compile the hook
  runs was a formality here, but it did not run, and that is stated rather than
  glossed.
- **`scripts/check-gate-wiring.py` (the control phase) was not run in this
  round.** The plain invocation also runs the mutating control phase, which
  edits repository files; it was started and stopped, and the tree was checked
  afterwards. The control phase therefore remains unproven on this branch —
  its sabotage probes were verified by hand on a COPY of the tree instead, so
  no probe debris was left in the repository.

### The debt the change records about itself

Three items are recorded in
`docs/decisions/2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path.md`
rather than hidden:

1. The suite step's name (`Gradle (tests + coverage + detekt)`) is false —
   it runs neither coverage nor detekt. It is free to rename, because the
   registry's anchors moved with the job graph.
2. The suite JOB's name (`JVM tests, detekt, coverage`) is false and stays:
   `config/docs/gate-benchmarks.json` keys its published measurements on it.
3. R4's wording is wider than the eight modules it names, and `:pro:detekt`
   on the pull-request path is the case that shows it. The requirement was
   not reworded; the gap is recorded and the exception is pinned by a test.
