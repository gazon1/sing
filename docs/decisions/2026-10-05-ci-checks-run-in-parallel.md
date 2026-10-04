---
title: Six independent CI checks, run in parallel
date: 2026-10-05
status: accepted
tags: [ci, process, performance]
---

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
  runs: 2.65x. The critical path is now `jvm-tests` at 9.1m, not the sum of
  every check in the workflow.
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

## Links

- `2026-10-05-ci-action-versions-and-runner-image-pin.md` — the other CI change
  on this branch: action versions and the pinned `ubuntu-24.04` image
- `scripts/fetch-ci-failures.sh` — reads a run's test artifacts, which is how
  the timings above and the failures behind them were obtained
