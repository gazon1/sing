---
title: "A Gradle task named by one surface is a task the other never runs"
date: 2026-10-07
status: accepted
tags: [ci, process, testing]
---

# A Gradle task named by one surface is a task the other never runs

## Context

Part A of `scripts/check-gate-wiring.py` asks whether a configured Gradle check
task is invoked *somewhere*. It answered `ok — 6 configured task(s), all named by a
gate`, and that answer was true and useless at the same time.

"signed by a gate" is satisfied by CI alone. Measured at `5146543f`, two of the six
configured detekt tasks were named by `ci.yml` and by nothing else:

| Task | `check.sh` | `ci.yml` |
|---|---|---|
| `:androidApp:detekt` | — | 1 |
| `:detekt-rules:detekt` | — | 1 |
| `:desktopApp:detekt` | 1 | 1 |
| `:mcp-server:detekt` | 1 | 1 |
| `:shared:detekt` | 1 | 1 |
| `:pro:detekt` | — | 2 (matrix leg) |

So `./check.sh` was green on two modules whose lint it had never executed.
`detekt-rules` is the worse of the two: it holds the project's own custom rules,
and a rule file that is not linted looks exactly like a rule file with no findings.

The local loop exists to be a rehearsal of CI. A loop that skips two modules is a
cheaper, different thing wearing the same name.

## Decision

1. **Part I requires parity by default.** Any Gradle task named by one surface and
   by neither declaration must be named by both. The alternative — declaring each
   of today's nine asymmetries — is what the table is for, and it is why the default
   is the strict one.

2. **Close the two holes rather than declare them.** `:androidApp:detekt` and
   `:detekt-rules:detekt` are now named in `check.sh`. Neither needed a new task
   step; both join the existing detekt step.

3. **Asymmetries live in `GRADLE_TASK_PARITY` with a stated reason.** Seven rows
   today, covering the `:pro` module (only in the graph under `-PwithPro=true`),
   `koverReport`-reachable tasks, and `:mcp-server:jar` (an artifact
   `McpServerEndToEndTest` resolves by path).

4. **`release.yml` is not a verification surface.** Its tasks are packaging steps —
   an unsigned APK and a Linux deb, built in order to publish them. Requiring
   `check.sh` to build a release artifact would put a shipping decision in the
   developer loop.

5. **A `covered` row is checked even when absent from both surfaces.** Without
   that, `covered` is a loophole: delete the task from `check.sh` and the union no
   longer contains it, so nothing compares it to the table and the declaration
   quietly stops meaning anything. `test_a_covered_task_must_still_be_named_locally`
   pins it.

## What this check does and does not detect

It detects **presence of a task string**, not invocation. The two differ:
`--require mcp-server:test` names a task as a floor to compare against and never
runs it. In practice the check reads only the Gradle CLI spelling `:module:task`,
which is why that floor declaration does not count — pinned by
`test_a_floor_declaration_is_not_an_invocation`.

The check errs toward passing, and that is a choice rather than an accident.
Proving invocation from shell and YAML text means resolving `run:` blocks, matrices
and line continuations, and a wrong answer there is worse than a known
approximation. What Part I guarantees is narrower and worth having: **a module
whose lint or tests nobody names on the local surface cannot go unnoticed**, and
every intentional asymmetry is a row with a reason.

## Consequences

- `check.sh` runs four modules' lint instead of two. It is slower, and it is the
  first time `detekt-rules`' own rules have been linted by the local loop.
- `:androidApp:test` appears in `ci.yml` but there is nothing behind it: `androidApp`
  has **no** unit-test source set, only four instrumentation classes under
  `androidTest/`. That task succeeds without executing a test. It is not fixed
  here — an empty task that reports success is worth its own decision — but it is
  recorded here because it was found while measuring this one.
- Nine of the surfaces' asymmetries are now written down. A tenth must earn a row.

## Links

- `docs/decisions/2026-10-07-branch-protection-is-unavailable.md` — why none of this
  is enforced yet
- `openspec/changes/ci-checks-parallel-split` — why the Gradle surface is split
- `scripts/check-gate-wiring.py` — Parts A, H and I