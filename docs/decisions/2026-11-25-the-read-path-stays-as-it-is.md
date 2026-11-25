---
title: "The read path stays as it is: the overhead left after the SQL rewrite is Room's own, and it does not grow with the database"
date: 2026-11-25
status: accepted
---

# The read path stays as it is: the overhead left after the SQL rewrite is Room's own, and it does not grow with the database

## Context

Issues #3157, #3176 and #3195 measured and rewrote three user-scoped reads —
`TaskDao.getBlockingEdgeIdsForUser`, `TaskDao.observeTypedDependenciesForUser`
and `TaskDao.getTagIdsForUser` — and each rewrite ended with the price of a
read becoming independent of the size of the database. What was left of the
price was not measured: the harness of those issues compared **SQL forms**, so
it could say how much a read costs, but not what the cost is made of.

The estimate the previous report carried was "0.3–2.1 ms per read — the Room
Flow and the invalidation observer", and it was an estimate. This issue asks
for the remaining per-read overhead to be measured **as its own quantity** on a
large and on a small dataset, the split to be recorded, and a verdict to be
given: leave the read path alone, or propose a change with its own evidence.
The issue forbids changing read semantics, the invalidation contract, or the
write path.

The read path in Room 3 is two layers. A suspend DAO method is compiled into
`performSuspending(db, isReadOnly, inTransaction, block)`: the coroutine hop,
the connection, and the statement. A Flow DAO method is
`db.invalidationTracker.createFlow(*tables).conflate().map { performSuspending(...) }`:
the same, plus the invalidation observer and the Flow plumbing. The statement
itself is SQLite's work, and after the rewrites it is nearly free.

## Idea

Ask the same read four ways and let the differences price the layers:

| form | what it is | what it leaves out |
|---|---|---|
| `statement` | the SQL on an already-held connection, clock around prepare/bind/step | nothing — this is SQLite's work |
| `one-shot` | the same SQL as a suspend call (`performSuspending`) | the Flow and the observer |
| `flow` | the DAO's Flow, collected once (`first()`) | nothing — this is the full price of a read |
| `tracker` | the invalidation observer alone: `invalidationTracker.createFlow(...)`, no query | the query itself |

The price of a read is `flow − statement`, and it splits into three parts that
add up to the same number:

| part | what it is | how it is priced |
|---|---|---|
| call path | the coroutine hop and the connection the call takes | `one-shot − statement` |
| observer | the observer's registration and its first emission | `tracker` |
| Flow plumbing | the rest of the machinery around the query | `flow − one-shot − tracker` |

A fifth number prices the observer on a **change** rather than on an opening: a
live collector, a write, and the wait for the re-read the observer triggers. The
same write without a collector is priced next to it, and the difference between
the two is the observer's share. The write is idempotent — the same row, written
again — so the rows of the relation do not change and the two remain comparable.

## Decision

**The read path stays as it is.** No production code changes.

The measurement says the remaining price of a read is Room's own machinery, and
that it is a per-call constant rather than a function of the database:

- the statement costs **0.01–0.02 ms** on both datasets and on all three reads;
- the call path costs **0.04–0.41 ms**;
- the observer costs **0.06–0.38 ms**;
- the Flow plumbing costs **0.10–1.01 ms**;
- on a change, the observer's share of a write is **0.18–1.61 ms**.

A 10k-task profile and a 100k-task profile produce the same numbers, so
nothing in the remaining price grows with the profile. The task detail screen
reads four Flows (`watchByIdForUser`, `blockingDependencyIdsForUser`,
`followUpDependencyIdsForUser`, `getTagIdsForUser` — `TaskRepositoryImpl.observe(id)`),
which is 1–6 ms per emission, inside a 16 ms frame.

The measurement harness is a program, not a test class: a dataset-dependent
test would either skip itself or fail for a reason that has nothing to do with
the claim. It lives in
`shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadPerformanceHarness.kt`
and is run through `:shared:readOverheadPerf`.

## Rationale

The verdict follows from the numbers rather than from an argument about
complexity. Two facts do the work.

First, the price is not in the query any more: `statement` is 0.01–0.02 ms, and
the whole remaining cost is the call around it. There is nothing left for a
rewrite of the SQL to remove.

Second, the price does not grow with the database: the same reads on 10 000
tasks and on 100 000 tasks cost the same 0.2–1.5 ms. A change to the read path
would therefore have to buy a constant, not a curve — and the constant is
already small enough to sit inside a frame.

What a change would cost is concrete too. Replacing Room's Flow machinery means
taking on the invalidation contract — which tables invalidate which read, and
when — and the risk of getting it wrong is a screen that stops updating or
updates on the wrong write. The issue forbids touching that contract, and a
measurement that prices a constant at 0.2–1.5 ms does not justify taking it on.

The alternative — proposing a change with its own evidence — was not chosen
because there is no candidate worth measuring: the parts are named, they are
Room's, and their size is known. If the price of a read ever matters again, it
will be because the number of reads per screen changed, not because the
per-read cost grew; that is a different question and belongs in its own issue,
like #3194 for the checklist reads.

The latencies are recorded, not asserted: a clock assertion on a shared machine
is a flaky test wearing a benchmark's clothes. What the harness checks is what
does not move with the machine — that the observer really did see the re-read
(10 of 10 iterations in every run) — and the numbers are published as ranges
over three runs, so the run-to-run spread is visible rather than smoothed away.

## Consequences

- The read path is unchanged: no DAO signature, no SQL, no invalidation
  contract, no write path. The measurement is the whole deliverable.
- The number "0.3–2.1 ms per read" in the performance report was an estimate
  and is now a measurement: 0.2–1.5 ms per read, split into the call path
  (0.04–0.41 ms), the observer (0.06–0.38 ms) and the Flow plumbing
  (0.10–1.01 ms).
- The remaining per-read cost is documented as **not** growing with the profile.
  A future reader who sees a slow screen does not have to re-measure this to
  know that the read path is not where the time goes.
- The screens that read the cross-reference tables profile-wide (`TaskDao.withExtras`)
  are still a separate question: their price is the price of a profile-wide read,
  not of the call around it, and it stays with #3148.
- The harness and its Gradle task are kept, so the split can be re-measured
  with the documented command when the Room version or the read path changes.
- The checklist reads, still unmeasured, are left exactly as they are — the
  same rule as before: one measured read at a time (#3194).

## Links

- Measurement and results: `docs/architecture/dependency-query-performance.md`, section "Обвязка вызова"
- Harness: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadPerformanceHarness.kt`
- Gradle task: `:shared:readOverheadPerf` in `shared/build.gradle.kts`
- Issue: https://github.com/gazon1/sing/issues/3196
- Previous measurements: https://github.com/gazon1/sing/issues/3195, https://github.com/gazon1/sing/issues/3176, https://github.com/gazon1/sing/issues/3157
- Dataset generator: https://github.com/gazon1/sing/issues/3090
- Related open question: https://github.com/gazon1/sing/issues/3148
