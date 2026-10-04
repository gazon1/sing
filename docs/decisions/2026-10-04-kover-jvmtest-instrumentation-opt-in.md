---
title: "Coverage: kover instrumentation for jvmTest stays opt-in, measured behind a project property"
date: 2026-10-04
status: accepted
tags: [testing, coverage, kover, build, gradle]
---

# Coverage: kover instrumentation for jvmTest stays opt-in, measured behind a project property

## Context

`feature/agenda` got a coverage ratchet in Phase 6 of the Agenda Views epic.
Building it ran into a wall that made the ratchet impossible as first imagined.

Kover instrumentation is disabled for the `:shared:jvmTest` task:

```kotlin
// shared/build.gradle.kts
disabledForTestTasks.add("jvmTest")
```

The comment attributes this to the IntelliJ coverage runtime accumulating
3000+ ClassData entries and OOMing in `TaskOutgoingLinksTest`.

Two consequences, in order of how they were hit:

1. `:shared:koverXmlReport` was **red** — it depends on
   `:shared:testAndroidHostTest`, which had two failing tests. Fixing those was
   a prerequisite for producing a report at all.
2. With the failures worked around (`-x :shared:testAndroidHostTest`) the report
   was produced, and **every counter under `feature/agenda` was 0**. The agenda
   tests live in `jvmTest`/`commonTest`, and nothing instrumented them. A
   ratchet pinned to that number would ratchet on nothing.

Meanwhile the OOM story itself had been undermined: ledger #11 of
`2026-09-27-write-layer-soundness.md` records that the `toLinksJson` OOM
attributed to Kover **reproduces with Kover disabled and in complete
isolation** — so those tests were switched off rather than the cause fixed, and
were later re-enabled and pass. The disable was therefore justified by a
diagnosis that turned out to be wrong.

## Idea

Two options, one per half of the problem.

**A.** Re-enable instrumentation for the whole suite and let kover do its job.
The counter-evidence to the OOM exists, but "the old diagnosis was wrong" is not
"the new configuration is safe", and `check.sh` must not become the place where
that gets discovered.

**B.** Keep the default off, make it switchable, and measure a filtered run that
loads far fewer classes than the full suite. Slower to set up, but it cannot
affect anyone who did not ask for it.

## Decision

**B**, with the flag kept narrow and the filter required.

```kotlin
// shared/build.gradle.kts
if (project.findProperty("kover.jvmTest")?.toString() != "true") {
    disabledForTestTasks.add("jvmTest")
}
```

Two non-obvious details make this work, both learned the hard way:

- **The test filter has to be a project property (`-Pcoverage.tests`), not
  `--tests`.** Gradle rejects `--tests` when a non-`Test` task is in the same
  invocation ("Unknown command-line option '--tests'"), so
  `jvmTest koverXmlReport --tests …` fails at configuration time. And without a
  filter, `koverXmlReport` pulls the entire suite in under instrumentation.
- **The ratchet wipes `shared/build/kover` first.** Kover merges binary reports
  incrementally, so a report produced after other runs carries coverage from
  tests outside the current filter. Measured on a stale state: 19.70%. Measured
  from clean with the same filter: 31.60%. Both "looked like" valid numbers and
  neither was reproducible. A baseline pinned to a number that depends on what
  happened to run last week is not a gate.

`just cr` wires it together; `scripts/coverage-ratchet.py` compares against
`config/coverage-ratchet.json` and exits 1 on a drop, with `--update` to adopt a
rise.

### Scope: the Compose subtrees are excluded

The baseline is **68.29%** (687/1006) over `domain`, `data` and the view
models. The whole package, including `presentation/{screen,nav,components}`, is
**31.60%** (687/2174) — 1168 lines at 0%.

Those lines are covered by the **desktopApp flow tests**, which kover cannot
see: a kover report covers the current project's classes, and the Compose UI is
exercised from `:desktopApp:test`. Including them would have produced a number
that mostly measures "this harness cannot run UI tests", and would drop on any
run that did not include desktop tests. The whole-package figure is kept in the
baseline file so the exclusion is visible rather than hidden.

## Rationale

The default stays off because the thing the flag exists to prove — that
instrumenting the full `jvmTest` is safe — is not what was measured. Flipping
the default would be claiming more than the evidence supports, and the cost of
being wrong lands on `check.sh` for everyone.

The filter is required rather than optional for the same reason: an unfiltered
run is the unproven configuration, and a ratchet that only works in the
configuration nobody has verified is not a ratchet.

Excluding the Compose subtrees is a statement about what the number *means*.
68.29% says "the logic under test is well covered". 31.60% would say "half the
package cannot be measured here", which is true and not actionable.

## Consequences

- `check.sh` and `just tcheck` are unaffected — they never pass the flag.
- `just cr` is the only supported way to measure. It is opt-in and slower than a
  plain test run (~90 s for the filtered agenda suite).
- The ratchet will not notice coverage changes in the Compose subtrees. The
  desktopApp flow tests are what covers those, and they are not line-measured.
  This is a known gap, not a solved problem.
- **The default-off status is now the thing to revisit.** What would settle it:
  one instrumented run of the full `:shared:jvmTest` with peak-memory
  measurement. If it completes, the ADR's premise is dead and the flag can
  become the default, which would also make the Compose exclusion negotiable
  via a combined report. Tracked in `deferred-backlog.md#kover-full-jvmtest-run-unmeasured`.

## Links

- `shared/build.gradle.kts` — the kover block and the `coverage.tests` filter
- `scripts/coverage-ratchet.py`, `config/coverage-ratchet.json`
- `.just/tests/mod.just` — `coverage-ratchet`
- `docs/decisions/2026-09-27-write-layer-soundness.md` — ledger #11
- `docs/decisions/2026-09-25-test-jvm-heap-default.md` — the original, corrected diagnosis
- `docs/plans/2026-10-04-mr6-retro-gate.md` — §1
