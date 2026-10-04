---
title: Six independent CI checks, run in parallel
date: 2026-10-05
status: deferred
tags: [ci, process, performance]
---

> **Withdrawn from the tree on 2026-10-05, hours after landing.** The measurement
> below is correct and was reproduced twice. The implementation is not in the tree: see
> [Why this was reverted](#why-this-was-reverted). The split is deferred, not
> abandoned — it is `ci-parallel-split-blocked-by-new-intra-job-coupling` in
> `deferred-backlog.md`.

## Context

`test-and-check` was one job with 25 strictly sequential steps, and the eight
Python/doc gates sat at the *end* of it, behind two Gradle invocations, even
though they need no Gradle at all. Measured on run `37209255590`, the job took
24.25m:

| Step | Time |
|---|---|
| Assemble Android debug | 10.27m |
| jvmTest (full suite) | 7.48m |
| detekt (shared + desktopApp) | 2.10m |
| detekt-rules test | 1.42m |
| desktopApp:test (full suite) | 1.72m |
| detekt-rules lint | 0.50m |
| setup (checkout, JDK, Gradle, PyYAML) | 0.56m |
| eight Python/doc gates combined | 0.13m |
| artifact uploads | 0.07m |

0.13m of work was waiting behind 23.6m of work it did not depend on. The
gateway was the sum of everything in the job.

## Idea

Ask what each step actually requires, and split on that rather than on what is
convenient to keep in one file.

## Decision

Six leaves, all independent by construction, plus an aggregator.

- `docs-gates` — every Python gate. Needs Python and PyYAML, not a JVM.
- `detekt-rules` — `:detekt-rules:test` and `:detekt-rules:detekt`.
- `jvm-tests` — `:shared:jvmTest` plus the shared report and bundle uploads.
- `desktop-tests` — `:desktopApp:test` plus the desktop uploads.
- `android-build` — `:androidApp:assembleDebug`.
- `detekt-main` — `:shared:detekt :desktopApp:detekt`.

`test-and-check` survives as an aggregator over all six, so the status people
watch is still a single name. Every existing reference to it — the
quality-tools skill, two ADRs, a KDoc comment in `ArchitectureTest` — stays
correct, and a PR still shows one verdict rather than six unrelated checks.

The aggregator asserts rather than assumes:

```yaml
if: always()
run: echo '${{ toJSON(needs) }}' | jq -e 'to_entries | all(.value.result == "success")'
```

Both halves are load-bearing. Without `if: always()` the job is skipped
whenever a leaf fails, and a skipped job reads as neutral. Without the
assertion, a failed leaf leaves its siblings `skipped` and a bare
`needs:` job would report success. Checked against success, failure and
cancelled inputs before committing to the shape.

The retry environment moved to `desktop-tests` alone, because
`DesktopAppHarness` is the only reader of `retry.maxAttempts` and
`retry.failOnPassedAfterRetry`. `setup-python` and PyYAML moved to
`docs-gates`, the only job that imports `yaml`.

## Rationale

Nothing in the original list depended on anything else beside it: the two
test tasks touch different modules, the Android build consumes no test
results, and the doc gates are pure Python. Split on that and the wall clock
becomes the slowest leaf instead of the sum.

Kept the Android step at full `assembleDebug` rather than trimming it, even
though it is the critical path. Its cost is genuine — a cold no-cache profile
puts `:shared:compileAndroidMain` at 28.8s, `:shared:kspAndroidMain` (Room
codegen) at 27.2s and dexing — the `DexingNoClasspathTransform` on `:shared`
plus `:androidApp:mergeExtDexDebug` — at 38.2s, about a quarter of the build.
Dropping to `compileDebugKotlin` would give that back, but the step would then
prove only that the code compiles, not that the APK packages, and the workflow
that installs and runs the APK runs on a schedule rather than on every PR. A
gate is not a thing to weaken for wall-clock time without an owner deciding
it.

## Consequences

- Wall clock went from 24.25m to 9.2m on the same commit, measured across two
  runs: 2.65x. That was the split as built; it is not the state of the tree
  today. The critical path under the split was `jvm-tests` at 9.1m, not the sum
  of every check in the workflow.
- Nine jobs now start where there was one, so a 4-core private repository pays
  for concurrent runners. It is cheap relative to the wall clock, and worth
  revisiting if the bill matters more than the wait.
- Per-job setup is no longer amortised: five Gradle jobs each pay their own
  checkout, JDK and Gradle bootstrap. That is included in the numbers above,
  not assumed away.
- A failure is now reported as one aggregate plus the specific failed leaf
  rather than as a position in a 25-step log.
- `assembleDebug` remains the critical path's tail. More runner cores, or the
  compile-only trade, are the two levers left, and both are an owner's call.

## Why this was reverted

This ADR was written against the `test-and-check` job as it stood before this
branch was rebased onto `main`. `main` had independently made that job *coupled*
in three places the six-leaf split assumed absent:

- `Stamp run start` writes `$RUN_STARTED`, and `Check executed test counts` and
  `Check coverage floors` both read it to assert freshness. A count floor is only
  meaningful against the results written by the same job in the same run; the
  stamp and the floor have to stay in one place.
- `Flake analysis vs previous run` reads `shared/build/test-results/jvmTest` of
  the *current* job and the `junit-results` artifact of the *previous* run. Two
  runs, two jobs, one comparison.
- `main` moved kover and its floors into a job that also runs the test tasks,
  because Kover's report has to be generated from a test run rather than from
  whatever a cache held.

Splitting on "these steps do not import each other" was true of the old job and
false of the new one. Applying the old shape to the new job would have produced a
green run whose count floors and flake analysis measured the wrong thing — a
faster pipeline that quietly checks less, which is the specific failure mode this
whole branch exists to remove.

So `main`'s monolith is what ships, and this decision is recorded as measured,
built, and withdrawn. Redoing it means designing around the coupling first:
publish `$RUN_STARTED` as a job output, or move the count and coverage floors
into the leaves that actually produced the results, or drop the per-run
comparison for a stored-baseline one. That is a separate piece of work with its
own ADR, and it is in `deferred-backlog.md`.

The parts of this change that carried no coupling did stay: the widened path
filters, the action version bumps, the `ubuntu-24.04` pin, the retry fix and the
restored `:detekt-rules` steps are all in `ci.yml`.

## Links

- `2026-10-05-ci-action-versions-and-runner-image-pin.md` — the other CI change
  on this branch: action versions and the pinned `ubuntu-24.04` image
- `scripts/fetch-ci-failures.sh` — reads a run's test artifacts, which is how
  the timings above and the failures behind them were obtained
