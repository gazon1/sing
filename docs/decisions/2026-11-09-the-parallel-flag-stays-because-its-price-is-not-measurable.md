---
title: "The parallel flag stays, because its price is not measurable"
date: 2026-11-09
status: accepted
tags: ["ci", "tests"]
---

# The parallel flag stays, because its price is not measurable

## Context

A review of the CI pipeline found four invocations in `.github/workflows/ci.yml`
that pass `--no-parallel`: the detekt step, the JVM test step, the ADR module
test step, and the coverage step. The finding said the flag costs wall-clock
time and should go.

The flag is not decoration. It was added because a batch of `:shared:jvmTest`
dies with `EOFException` in a "Gradle Test Executor" when several test tasks
share one checkout under parallel execution — #471. `CONTRIBUTING.md` documents
the failure mode, and the pre-push hook makes the same call for the same
reason.

The shape of the CI invocation is exactly the documented one: the test step
names nine test tasks (`:shared:jvmTest`, `:core-domain:jvmTest`,
`:shared:archTest`, `:shared:testAndroidHostTest`, `:desktopApp:test`,
`:mcp-server:test`, `:detekt-rules:test`, `:tools:unwritten-properties:test`,
plus `:mcp-server:jar`) in a single Gradle call, and `koverReport` pulls in
eight test tasks on its own.

## Idea

Before removing a flag, measure what it costs.

## Decision

The flag stays in all four steps. The comment that explains it is corrected,
and the measurement is recorded here.

The old comment said the collision was "two parallel Gradle processes". That is
the wrong shape: CI runs one Gradle process, and the collision comes from
several test tasks inside it. The comment now names the form that
`CONTRIBUTING.md` documents.

## Rationale

**The price of the flag is not measurable.** Two runs on the same worktree, same
tasks, `--rerun` on every task, 2026-11-09:

| Task set | with `--no-parallel` | without | difference |
|---|---|---|---|
| 11 detekt tasks | 57.34 s | 55.68 s | 1.7 s (noise) |
| JVM suite (513 + 1 + 31 classes) | 41.77 s | 47.93 s | flag was 6.2 s *faster* |

Both differences sit inside the machine's noise (load average 10–17 during the
runs). The flag does not buy a measurable amount of time, and removing it does
not buy one either.

**The thing it protects against is present in CI.** The documented failure is
several test tasks sharing one checkout under parallel execution. The CI test
step is one invocation with nine test tasks; the coverage step's `koverReport`
depends on eight. The pre-push hook sees the same shape.

**The detekt step has a second reason.** That step runs no test task, so
issue 471 cannot happen there. The flag keeps the daemon's live set at the measured
~4.3 GB — the measurement in `config/docs/gradle-memory-budget.properties` was
taken with `--no-parallel`, "as CI runs it". The 6144 MB daemon heap and the
1536 MB test forks are the numbers the budget file records, and removing the
flag from the step that compiles the same task set would invalidate them.

## Consequences

- The four `--no-parallel` flags stay. Removing them buys no measured time and
  gives up a documented protection.
- The comment in `ci.yml` names the collision correctly: several test tasks in
  one invocation, not two Gradle processes.
- A future change that wants the parallelism must first show a measured gain
  and re-measure the memory budget under the new shape.
- The review finding is closed by evidence, not by a change: the redundancy it
  described is not present.

## Links

- `.github/workflows/ci.yml` — the four invocations and the corrected comment
- `CONTRIBUTING.md` — the documented `EOFException` failure mode
- `config/docs/gradle-memory-budget.properties` — the memory measurement taken
  with `--no-parallel`
- `docs/decisions/2026-10-14-measurement-determinism-helpers-and-gates.md` —
  the decision that added the flag to the pre-push hook
- `docs/decisions/2026-10-22-gradle-memory-budget.md` — the budget measurement
