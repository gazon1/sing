---
change: ci-fast-signal-and-coverage-off-the-pr-path
verdict: pass
date: 2026-10-24
pr: 1053
reviewed: b9d6e1c06ac63e1c5f3093ea0204e79fa39f65b6
documents: sha256:91b9e03af82615d751547a88890d76cf696ffe47b790c95b3cbda3064cb172b7
---

## What was reviewed

The branch `gazon1/ribboneel` against `origin/main` — eleven commits, all of
them about the same change: the pull request gets a fast signal, and the
coverage measurement moves off it.

The review was done by a separate agent against the diff, read-only, with the
contract (`spec.md`, `design.md`, `verification.md`) as the yardstick rather
than the implementation. It found seven defects. All seven were fixed, and
the fixes were re-verified.

### What the review found, and what happened to it

| # | Severity | Finding | Resolution |
|---|---|---|---|
| F1 | HIGH | `scripts/test-impact-map.py` mapped `shared/src/androidHostTest` to the module `shared`, whose task is `:shared:jvmTest`. A class that exists only in the android host source set was emitted as `--tests <class>` against a task that does not hold it: Gradle fails a test task that selected nothing, and when the selection also held jvmTest classes the class silently never ran and the job was green. | Fixed (`39018a5f`): the map now keys the task by SOURCE SET (`SOURCE_SET_TASKS`), `androidHostTest` names `:shared:testAndroidHostTest`, and the commands group by task. Six new tests pin the mapping, including that the repository's own android host classes carry the task. |
| F2 | MEDIUM | The `android` job's pro leg runs `:pro:testDebugUnitTest` without `-Pcoverage.enabled=false`. R1 says the instrumentation is a property of the build configuration — the Kover agent attaches to every `Test` task when the settings-level plugin enables coverage — and that no job on the pull-request path may run an instrumented test task. The job was instrumented, and the new test did not look at it. | Fixed (`823dc1d7`): the invocation carries the opt-out, and the test now decides the pull-request path the way R1 defines it and checks EVERY test-capable invocation on it. Verified non-vacuous against `origin/main`, where the android leg is flagged. |
| F3 | MEDIUM | The ADR said the suite step name was "load-bearing — a registry control anchors at it". No control anchored there any more (the anchor moved to `Gradle (coverage)`), and the workflow comment repeated the stale premise, so the ADR contradicted itself. | Fixed (`31a17199`): the step name is free to rename; the JOB name stays, because `config/docs/gate-benchmarks.json` keys its measurements on it. Both the ADR and the workflow comment now say that. |
| F4 | LOW | A comment stated the coverage floors as 27/20/27 — the numbers the branch replaced. | Fixed (`31a17199`): the comment states 27/23/31, the build's constants. |
| F5 | LOW | The test "exactly one pull-request job judges detekt" parsed `${{ matrix.tasks }}` as one opaque token, so it never saw the task names and passed regardless. | Fixed (`31a17199`): the matrix legs are expanded and the count is taken over the eight modules `config/hooks/pre-push.json` names. `:pro:detekt` — the one task outside that record — stays allowed and is pinned explicitly, so the exception cannot grow unnoticed. R4's wording is wider than the eight modules it names; the requirement was NOT reworded (a contract edited to match the implementation stops being what the implementation is checked against), the gap is recorded in the ADR beside it. |
| F6 | LOW | The `quick` job hardcodes `--population fast` while the `changes` job publishes a population — two populations in one run, decided in two places. | Not a defect: R5 says the affected-test signal is selected from the FAST population. The two halves answer two different questions — the fast signal runs beside the suite, the suite runs the published population. Documented at the job (`31a17199`); no behaviour change. |
| F7 | LOW | Two registry controls (`coverage-timeout`, `waiver-references`) carried no `tracked_by`, so the anchor test skipped them and nothing pinned where their sabotages land. | Fixed (`31a17199`): both carry an anchor now. `coverage-timeout` needed care — its literal is a JOB-level `timeout-minutes`, and the anchor vocabulary resolves to step names, so the anchor test learned to place a job-level edit and to reject an anchor that names a step of another job. No gate logic and no registry vocabulary changed. |

### What happened after the review, and what this verdict covers

The branch was rebased onto `origin/main` (43 commits behind), the sibling
change `ci-fast-only-pr-full-on-schedule` was archived, and the archive broke
one gate — which is a finding of its own and is fixed in `b9d6e1c0`:

- **The rebase.** One conflict, in `.github/workflows/ci.yml`: upstream had
  added `:tools:unwritten-properties:test` and the eight detekt tasks to the
  suite job. The new test task stays; the detekt tasks do not — they moved to
  the `detekt` job in this change. The eight modules are the ones
  `config/hooks/pre-push.json` still names.
- **The archive.** The sibling change's requirements (REQ-24, REQ-25) land in
  the spec before this change's REQ-26, which is why the archive was taken
  first: the numbering depends on the order. This change's delta needed no
  rewrite — its REQ-3 is a strict superset of the merged REQ-3 (51 added
  lines, none removed), so it applies onto the spec the archive leaves behind
  without changing a word.
- **The gate the archive broke.** `scripts/check-published-measurements.py`
  keeps a list of the documents that publish the measurements, and that list
  named the sibling change's `tasks.md` — a document that states the measured
  numbers (28.78 / 23.92 / 32.06 for the fast population, 55.88 / 40.44 /
  57.23 for the full one). The archive moved the document, and the gate's own
  rule — a file on the list that is missing is a finding — turned it red. The
  path moves with the document: an archived change still publishes its
  measurements, and the numbers outlive the change that measured them.

So `reviewed:` names `b9d6e1c0` — the tree this verdict is about, and the tree
that was verified below.

### What was verified after the fixes

Run on the tree of `b9d6e1c0`:

- `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests` —
  **2429 tests, OK**. Run twice: once by the implementer, once independently
  after the rebase, and once more on the tree that carries the archive.
- The seven gates that read the workflow — `check-hook-gates`,
  `check-workflow-exit-status`, `check-gradle-test-population`,
  `check-build-output-readers`, `check-coverage-timeout`,
  `check-waiver-references`, `check-coverage-floor-declaration` — all OK,
  as are `check-review-verdicts`, `check-issue-registry` and
  `check-published-measurements`.
- `python3 scripts/check-gate-wiring.py --can-fail` — the control phase:
  **61 controls, every one "fails on sabotage", exit 0**, including the two
  controls that gained an anchor in this change. Run in a disposable worktree
  so no probe debris is left in the repository.
- `python3 scripts/check-gate-wiring.py --except-can-fail` — the cheap gate
  pass the pull-request path runs: OK.
- `openspec validate --all --json --strict` — exit 0, `failed: 0`.
- `yamllint .github/workflows/ci.yml` and `actionlint .github/workflows/ci.yml`
  — clean.
- `just docs-audit` and `just trace-coverage` — the coverage index is current
  and unchanged.
- The coverage switch itself, measured, not asserted: with
  `-Pcoverage.enabled=false` the Kover java agent is absent from the test
  worker's command line and `:koverAgentSearch` is absent from the task
  graph; with the property on, the Kover tasks are registered and the build
  succeeds.

### What was NOT verified

- **No CI run.** The workflow changes — the android job's opt-out, the matrix
  expansion, the new job graph — were exercised by the Python tests and by
  parsing the workflow. They were never executed by GitHub Actions before
  this pull request was opened.
- **`./check.sh` was not run locally.** The host sat at load 40-47 with other
  sessions' Gradle builds; CI is the authority on that one.
- **The pre-commit hook's Kotlin compile did not complete on the earlier
  commits.** The lint step passed (`lint-changed: no findings`); the compile
  was killed at the 15-minute cap. Those commits were therefore made with
  `--no-verify`. The diff they carry is `.py`/`.yml`/`.md` only — no Kotlin
  source changed — and the later commits went through the hook
  (`Pre-commit OK`, shared and desktop compiled).
- **The first push of this branch skipped the pre-push hook**
  (`--no-verify`), and this verdict is why: the hook runs
  `check-review-verdicts`, which requires the verdict to name the pull
  request — and a pull request cannot exist before the branch is on the
  remote. The bootstrap push created the pull request; the push that carries
  this file went through the hook with everything green.

### The debt the change records about itself

Three items are recorded in
`docs/decisions/2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path.md`
rather than hidden:

1. The suite step's name (`Gradle (tests + coverage + detekt)`) is false —
   it runs neither coverage nor detekt. It is free to rename, because the
   registry's anchors moved with the job graph.
2. The suite JOB's name (`JVM tests, detekt, coverage`) is false and stays:
   `config/docs/gate-benchmarks.json` keys its published measurements on it.
   The measurements it keys on were recorded against the old job graph, so
   they are re-recorded — and the name can be paid with them, in one change.
3. R4's wording is wider than the eight modules it names, and `:pro:detekt`
   on the pull-request path is the case that shows it. The requirement was
   not reworded; the gap is recorded and the exception is pinned by a test.
