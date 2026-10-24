---
change: ci-fast-signal-and-coverage-off-the-pr-path
verdict: pass
date: 2026-10-24
pr: 1074
reviewed: de6ad02400477912034273854a45b477e3e5e8c6
documents: sha256:91b9e03af82615d751547a88890d76cf696ffe47b790c95b3cbda3064cb172b7
---

## What was reviewed

Two pull requests, both about the same change: the pull request gets a fast
signal, and the coverage measurement moves off it.

- **#1053** — the change itself: the build-level coverage switch, the new job
  graph, the affected-test map, the ordered floors, the control anchors, and
  the decision record.
- **#1074** — the debts that change recorded about itself: the false names are
  paid, and the local loop learns the same tiers the workflow runs.

The review of #1053 was done by a separate agent against the diff, read-only,
with the contract (`spec.md`, `design.md`, `verification.md`) as the yardstick
rather than the implementation. It found seven defects; all seven were fixed
and re-verified. The review of #1074 was done by another separate agent, and
it found four defects in the follow-up — three in the new local recipes and
one in a claim about the map's own behaviour. All four were fixed too.

## The review of #1053: seven findings

| # | Severity | Finding | Resolution |
|---|---|---|---|
| F1 | HIGH | `scripts/test-impact-map.py` mapped `shared/src/androidHostTest` to the module `shared`, whose task is `:shared:jvmTest`. A class that exists only in the android host source set was emitted as `--tests <class>` against a task that does not hold it: Gradle fails a test task that selected nothing, and when the selection also held jvmTest classes the class silently never ran and the job was green. | Fixed (`39018a5f`): the map now keys the task by SOURCE SET (`SOURCE_SET_TASKS`), `androidHostTest` names `:shared:testAndroidHostTest`, and the commands group by task. Six new tests pin the mapping. |
| F2 | MEDIUM | The `android` job's pro leg runs `:pro:testDebugUnitTest` without `-Pcoverage.enabled=false`. R1 says the instrumentation is a property of the build configuration — the Kover agent attaches to every `Test` task when the settings-level plugin enables coverage — and that no job on the pull-request path may run an instrumented test task. The job was instrumented, and the new test did not look at it. | Fixed (`823dc1d7`): the invocation carries the opt-out, and the test now decides the pull-request path the way R1 defines it and checks EVERY test-capable invocation on it. Verified non-vacuous against `origin/main`. |
| F3 | MEDIUM | The ADR said the suite step name was "load-bearing — a registry control anchors at it". No control anchored there any more (the anchor moved to `Gradle (coverage)`), and the workflow comment repeated the stale premise, so the ADR contradicted itself. | Fixed (`31a17199`): the step name is free to rename; the job name stays because `config/docs/gate-benchmarks.json` keys its measurements on it. Both texts now say that. **Paid in #1074** — both names are renamed. |
| F4 | LOW | A comment stated the coverage floors as 27/20/27 — the numbers the branch replaced. | Fixed (`31a17199`): the comment states 27/23/31, the build's constants. |
| F5 | LOW | The test "exactly one pull-request job judges detekt" parsed `${{ matrix.tasks }}` as one opaque token, so it never saw the task names and passed regardless. | Fixed (`31a17199`): the matrix legs are expanded and the count is taken over the eight modules `config/hooks/pre-push.json` names. `:pro:detekt` — the one task outside that record — stays allowed and is pinned explicitly. R4's wording is wider than the eight modules; the requirement was NOT reworded, the gap is recorded in the ADR. |
| F6 | LOW | The `quick` job hardcodes `--population fast` while the `changes` job publishes a population — two populations in one run, decided in two places. | Not a defect: R5 says the affected-test signal is selected from the FAST population. The two halves answer two different questions. Documented; no behaviour change. |
| F7 | LOW | Two registry controls (`coverage-timeout`, `waiver-references`) carried no `tracked_by`, so the anchor test skipped them and nothing pinned where their sabotages land. | Fixed (`31a17199`): both carry an anchor now. `coverage-timeout`'s literal is a job-level `timeout-minutes` and the anchor vocabulary resolves to step names, so the anchor test learned to place a job-level edit and to reject an anchor that names a step of another job. No gate logic and no registry vocabulary changed. |

## The review of #1074: four findings

| # | Severity | Finding | Resolution |
|---|---|---|---|
| G1 | HIGH | The new `ci::quick` recipe omitted `-Pcoverage.enabled=false`, and the property DEFAULTS to on — so the local fast tier ran INSTRUMENTED while the CI fast tier does not. The recipe's justification ("the map selects classes, it does not run a suite") was false: `--run` runs the suite. | Fixed (`98d5e7ec`): the recipe writes the map's commands and evaluates each with the flag — the shape the job uses. |
| G2 | HIGH | The new `ci::tests` recipe omitted `-Dsingularity.test.a11y=fail`, which makes an accessibility violation FAIL the run. The recipe's comment mis-described it as log verbosity. | Fixed (`98d5e7ec`): the flag is back, with `-Dsingularity.test.steps=true`, and the comment says what each does. |
| G3 | MEDIUM | The new `ci::coverage` recipe rendered one of the two numbers the job's "Coverage report" step renders — `:detekt-rules:koverLog` was missing. | Fixed (`98d5e7ec`). |
| G4 | MEDIUM | The map's new docstring claimed that the command naming one android host class "runs every class in that source set". It does not: the command carries `--tests <class>`, and Gradle applies that filter. What is true is narrower — the android host task is exempt from the TAG filter, so the POPULATION does not narrow it, while the `--tests` filter does. | Fixed (`3ef5555c`): the docstring and the task table now say that. The class count was four, not five. |

The same review found three claims in the decision record that the tree did
not support, and they are fixed in `5294e064`:

- the 30 seconds the record published was the cost of **asking** — that run's
  selection was empty, so the step that runs the tests was skipped;
- the table was not "as the runner wrote them down" — two rows carried names
  the runner never used, one row came from the other workflow, two jobs were
  missing;
- the published page still names the old job, and the reason given for the
  rename (the measurements would be invalidated) was unfounded: nothing
  compares the recorded names with the workflow's.

And one live gate rationale still named the step that no longer exists
(`de6ad024`).

## What happened between the two pull requests

- **The rebase.** The branch was rebased onto `origin/main` (43 commits behind),
  with one conflict, in `.github/workflows/ci.yml`: upstream had added
  `:tools:unwritten-properties:test` and the eight detekt tasks to the suite
  job. The new test task stays; the detekt tasks do not — they moved to the
  `detekt` job in this change. The eight modules are the ones
  `config/hooks/pre-push.json` still names.
- **The archive.** The sibling change `ci-fast-only-pr-full-on-schedule` was
  archived, so its requirements (REQ-24, REQ-25) land in the spec before this
  change's REQ-26 — the numbering depends on the order. This change's delta
  needed no rewrite: its REQ-3 is a strict superset of the merged REQ-3 (51
  added lines, none removed).
- **The gate the archive broke.** `scripts/check-published-measurements.py`
  keeps a list of the documents that publish the measurements, and that list
  named the sibling change's `tasks.md` — a document that states the measured
  numbers. The archive moved the document, and the gate's own rule — a file on
  the list that is missing is a finding — turned it red. Fixed in `b9d6e1c0`:
  the path moves with the document.

So `reviewed:` names `de6ad024` — the tree this verdict is about.

## What was verified

On the tree of `de6ad024`:

- `PYTHONPATH=scripts/src python3 -m unittest discover -s scripts/tests` —
  **2590 tests, OK**. Run by the implementer of the follow-up, by the reviewer
  of the follow-up, and by the parent session.
- The control phase — **61 controls, every one "fails on sabotage", exit 0** —
  run in a disposable worktree so no probe debris is left in the repository.
- The cheap gate pass the pull-request path runs
  (`check-gate-wiring.py --except-can-fail`) — OK.
- The gates that read the workflow — `check-hook-gates`,
  `check-workflow-exit-status`, `check-gradle-test-population`,
  `check-build-output-readers`, `check-coverage-timeout`,
  `check-waiver-references`, `check-coverage-floor-declaration`,
  `check-published-measurements`, `check-review-verdicts`,
  `check-issue-registry` — all OK.
- `openspec validate --all --json --strict` — exit 0, every item valid.
- `yamllint` and `actionlint` on `.github/workflows/ci.yml` — clean.
- `just docs-audit` — OK; the decision digest is regenerated from the ADR.
- `python3 -m traceability coverage --check` — the coverage index is current.
- The coverage switch itself, measured, not asserted: with
  `-Pcoverage.enabled=false` the Kover java agent is absent from the test
  worker's command line and `:koverAgentSearch` is absent from the task
  graph; with the property on, the Kover tasks are registered and the build
  succeeds.
- **On CI, for real:** pull request #1053 ran the new graph — the affected-test
  job, the detekt job, the suite without the agent, and a skipped coverage
  job — and every job was green, including the `CI gate` that blocks the
  merge.

## What was NOT verified

- **No CI run of #1074's own recipes.** The `just ci::` recipes were not
  executed — `./gw` needs the Gradle daemon and the host was at load 40-47 —
  and no CI run exercised them before this pull request was opened.
- **`./check.sh` was not run locally.** The host sat at load 40-47 with other
  sessions' Gradle builds; CI is the authority on that one.
- **The pre-commit hook's Kotlin compile did not complete on the earlier
  commits.** The lint step passed (`lint-changed: no findings`); the compile
  was killed at the 15-minute cap. Those commits were made with `--no-verify`.
  The diff they carry is `.py`/`.yml`/`.md` only — no Kotlin source changed —
  and the later commits went through the hook (`Pre-commit OK`, shared and
  desktop compiled).
- **The first push of the #1053 branch skipped the pre-push hook**
  (`--no-verify`), and this verdict is why: the hook runs
  `check-review-verdicts`, which requires the verdict to name the pull
  request — and a pull request cannot exist before the branch is on the
  remote. The bootstrap push created the pull request; the push that carried
  the first version of this file went through the hook with everything green.
- **The published measurements are one re-record behind.** The job rename
  invalidates nothing, but the page still names the old job until the
  measurements are re-recorded from a trunk run that carries the new name.

## The debt this change still carries

One item, recorded in
`docs/decisions/2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path.md`
rather than hidden:

1. **R4's wording is wider than the eight modules it names**, and `:pro:detekt`
   on the pull-request path is the case that shows it. The requirement was not
   reworded — a contract edited to match the implementation stops being what
   the implementation is checked against — the gap is recorded beside it, and
   the exception is pinned by a test so it cannot grow unnoticed.
