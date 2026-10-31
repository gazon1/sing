---
title: "The architecture gates have a task of their own, and the local loop has an affected lane"
date: 2026-10-31
status: accepted
tags: ["build", "performance", "tests"]
---

# The architecture gates have a task of their own, and the local loop has an affected lane

## Context

The local verification loop took 20-25 minutes, and three git worktrees running it
at once saturated the machine. The audit behind this decision measured where the
time went, from the JUnit XML of a real run (`shared/build/test-results/jvmTest`):

- the fast population is 521 classes and 653 s of summed per-class CPU;
- **54 classes — the ones that read a whole tree — account for 384 s of that,
  59% of the suite's CPU**;
- `TextFieldRewriteContractTest` alone was 103 s, 16% of the suite.

Two separate things turned out to be true, and they have separate causes.

**The Konsist scope is built once per JVM, and four JVMs built it four times.**
`CommonMainKonsistScope.scope` is a `by lazy` over
`Konsist.scopeFromExternalDirectory(commonMainRoot)` — a lazy is per JVM, and
`:shared:jvmTest` runs `maxParallelForks = 4`. Parsing 1,238 files of `commonMain`
cost about 25 s, so a quarter of the suite's CPU was spent parsing the same tree
four times over.

**A change to anything selected every tree-wide gate.** The impact map's
tree-wide rule put all 55 of them into the selection of a change to a Maestro
flow, a skill file, or a test file — 54 of which read `commonMain` and cannot be
broken by a YAML file.

## Idea

Two changes, one per cause.

1. The architecture gates get a task of their own, with one fork. The scope is
   then built once per run instead of once per fork.
2. The map learns which tree a gate reads, and a change selects the gates that
   read THAT tree.

## Decision

**1. `:shared:archTest` — the arch suite in one task, with one fork.**

The split is by PACKAGE and not by moving files: `:shared:jvmTest` excludes
`com.singularity.todo.arch.*` and `:shared:archTest` includes it, both over the
same compiled test classes. An `internal` declaration is the same object in both
tasks and the shared fixtures stay shared.

The arch gates' inputs are declared on `archTest` and not on `jvmTest`. A gate
that cannot be invalidated by the change it watches reports a verdict about a
file it never re-read, and that is worse than no gate at all — so an input moves
only when the gate that reads it moves with it.

**2. The map derives the tree a gate reads from the gate's own text.**

A gate asks the build for a tree by its root name (`commonMain.root`,
`maestro.root`, `skills.root`) or names the tree's path. Both are in the gate's
source, so the map reads them there. A gate that names NO tree reads a tree the
map does not know, and it is selected for every change — the safe answer.

The narrowing applies only to changes OUTSIDE the production code. A gate can
depend on production code through an IMPORT — without reading its source — so a
change in `commonMain` still selects every tree-wide gate.

**3. `check.sh --affected` — the local lane that runs what the change can break.**

The same two tools CI's "Affected tests" job uses: the map's selection for the
tests, `scripts/lint-changed.py` for the detekt. Every gate still runs; the full
loop is still what CI runs. The test-run floor does not apply to the lane: the
floor describes the full suite, and a subset is the lane working, not coverage
lost.

**4. The build slot pool counts memory, not just builds.**

Three worktrees are three daemons and three sets of test forks — about 12 GiB
each, 36 GiB on a 62 GiB host, before the emulator. A free slot is now only
taken when the machine can still hold the build that is about to start.

## Rationale

Measured on this tree, 2026-10-31:

| | before | after |
|---|---|---|
| arch suite CPU | 199 s | **63 s** |
| `TextFieldRewriteContractTest` | 103 s | **17.9 s** |
| map selection for a Maestro flow | 55 classes | **6** |
| map selection for a skill file | 55 classes | **7** |

The CPU number is the one that matters for the three-worktree case: the wall
clock of a single run is dominated by the compilation floor
(`ADR 2026-10-29-the-compose-plugin-breaks-incremental-compilation`), and the
compile does not care how many gates run. What three worktrees feel is the CPU
they take from each other.

The arch suite's own wall clock did not improve — one fork is one fork — and
that is the trade: 63 s of CPU in one fork instead of 199 s spread over four.

## Consequences

- `:shared:archTest` is a new Gradle task: CI names it, `check.sh` runs it, the
  test-run floor has a line for it, and the map routes the arch classes to it.
- The arch gates are a serial task. They are gates, not a benchmark: the
  parallelism that mattered was the parallelism of the OTHER 460 classes.
- The timings floor for Maestro (`config/docs/maestro-flow-timings.txt`) starts
  empty, and the shard-cost comparison says so in its output instead of
  pretending it compared seconds.

## Links

- Issues: #1647, #1648, #1649, #1650, #1651, #1652, #1653, #1654, #1655
- `docs/architecture/tests.md` — where the tests live and how they are tagged
- `docs/architecture/verification.md` — the order the gates run in
- `ADR 2026-10-22-the-test-cycle-has-a-map` — the map this ADR narrows
- `ADR 2026-10-29-the-compose-plugin-breaks-incremental-compilation` — the
  compilation floor that no test selection can go under
- `ADR 2026-10-22-gradle-memory-budget` — the heaps the slot pool now respects
