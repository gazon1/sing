---
change: ci-fast-only-pr-full-on-schedule
verdict: pass
date: 2026-10-23
pr: https://github.com/gazon1/sing/pull/978
---

## What was reviewed

The diff of `gazon1/dulse` against `origin/main` for the change
`ci-fast-only-pr-full-on-schedule`, and the four documents that state what the
code must do: the change's spec
(`openspec/changes/ci-fast-only-pr-full-on-schedule/specs/test-execution-integrity/spec.md`)
and the ADRs of 2026-10-22 (CI population, Gradle memory budget, the coverage floor
in the settings).

The review was run by a separate agent with read-only access, on the diff as it stood
after the rebase onto `main` and after the reconciliation with the gates that landed
on `main` in the meantime. It was asked for defects with a location, not for a
summary of the diff, and it was told to check the claims in the documents against the
code rather than to run the build.

## Findings

1. **should-fix — the timeout justification cited a file that is not in this branch.**
   The comment on the JVM tier's timeout named
   `config/docs/coverage-cost.properties` and `docs/testing/gate-costs.md`; neither
   carries the instrumented-run timings in this branch, and the comment's claim that
   the CI runner is slower than the development host is contradicted by the run
   durations the repository itself publishes.

2. **should-fix — a comment pointed at code the change had deleted.**
   The root build script explained why the Kover DSL block does not work on the root
   project and said "see below" for a `plugins.withId("…kover")` block that this
   change removes. A reader asked to touch the floors would have re-added the dead
   mechanism.

3. **should-fix — the full population's floor claimed a provenance it does not have.**
   The label said the floor was "measured 2026-10-15 on the full run", and the
   paragraph below it said that measurement was not a full-population measurement at
   all. The file contradicted itself, and the fast floor is stricter than the full
   floor in branch and line.

4. **should-fix — the run manifest is written by one module of three.**
   The gate that decides whether the slow classes owe the reader a report reads the
   manifest's `tags=`, and only the shared module writes one. The desktop and MCP
   source sets fall back to "did any slow class execute" — the signal the ADR itself
   calls insufficient, because a filter that selects no slow class looks the same as
   a deliberate fast-only run.

5. **note — the nested call in the slot pool inherited the lock descriptor.**
   The header of `scripts/gradle-slot.sh` promises that the child never inherits the
   descriptor, but the nested path (`SINGULARITY_GRADLE_SLOT_HELD=1`) started the
   child without closing it. Only the direct child sets that flag today, so the
   impact was latent.

6. **note — no dead work.** The slot script is reached through `gw`, the stop script
   through `just gradle::stop-all`, and the pre-commit hook uses `gw` with the slot
   pool switched off, as the memory ADR requires.

## Disposition

Findings 1, 2, 3 and 5 were fixed in the same branch before the push: the timeout
comment now cites the measurement where it is recorded and claims nothing about the
runner's speed; the root build script says the block is gone and where the floors
live now; the full floor's label states what the number is — a floor the fast
population's own measurement justifies, applied to both populations — instead of
claiming a measurement that did not happen; and the nested path closes the
descriptor.

Finding 3 leaves a decision open on purpose: raising the full floor to the numbers
the full population measures (55.89 / 40.45 / 57.23) is a policy choice about how
much drift a nightly may have, not a measurement, and it is recorded as an open
decision in the ADR of 2026-10-22. The label now says plainly that the floor is not
the strictest reading of the requirement, so the next reader sees the gap instead of
a claim that hides it.

Finding 4 is real for this branch and is closed by the population-manifest change
that follows in the same series: the manifest convention moves to the root build
script, so every module writes one, and the gate reads the population from the
manifest of the source set it is asking about instead of from the executed set.
