---
title: The local loop measures nothing it does not use
status: accepted
date: 2026-10-28
tags: [ci, testing, coverage, performance]
---

# Context

`check.sh` is the local rehearsal of CI: eleven steps, every one timed, and
the timings published in `docs/testing/gate-costs.md`. It is the loop a
developer runs before pushing.

Two of its steps cost more than they are worth, and both costs were measured
rather than assumed.

**The Kover agent is attached to the local test run.** The switch that
controls it (`-Pcoverage.enabled`) defaults to `true`, so `./gw :shared:jvmTest`
inside `check.sh` runs every test class with the agent on its command line —
the same configuration the pull-request path explicitly opts out of. Measured on
this tree on 2026-10-28, the same task with the same tests:

| run | wall time |
|---|---|
| `:shared:jvmTest` (agent attached, the default) | 1241 s |
| `:shared:jvmTest` (`-Pcoverage.enabled=false`) | 771 s |

The agent is a multiplier, not a decoration: the local loop paid 470 s more
than the same work costs without it. It was not measuring anything either.
Step 9 reads the coverage floor only `if [ -f build/reports/kover/report.xml ]`
— and the local loop never produces that file, because producing it is what
`koverReport` does, and `check.sh` does not run it. So the number the agent
was bought for was, on a clean checkout, never consulted.

**The impact map already knows what this change touched.** `scripts/test-impact-map.py`
computes, from the diff against `origin/main`, which test classes the change
can break — and `just ci::quick` already runs exactly those. The local loop
does not use it: `check.sh` runs every test class, so a change to one file in
one feature pays for the whole suite.

# Decision

**The local loop runs the tests uninstrumented.**

- `check.sh` passes `-Pcoverage.enabled=false` to its test steps
  (`:detekt-rules:test` excepted — that module measures its own coverage with
  its own Kover plugin). The coverage floor is no longer measured by the local
  loop, and the loop says so rather than pretending to measure it:
  `:koverVerify` at the root is only meaningful against a measurement, and the
  local loop takes none. The floor stays enforced where the measurement is
  taken — the coverage steps of CI's `tests` job, and the `just ci::coverage`
  recipe, which already exists and already does exactly that.
- The detekt-rules branch floor is unaffected and still runs locally: it is
  measured by that module's own `kover` plugin, not by the root agent, so
  `-Pcoverage.enabled=false` does not silence it. Measured on 2026-10-28 with
  the switch off, it still reports 66.4588% against a 60% floor.

The forkEvery change is already made and is not repeated here: `forkEvery` is
`0` in `shared/build.gradle.kts`, `ForkEveryIsolationTest` asserts it, and
`KoinIsolationExtension` cleans up per class. The Koin premise in the old
`forkEvery = 1` comment no longer describes the tree.

# Rationale

**A number nobody reads is a cost with no return.** The agent multiplied every
test by a factor that bought a measurement the loop then declined to consult.
Turning it off does not weaken a check: it removes the ability to produce a
verdict that was never produced.

**The floor and the measurement belong together.** The rule the repository
already follows — "the run that computes coverage is the run that judges it"
(`openspec/specs/test-execution-integrity/spec.md`) — is what this change
makes true locally as well.

# Consequences

- The local loop's test steps drop to their uninstrumented cost —
  `:shared:jvmTest` from about 1241 s to about 771 s on this tree — and the
  loop stops attaching an agent it does not read.
- A developer who wants the coverage floor runs `just ci::coverage`, which is
  the recipe that mirrors the coverage steps of CI's `tests` job.
- The timings in `docs/testing/gate-costs.md` are regenerated from the next
  recorded cycle, because a published cost that no longer describes the loop
  is a wrong number. Until then the page still prices the step this change
  removed: the page is a render of a record, and editing it by hand would
  publish a number nobody measured.

# Links

- ADR: `2026-10-23-ci-fast-signal-and-coverage-off-the-pr-path` — the same
  split, applied to the pull-request path.
- ADR: `2026-09-25-test-parallelization` — the fork and parallelism decision.
- `scripts/test-impact-map.py`, `.just/ci/mod.just`, `check.sh`
