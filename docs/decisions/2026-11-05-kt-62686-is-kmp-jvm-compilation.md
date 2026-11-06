---
title: "KT-62686 is KMP's JVM compilation, not the execution strategy"
date: 2026-11-05
status: accepted
tags: ["build", "performance", "kotlin", "incremental-compilation"]
---

# KT-62686 is KMP's JVM compilation, not the execution strategy

## Context

After Room KSP was isolated into `:core-database` (`build-investigation/stage5-benchmark.md`), a
one-file edit in `:shared` still recompiled the whole module with `REBUILD_REASON: Incremental
compilation might be incorrect (KT-62686)`, with KSP `UP-TO-DATE` and `Classpath changes: NoChanges`.

Three documents disagreed about the cause:

- `build-investigation/baseline.md` recorded "compiler strategy (`daemon` vs `in-process`) … both
  `KT-62686` — `in-process` is not the trigger";
- `gradle.properties:77-83` and `2026-11-05-the-koin-plugin-forced-the-aggregator-compile-to-rerun`
  `:176-213` recorded the opposite: "`in-process` makes an edited KMP module non-incremental …
  `:shared` under `daemon` loses the message and drops to 47–60 s";
- `2026-10-22-gradle-memory-budget` (2026-11-05 amendment) repeated the second version.

Issue #2600 asked for the minimal configuration that reproduces the fallback. `KT-82893` is the
known KMP IC report (`UNSAFE_INCREMENTAL_CHANGE_KT_62686`), but the Koin ADR argued `:shared` was
not that case because its `daemon` probe was said to be incremental.

## Idea

Measure instead of reconciling prose. Two independent cycles on `:shared` — its own settle state per
strategy, the same body-only patch, one variable — then the same protocol on `:core-domain`,
`:core-database`, and a committed minimal pair (a KMP module and a plain JVM module with the same
source and the same edit, differing only in the applied plugin).

`--no-build-cache` was not enough for the settle step: it leaves Gradle's up-to-date check in place,
so a run can return `UP-TO-DATE` without invoking the compiler. Each cycle starts with
`--rerun-tasks`, then requires a genuine `UP-TO-DATE` settle run, then applies the patch.

## Decision

**Kotlin's incremental compiler gives up on a KMP module's JVM compilation under both execution
strategies, and no plugin of this repository is required.** Measured 2026-11-05 on `a4fbed7a0`,
Kotlin 2.4.10, Gradle 9.8.2:

| Configuration | Plugin set | Strategy | Verdict |
|---|---|---|---|
| `:shared` (1,237 `.kt`) | KMP + Compose + Koin + KSP | `in-process` | non-incremental, `KT-62686`, 62.0 s |
| `:shared` (same patch) | same | `daemon` | non-incremental, `KT-62686`, 53.4 s |
| `:core-domain` (44 `.kt`) | KMP only | `in-process` | non-incremental, `KT-62686`, 7.3 s |
| `:core-database` (83 `.kt`) | KMP + Room/KSP | `in-process` | non-incremental, `KT-62686`, 21.6 s |
| `probe` (1 `.kt`) | KMP only | `in-process` | non-incremental, `KT-62686` |
| `probe` (same edit) | KMP only | `daemon` | non-incremental, `KT-62686` |
| `probeJvm` (same source) | `kotlin("jvm")` | `in-process` | **incremental** |
| `probeJvm` (same edit) | `kotlin("jvm")` | `daemon` | **incremental** |

All runs: `Classpath changes: NoChanges`, one modified source, task executed. Evidence and commands:
`build-investigation/kt-62686/` (`reproducibility.md`, `module-probes.md`, `report-extracts/`,
`run-summary.txt`), and
the reproducer at `build-investigation/kt-62686/probe/` (its own Gradle build, not in
`settings.gradle.kts`).

## Rationale

The strategy claim is refuted by its own subject: `:shared` under `daemon` reports the same
`Non-incremental compilation will be performed: Incremental compilation might be incorrect
(KT-62686)` and recompiles the same 1,237 sources. The 47–60 s the earlier measurement recorded
under `daemon` is a faster non-incremental compile, not an incremental one — the two numbers differ
in speed, not in kind.

The minimal pair takes every plugin out of the question: a one-file KMP module with no Compose, no
Koin, no KSP and no android target falls back, and the identical file compiled by `kotlin("jvm")`
does not. That isolates the difference to the KMP JVM compilation configuration against the plain JVM
compilation configuration — the plugin and the compilation model it creates change together, so it
does not name a mechanism inside the Gradle plugin. Module size is not required for the fallback
either: the one-file module falls back, so `:shared`'s ~54–64 s per edit is the cost of recompiling
1,237 files, not a second cause.

The earlier "bare KMP is incremental under `daemon`" probe did not reproduce. Why is not established;
the most likely difference is that it measured a build whose compiler never ran (no forced compile),
which is the same trap `--no-build-cache` alone sets. That is a hypothesis, not a finding.

## Insights

**The measurement trap.** `--no-build-cache` does not force a compile: it leaves Gradle's up-to-date
check in place. A "settle" run can therefore return `UP-TO-DATE` without ever invoking the compiler,
and a later run then looks incremental because nothing was compiled under test. `--rerun-tasks` on
the first run is what makes the pair measurable; the run after it must genuinely report `UP-TO-DATE`.

**The two documents were not equally wrong.** `baseline.md` recorded "both `KT-62686`" and was
right. The ADR recorded `daemon` as incremental and was wrong. The difference between them is
protocol, not tree: this measurement gave each strategy its own forced compile and its own settle
state. A settle under one strategy followed by a measured run under the other cannot separate the
edit, the strategy switch, and a compiler that may be cold or warm.

**Speed is not kind.** 53.4 s under `daemon` against 62.0 s under `in-process` on the same
non-incremental compile. Reading a faster fallback as an incremental compile is how the wrong
conclusion survived.

**No plugin is a necessary condition.** `:core-domain` (44 files) reproduces it with no Compose, no
Koin, no KSP and no android target; `:core-database` (83 files) with Room/KSP and no Compose;
`:shared` with all of them; the one-file probe with none. Each measurement excludes a *necessary
condition*. None of them excludes an interaction that changes the fallback's characteristics inside
`:shared` — that is not measured, and the documents should not read as if it were.

**The cost is size, the trigger is not.** 0.6 s (1 file), 7.3 s (44), 21.6 s (83), 62.0 s (1,237).
The numbers track the source count, which is what a non-incremental compile costs; nothing in them
suggests size causes the bailout.

**`.kotlin_module` is not the discriminator.** `Process SignatureChanged(fqName = .kotlin_module)`
is the last IC decision line before the bailout in the KMP runs, and it also appears in the
incremental `kotlin("jvm")` runs. The verdict is `Non-incremental compilation will be performed`
versus `Incremental compilation completed`, not that line.

**KSP executes on any edit in its own module.** The `:core-database` measured run executed
`kspKotlinJvm` because the edited file is a KSP input. That is why the Stage 5 split removed the KSP
cost from UI/ViewModel/repository edits, and why it says nothing about this fallback.

**Not covered by these tests.** Only body edits (no ABI-changing edit in the minimal pair); only
`jvm()` (the android target is untested); only Kotlin 2.4.10 (no older-version comparison, which
would distinguish a regression from longstanding KMP behaviour).

**What follows for the build.** No property flip (`daemon` is not a fix), no further split for this
symptom (a one-file module still falls back; a split only shortens the recompile), and no version pin
as an action until upstream answers. `build-investigation/kt-62686/probe/` is the artifact to send.

## Consequences

- `build-investigation/baseline.md` was right and keeps its row. `gradle.properties:77-83`, the
  "Second cause" section of `2026-11-05-the-koin-plugin-forced-the-aggregator-compile-to-rerun`, and
  the 2026-11-05 amendment in `2026-10-22-gradle-memory-budget` are corrected to point here.
- **The memory-budget decision does not have to be re-opened.** `daemon` is not a fix for the
  fallback; it buys a faster non-incremental compile at the price of a Kotlin daemon JVM. Nothing in
  this measurement argues for or against that trade — it removes it as a candidate.
- **A further module split is not justified by this symptom.** Splitting reduces the number of files
  a non-incremental compile must process, but a one-file KMP module still falls back, so a split
  would not make any module incremental.
- The known upstream report is `KT-82893` (`UNSAFE_INCREMENTAL_CHANGE_KT_62686`, open/backlog).
  `build-investigation/kt-62686/probe/` is a one-file reproducer that can be attached to it; no
  upstream report was filed in this task because the finding matches the known condition.
- Not established: the compiler-internal reason, whether the android target behaves the same, and
  whether this is specific to Kotlin 2.4.10. Changing the compiler version was out of scope.

## Links

- `build-investigation/kt-62686/README.md` — the table, the protocol, and what is measured vs inferred
- `build-investigation/kt-62686/probe/` — the minimal reproducer
- `build-investigation/stage5-benchmark.md` — the measurement that removed Room KSP from the question
- `build-investigation/baseline.md` — the Stage 0 probes, including the Koin and strategy A/B
- `ADR 2026-11-05-the-koin-plugin-forced-the-aggregator-compile-to-rerun` — the Koin task-state
  cause (stands) and the strategy claim (corrected here)
- `ADR 2026-10-22-gradle-memory-budget` — `in-process`, and why it was chosen
- `KT-82893` — the upstream KMP IC report
