---
title: "Gate the magnitudes, not the booleans: coverage, skips, flakes and untested gates"
status: accepted
date: 2026-10-04
tags: [testing, ci, kover, coverage, process, gates]
---

# Context

ADR `2026-10-04-test-execution-integrity` closed one hole: a test task that runs
fewer classes than it should. It stated the generalisation explicitly — *any gate
that reports a boolean where it could report a magnitude should report the
magnitude* — and then left three instances of exactly that shape untouched.

The work in this ADR is that generalisation, applied.

## 1. Coverage was measured, and the measurement described the build

`:shared` had a Kover report, a CI job and an artifact upload. It also had no
`verification` block anywhere, so nothing failed on coverage. Worse, the number
itself was an artefact.

ADR `2026-09-25-test-jvm-heap-default` measured that the IntelliJ coverage runtime
accumulates one `ClassData` per loaded class, and that the Koog classpath alone
contributes 3,000+ of them (42% of a heap dump). The ADR's fix was to exclude
`jvmTest` from instrumentation entirely:

```kotlin
instrumentation { disabledForTestTasks.add("jvmTest") }
```

That stopped the OOM and produced a report describing only the Android host source
set. Instruction coverage read **10.5%** — a number that moved when a Gradle
property moved, not when the code did.

Kover 0.9 supports filtering at instrumentation time, which the earlier diagnosis
did not consider. `includedClasses.add("com.singularity.todo.*")` instruments only
our own bytecode; Koog's 3,000 classes are never touched, so the accumulator stays
proportional to the code under test. `forkEvery = 1` bounds it further. The same
suite now measures **23.0%** instruction, 24.4% line, 18.3% branch coverage.

The lesson is not "turn instrumentation on". It is that **an excluded test task
does not reduce coverage, it stops the measurement**, and the resulting figure looks
exactly like a coverage number until you ask which task produced it.

## 2. The artifact had never been uploaded

The CI job uploaded `shared/build/reports/kover/xml-report.xml`. Kover 0.9 writes
`reports/kover/report.xml`. The step had no `if-no-files-found: error`, so the
upload succeeded carrying nothing, every run, since the job was added. A step that
succeeds without doing its job is the same defect as a gate that passes without
testing anything — and it survived because nobody ever opened the artifact.

## 3. A skipped test is invisible to a count floor

`check-test-runs.py` compares executed class and test counts against a floor. JUnit
counts a skipped testcase inside `tests=` exactly like a passing one, so a class
annotated `@Disabled` leaves the count untouched. `TaskOutgoingLinksTest` sat
disabled for a month behind a fully green task, with an ADR explaining why the
cause had been misdiagnosed (a reviewer chasing the OOM found an unbounded loop in
`toLinksJson` instead).

The skipped count is therefore a **ceiling of zero**, not a floor. Raising it to
make the gate pass would invert its meaning, and the baseline header says so.

## 4. The gates had no tests

`scripts/tests/test_find_unwired_surfaces.py` existed and nothing ran it — not
`check.sh`, not CI. A regression inside `check-test-runs.py` would disable the
count floor silently, which is the same failure one level up. The scripts now have
56 unit tests between them, covering the failure direction (a drop, a skipped test,
a missing report, a third-party-only report) as much as the passing one, and
`check.sh` runs them in 20 ms.

## 5. Flakiness cannot be seen from one run

Two flakes in this repository's recent history — `ProjectsFlowTest` reading a draft
state before the init collector seeded it, and `TaskDetailCoordinatorGraphTest`
waiting on a 10-second real-time budget under parallel load — were each observed
once, never reproduced, and filed as prose in the deferred backlog. A run that is
green is indistinguishable from a run that is green because the test is sound.

`check-flaky-tests.py` compares two runs' JUnit XML. A status flip is the signal:
failed-then-passed is a *flake witness* even though nothing about the current run
looked wrong. CI publishes the XML on every run (previously only on failure — i.e.
only the half you do not need) and compares against the previous successful run.

The step annotates the run rather than gating it, and that is deliberate: a test
that failed here already failed the Gradle step, so failing again would change no
outcome. What it adds is the other half of the signal.

## 6. Three CI gates were advisory

`Run detekt`, `Assemble Android debug`, `Build version catalog gate` and the whole
`mcp-server` job carried `continue-on-error: true`. All four are enforced locally
already; in CI they reported findings nobody was required to fix. The `mcp-server`
job is the sharp one: it holds the profile-bootstrap identity tests, so the P0
data-corruption fix shipped with the tests that cover it unable to fail a build.

The Maestro tag check stays non-blocking, with its reason intact: it is superseded
by `MaestroFlowTagsTest` in `:shared:jvmTest`, which is blocking and covers the
same tag registry.

# Idea

Treat every verification step as a measurement with a recorded floor, and treat the
gates themselves as code that needs tests.

# Decision

1. **Filter Kover instrumentation to `com.singularity.todo.*`** instead of excluding
   `jvmTest`, and gate coverage with `scripts/check-coverage.py` against
   `config/docs/coverage-baseline.txt` (instruction/branch/line, current values
   23.0 / 18.3 / 24.4). Coverage is computed over our own packages only: a total
   over the whole Kover report is dominated by uninstrumented third-party bytecode
   and drifts with dependency bumps.
2. **Upload the artifact that exists** (`report.xml`) and set
   `if-no-files-found: error` so this cannot recur silently.
3. **Add a skipped ceiling of 0** to `check-test-runs.py`, alongside the existing
   floors.
4. **Test the gate scripts** in `scripts/tests/`, run from `check.sh` step 8 and CI.
5. **Publish JUnit XML every run** and add a flake-analysis step comparing against
   the previous successful run, with acknowledged flakes recorded in
   `config/docs/flaky-baseline.txt` and a reason required per entry.
6. **Make detekt, assembleDebug, the version-catalog gate and the mcp-server job
   blocking.**

# Rationale

**Why a floor and not a target.** A coverage *target* fails on the day it is
written, because a number nobody can reach is not a gate anyone keeps. A floor does
one job — fail on a drop — and growth needs no decision. The same rule already
governs the test-count baseline, and it is why that baseline records the *smallest*
legitimate run.

**Why own packages only.** The alternative measures `com.intellij.*` and `ai.koog.*`
alongside our code, so a Koog upgrade would read as a coverage regression nobody
caused, while our own newly-dead code would hide inside a 10% denominator.

**Why the skipped ceiling is a ceiling.** A floor of 0 is satisfied by any run; a
ceiling of 0 is satisfied only by a run where nothing was skipped. A missing test
is the thing to detect, and it can only be detected by an upper bound.

**Why the instrumentation filter beats the exclusion.** Both stop the OOM. Only one
produces a number that means something. The earlier ADR's diagnosis was correct
about the *cause* (per-loaded-class accumulation) and reached for the only tool
available at the time; a later plugin version offers a better one.

**Why the flake step does not gate.** Failing here would be failing a build that
Gradle already failed. The value is in the annotation — a flake witness is proof,
collected while the evidence still exists.

# Consequences

- `:shared:koverXmlReport` now instruments `jvmTest`, so the task takes roughly
  three times as long. It stays out of the default `./check.sh` path
  (`--if-present`) and runs in its own CI job.
- `check-doc-sizes` in CI now regenerates `DIGEST.md` first. The file is generated
  and gitignored, so on a fresh checkout it does not exist and the budget check
  would silently skip the one document whose size is generated.
- The ADR `2026-09-25-test-jvm-heap-default` is superseded on the instrumentation
  point. Its re-enablement finding (the `toLinksJson` unbounded loop) stands.
- Flake analysis needs a previous successful run on the same branch. On the first
  run, or after artifact retention expires, it prints that it was skipped — by
  design, not as a silent pass.
- Acknowledging a flake in `config/docs/flaky-baseline.txt` is a claim that its
  nondeterminism is understood. An entry that has not failed in 30 days is stale by
  the file's own rule.

# Links

- ADR `2026-10-04-test-execution-integrity` — the count floor this extends
- ADR `2026-09-25-test-jvm-heap-default` — the OOM diagnosis and the exclusion it justified
- ADR `2026-10-04-configuration-cache-hardening` — the other half of the CI honesty work
- Spec `openspec/specs/test-execution-integrity/spec.md` — REQ-1 … REQ-5
- `scripts/check-coverage.py`, `scripts/check-flaky-tests.py`, `scripts/tests/`
- `config/docs/coverage-baseline.txt`, `config/docs/flaky-baseline.txt`
- `docs/decisions/deferred-backlog.md` — `ci-gates-are-all-continue-on-error`,
  `digest-line-limit-pressure`
