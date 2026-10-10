---
title: A fork that died left no evidence, so the fix is to make the next one legible
date: 2026-10-07
status: accepted
tags: [testing, build, observability, sync]
---

# A fork that died left no evidence, so the fix is to make the next one legible

## Context

`:shared:jvmTest` has failed twice during this work with a build failure and **no failing
test** — Gradle reported `Gradle Test Run FAILED` without ever printing an
`N tests completed` line. Both times a rerun passed, and both times nothing was left
behind to explain it.

The task forks aggressively by design, and the reasons are recorded in
`shared/build.gradle.kts`:

- `forkEvery = 1` — a fresh JVM per test class, because Koin's global state and the
  `FileSystemContract` temp paths rely on class-level process isolation. Asserted by
  `ForkEveryIsolationTest`, not left as a comment.
- `maxParallelForks = min(cores / 2, 4)` — four forks here, each with `maxHeapSize = "3g"`.
- `junit.jupiter.execution.parallel.mode.default = concurrent` — methods run concurrently
  inside a fork too, so 16 cores' worth of dynamic ForkJoinPool workers per fork.
- `-javaagent:kotlinx-coroutines-debug` on every fork.

There is a safety net for hangs: `junit.jupiter.timeout.default = 300000` gives every
method five minutes, and `FailureContextExtension` writes coroutine dumps. A *dead
process* is outside it — no timeout fires, no extension runs, no dump is written.

The part that actually blocks a diagnosis is not the crash. It is that everything that
might have recorded it lives under `shared/build/`, and the next successful run replaces
it: `reports/tests/`, `test-results/`, and the console scrollback all go away. So the
question "why did that fork die" is not a hard question — it is an unanswerable one.

## Idea

Two separable halves:

1. **Make the crash legible.** Point `-XX:ErrorFile` at `build/test-heap-dumps/`, the one
   directory under `build/` that nothing clears. A JVM-level crash then leaves a file that
   survives subsequent runs.
2. **Record that this is not a fix.** The remaining candidate — the kernel killing the
   process — writes no `hs_err` at all, and the work needed to tell the two apart is not
   something a JVM flag can do.

## Decision

Add the `ErrorFile` flag, and record the measurement that the existing heap-dump
configuration already produced.

## Rationale

**What the empty dump directory proves.** `build/test-heap-dumps/` exists and is empty
after both failures. `-XX:+HeapDumpOnOutOfMemoryError` is enabled for every fork, so a
JVM that ran out of heap would have written a dump there. It did not. **The fork did not
die of a JVM heap exhaustion** — which also means the 3 GB ceiling is not the thing to
raise, and any future proposal to raise it should be told so.

**What the machine rules out, and how weakly.** 16 cores, 62 GB total, 27 GB available
when this was measured. Four forks reserving 12 GB of heap fits comfortably. So host
memory pressure is not the obvious answer *at the moment of measurement* — but the
measurement was taken after the fact, on a box that was not necessarily in the same state,
so this rules the hypothesis out weakly rather than out.

**Why the flag is scoped to crashes only.** `ErrorFile` covers SIGSEGV, SIGABRT and JVM
internal errors. It cannot cover SIGKILL, because a process killed by the OOM killer never
gets to write anything. That is precisely the useful property: **an absent `hs_err` file is
itself evidence**, and it is what will distinguish "the JVM crashed" from "something
outside the JVM killed it" next time.

**Why the conservative direction in the comment stripper and here.** The same principle as
the rest of this session's work: prefer a rule that cannot fail to a rule that fails
spuriously. A flag that writes a file costs nothing when there is no crash. A flag that
changed the fork's exit behaviour could turn a survivable condition into a build failure,
and that is a different decision with its own trade-off, not one to smuggle in here.

## Consequences

- The next JVM-level crash of a fork is diagnosable after the fact, from a file that later
  runs do not delete.
- The next kernel-level kill still is not. It leaves only Gradle's exit code, which is in
  the console and not retained. Closing that would mean recording OS memory pressure per
  fork, which is not justified by one occurrence in the project's history.
- Anyone raising `maxHeapSize` should read this first: heap exhaustion has already been
  ruled out as the cause of the failures that motivated the change.
- The build file now names this ADR, so the flag is not mistaken for a fix someone should
  later "clean up" as redundant.

## What was not established

The root cause of the two failures is **not known**, and this ADR does not pretend
otherwise. The remaining hypotheses, in the order they are worth testing:

1. The kernel OOM killer taking a 3 GB fork while the rest of the box is busy. Testable by
   reproducing under load and checking `dmesg` for a `Killed process` line.
2. The per-fork coroutines-debug agent, instantiated 321 times, interacting badly with
   concurrent execution inside each fork. Testable by running with the agent removed and
   seeing whether the failure survives — a real experiment, not an inspection.
3. Gradle worker management under `forkEvery = 1` plus `maxParallelForks > 1`. Testable by
   setting `maxParallelForks = 1`, at the cost of most of the wall-clock saving.

Until one of those is measured, "flaky fork" is the honest description, and the change
here buys the ability to find out next time — not the answer.

## Links

- `shared/build.gradle.kts` — the `jvmTest` fork configuration, including the new flag.
- `shared/src/jvmTest/kotlin/com/singularity/todo/arch/EnqueueSiteIsScannedTest.kt` — the
  rule added alongside it, whose own failure messages have to survive being read by a human
  days later, which is the same reason.
- `docs/decisions/2026-10-07-three-write-rules-and-the-one-that-needs-a-server-fact.md` —
  the other half of this round: what the write rules now cover, and what they still need a
  server fact to cover.
