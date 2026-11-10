---
title: "The evidence of a run is indexed by one reporter, and the task outcome is an input"
date: 2026-11-09
status: accepted
tags: ["tooling", "testing", "ci"]
---

# The evidence of a run is indexed by one reporter, and the task outcome is an input

## Context

A red CI run produced a pile of artifacts and no reading of them. The JUnit
XML was uploaded (`junit-results`), the diagnostics bundles were uploaded
(`ci-evidence`), the Allure results were uploaded — three artifacts a person
had to hold in their head at once, with nothing saying which test owned which
bundle.

Issue #2664 asked for the existing Desktop `FailureBundle` to be attached to
the test report. #2666 asked for the same for the Maestro flows, #2665 for the
Android instrumented tier, and #2669 for the whole set of artifacts plus a
workflow summary. Four issues, three platforms — and the obvious implementation
would have been three reporters, each parsing JUnit XML its own way, each with
its own idea of what "not run" means.

The repository already had a precedent for getting this wrong:
`scripts/junit-annotations.py` and `scripts/ci/print-test-failures.sh` each
parsed the XML themselves and disagreed about what it said (ADR
`2026-11-06-one-junit-xml-reader.md`).

There was also one thing none of the artifacts could say: whether a test
*ran*. An empty report and a task that never started look identical in the
XML.

## Idea

- One reporter, three surfaces: it reads JUnit XML and writes one index per
  run.
- The outcome of the Gradle task is **passed in**, not inferred.
- A failing test's bundle is attached to it by a rule that cannot race.

## Decision

**`scripts/test-evidence.py` is the reporter.** It reads JUnit XML
(`--glob`), knows which tasks the run promised (`--expect`), and writes
`index.md` and `manifest.json` into `--out-dir`. With `--summary` it also
appends the index to `$GITHUB_STEP_SUMMARY`. It always exits 0: it is a
reporter, not a gate — the gates that judge the run are separate and already
exist.

**Two outcome vocabularies, deliberately separated.**

- *Task outcome*: `passed`, `failed`, `skipped`, `not_run`, `cancelled`,
  `timed_out`, `unknown`. It describes the Gradle task as a whole and it is
  an **input** (`--task-outcome`), never an inference. JUnit XML cannot tell
  "the task never started" from "the task started and died before the first
  test" — both are an absent or empty report — so a reporter that guessed
  would be lying to whoever reads the index.
- *Testcase outcome*: `passed`, `failed`, `error`, `skipped`, `flaky`,
  `unknown`. It describes one test and it is read from the report.

`not_run`, `cancelled` and `timed_out` are grouped as "the run did not
finish" — they are the states in which the absence of a report says nothing
about the test's own outcome.

**`timed_out` is an input, and CI cannot supply it.** GitHub Actions does
not distinguish a step that hit its deadline from a step that failed on its
own account — both are `conclusion: failure`. The CI, Android and E2E
workflows therefore pass `failed`, and a caller that knows better (a wrapper
with its own deadline) passes `timed_out`. Inferring it from the report would
be a guess. The reporter supports the outcome; the workflows do not yet
detect it, and a `failed` in a manifest must not be read as "timed out".

**Incomplete evidence is called out.** A promised task with no report is
`not_run`; a report that was read but holds no testcase is empty. Both are
marked in the index, and under `GITHUB_ACTIONS` both raise
`::warning::the evidence of this run is incomplete: …`, because a run that
silently collected nothing reads exactly like a run where everything passed.

**Existence and readability are two questions.** A report that exists and
cannot be parsed is `unreadable`, not `missing`: the task wrote the file, and
what failed is the reader. Counting it as missing would say twice that the
task wrote nothing, and the second statement would be false. `missing_tasks`
is built from the reports that were FOUND, readable or not.

**The task identity is the profile's vocabulary.** A report's module is
written with colons (`tools:unwritten-properties`), because that is how the
profile registry names a task (`module:task`). A report whose module was
spelled with slashes would answer to a name no profile declares, and the
reporter would call it missing while holding it. `module_path()` turns the
identity back into a file path.

**A failing test's bundle is attached by name.** The bundle path is derived
from the failing test's class name (for Maestro: from the flow's stem), and
the class name is the directory name under `build/diagnostics/`. This is
deterministic and cannot cross-link two concurrently running tests.

**The latest diagnostics attempt wins, and the attempt number is a number.**
`attempt-10` is later than `attempt-2`; a string comparison says the opposite
and would link a failed test to a bundle nine attempts older. A directory
that is not a numbered attempt is ignored rather than guessed at.

**The paths in the index are repo-relative**, matching the layout inside the
uploaded artifact, so the path to a failing test's bundle resolves inside
`ci-evidence-<run_id>-<run_attempt>`.

**Explicit `--expect` wins over the profile's tasks**, and the two are never
merged. A task the caller did not promise must not be reported as missing.

**The profile says where the reports are.** Without `--glob` the reporter
reads the profile's `reports` globs, because the three tiers write their
reports in three different places and a reporter asked for `device` that
looked in the JVM place would report a run with no reports in it. The
workflows pass `--profile` and nothing else.

**The environment is written by the runners, not by the reporter.**
`scripts/run-android-ui-tests.py` writes `build/android-ui-results/environment.json`
and the Maestro runner writes `build/maestro-results/environment.json`. The
reporter merges it via `--metadata-file`. The field names are a **schema**,
and one name means one thing: `apk_filename` is the name of the file,
`app_version` is the `versionName`, and `apk_commit_sha` is the git SHA the
APK was built from — not a sha256, and not a file name. The two producers
are checked against the schema by `scripts/tests/test_environment_metadata.py`.

**CI wires the reporter into three workflows**: `ci.yml` (JVM tier),
`android-device-tests.yml` (instrumented tier) and `e2e.yml` (Maestro shard).
Each uploads its own index under a unique name and appends it to the job
summary.

**Evidence is collected and uploaded on `always()`.** A cancelled job is
still a run, and its account of what it did is worth more than no account.
The condition is `always() && steps.checkout.outcome == 'success'`: after a
checkout that did not happen there is no tree and nothing to read. An upload
that runs under `always()` therefore does not fail on an absent file
(`if-no-files-found: warn`): the report it looks for may never have been
written, and a missing report is recorded in the evidence — a cancelled job
must not be reported as a failure of the uploader.

**The manifest explains itself.** It carries `expected_tasks`,
`deferred_tasks` and `conditional_tasks`, so an artifact that leaves the
repository still says why a task of the profile is absent from the reports.

**Artifact names carry the run attempt.** `github.run_id` does not change
when a workflow is re-run, so two attempts of one run would write into the
same artifact and the first attempt — usually the failed one — would become
indistinguishable from the second. The names carry
`<run_id>-<run_attempt>`.

## Rationale

**One reporter, three surfaces.** The three issues describe the same problem
in three places, and three implementations would drift the way the annotation
emitter and the console reporter drifted. One reporter is also the smaller
change: the platform-specific parts are metadata files, not code paths.

**The task outcome is an input because XML cannot carry it.** This is the
difference between "the task was cancelled" and "the task failed" — the first
is the run's business, the second is the task's, and only the workflow knows
which one happened. The workflow passes it in one flag.

**Bundles by class name, not by timestamp.** The alternative — matching a
bundle to a test by time — is a race whenever two tests fail within the same
second, which is exactly when a run is in trouble. The other alternative,
`forkEvery=1`, was already measured and rejected: ADR
`2026-10-04-desktop-test-failure-diagnostics.md` records +93 s for 22 tests
for a guarantee that the class name already gives.

**The reporter never fails the build.** A reporter that fails the build makes
the artifact it produces conditional on the build succeeding — exactly
backwards. Incompleteness is therefore a warning and an annotation, not an
exit code.

**The evidence index sits next to the reports, not in place of them.** The XML
stays the evidence; the index is the reading of it, and the manifest is what a
machine consumes.

**One declaration of what a profile owes.** The workflows used to carry a
second, hand-maintained list of expected tasks, and the profile registry
carried another. They disagreed: the registry declared every task of a
profile, while the workflow expected fewer, because some reports arrive after
the index is built and some tasks select no test at all. The profile now
says which of the three it is, and the workflows pass `--profile` alone. A
second list is a second opinion about the same run, and it drifts.

**Cancellation is a state, not an excuse.** `!cancelled()` says "if the job
was cancelled, produce nothing" — which throws away the account of a run that
was mostly finished. The evidence of a cancelled run is exactly the evidence
that tells a reader how far it got.

## Consequences

- A failed test's row in the index names the bundle with the screenshot, the
  screen tree and the log — one click instead of three artifacts.
- Each run's summary says what ran, what was skipped, and whether the
  evidence is complete.
- The artifact names carry the run id and the run attempt (and the shard, in
  E2E), so two attempts of one run and two shards of one suite are four
  artifacts, not two written twice.
- The reporter is a script with tests in `scripts/tests/test_test_evidence.py`;
  it is in the tool catalogue.
- The reporter knows nothing about Android or Maestro beyond their report
  layout — platform facts arrive as metadata files.
- The environment metadata has a schema, and both producers are tested
  against it: `scripts/tests/test_environment_metadata.py`.
- **Rollback**: delete the script, its tests and the three workflow steps. The
  XML and the bundles keep being uploaded exactly as before.

## Links

- `scripts/test-evidence.py` — the reporter
- `scripts/tests/test_test_evidence.py` — its tests
- `scripts/tests/test_environment_metadata.py` — the environment metadata schema
- `scripts/run-android-ui-tests.py` — writes the Android environment
- `scripts/src/emulator/maestro/_cli.py` — writes the Maestro environment
- `.github/workflows/ci.yml`, `.github/workflows/android-device-tests.yml`,
  `.github/workflows/e2e.yml` — the three wirings
- `docs/decisions/2026-11-09-the-test-profiles-are-declared-in-one-file.md` —
  the profile registry the reporter reads
- `docs/architecture/tests.md` — «Доказательства прогона»
