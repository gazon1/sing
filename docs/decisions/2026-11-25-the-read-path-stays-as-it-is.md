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

An earlier version of this harness priced the observer only as an isolated
measurement and published it as a lower bound of the Flow machinery. That left
the share of the observer inside a real Flow read unmeasured — the question the
issue actually asks — so the split below measures that share **in place**.

## Idea

Ask the same read four ways and let the differences price the layers:

| form | what it is | what it leaves out |
|---|---|---|
| `statement` | the SQL on a connection the form takes for itself: prepare/bind/step and the row parsing | the Flow and the observer |
| `one-shot` | the same SQL as a suspend call (`performSuspending`) | the Flow and the observer |
| `flow without observer` | the same Flow machinery, but the observer replaced by a single emission in the same pipeline | the invalidation observer |
| `flow` | the DAO's Flow, collected once (`first()`) | nothing — this is the full price of a read |

The observer is also measured on its own: `invalidationTracker.createFlow(...)`
with no query, in its own pass.

The price of a read is `flow − statement`, and it splits into three parts that
add up to the same number in every iteration:

| part | what it is | how it is priced |
|---|---|---|
| call path | the coroutine hop into the database dispatcher and the call around the query | `one-shot − statement` |
| Flow plumbing | the Flow machinery around the call: the emission, `conflate`, `map`, the collection | `flow without observer − one-shot` |
| invalidation | the observer: registration, trigger synchronisation, emission | `flow − flow without observer` |

The invalidation share is priced **in place**: the two forms differ by exactly
the observer — the same machinery, the same pool, the same call — so the
difference between them is the price of the observer where a Flow really uses
it. The observer measured apart does not answer that question: measured in
isolation it pays for a connection and a collector of its own, which the Flow
path pays for as well, so it is published next to the parts and **not
subtracted** from them.

The parts are priced **per iteration**, not as the difference of two medians of
two separate passes: the four forms run in one loop, so the difference between
two of them is the difference between the calls. On a shared machine the
difference of two separately measured medians can come out negative, and a
negative part is not a price.

**Every form takes its connection from the same pool — the readers' pool.** No
form holds a connection while another is being measured, and no form is
measured on a connection taken from a different pool: otherwise the difference
between the forms would be a difference between pools rather than between
calls. A reader connection cannot be held across a nested writer registration
— Room refuses to upgrade a reader connection to a writer one
(`Cannot upgrade connection from reader to writer`) — so the statement form
takes its connection for the duration of its own measurement only, and the
price of taking a connection is therefore **inside** `statement`: the clean
price of the query is `statement` minus the price of the connection. The
connection is measured on its own, without a query, and is not carried into
the tables: it is paid identically by all the forms and does not enter the
differences.

Every form that runs the query materialises its rows into a list and keeps it,
the way a DAO does. No form absorbs the price of building the result, and the
difference between two forms is never that price. Before the clock starts, the
statement, the one-shot call, the Flow without the observer and the Flow are
asked for the same tasks and their answers are compared: the SQL in the
harness is a copy of the DAO's, and a copy that has drifted would be a
comparison of two different reads, so the comparison is a check that fails the
run instead of publishing a number.

A fifth number prices the observer on a **change** rather than on an opening: a
live collector, a write, and the wait for the re-read the observer triggers.
The same write without a collector is priced next to it, so that the cost of
the write itself is visible. The observer's share is priced as the
**observer's latency** — the time from the end of the write to the emission the
observer triggers — and it is measured with one clock: the iteration's total
time minus the time of the write leaves exactly the wait for the emission. A
difference of two medians would instead count "the write without a collector"
against "the write with a collector", and on a shared machine that difference
comes out negative, because the write without a collector is charged for the
background work the tracker of the previous iteration left behind. A negative
price is not a price, and the latency is a quantity in its own right — how long
a screen waits for the re-read.

The write is idempotent in the strict sense: it rewrites **the row that is
already there** in the relation — the same edge with the same verb, the same
tag of the task — so the set of rows does not change and the two numbers stay
comparable. Writing a foreign row would add a row and change the answer of the
read after the write.

Latencies are recorded, not asserted: a clock assertion on a shared machine is a
flaky test wearing a benchmark's clothes. What the harness checks is what does
not move with the machine — that the observer really did see the re-read (30
of 30 iterations in every run), and that the four forms answered the same
rows, so that the split compares one read and not two. The numbers are
published as ranges over three runs, so the run-to-run spread is visible
rather than smoothed away, and every run prints the JVM, the core count and the
load average of the machine it ran on: a latency without the state of the
machine is not a measurement.

## Decision

**The read path stays as it is.** No production code changes.

The measurement says the remaining price of a read is Room's own machinery, and
that it is a per-call constant rather than a function of the database:

- the statement — connection included — costs **0.05–0.14 ms** on 10k and
  **0.05–0.13 ms** on 100k;
- the call path costs **0.02–0.09 ms**;
- the Flow plumbing costs **0.02–0.08 ms**;
- the invalidation costs **0.09–0.25 ms** — the largest of the three parts, and
  priced in place, as a share of the Flow price rather than a lower bound;
- the observer measured on its own costs **0.07–0.54 ms**; it is published next
  to the parts and not subtracted from them;
- the whole overhead of a read costs **0.16–0.52 ms**;
- taking a connection from the pool costs **0.05–0.44 ms** (p50) on 10k and
  **0.05–0.11 ms** (p50) on 100k, and it is inside `statement`;
- on a change, the observer's latency — the wait from the end of the write to
  the re-read it triggers — is **0.02–0.24 ms**.

A 10k-task profile and a 100k-task profile produce the same numbers, so
nothing in the remaining price grows with the profile. The task detail screen
reads four Flows (`watchByIdForUser`, `blockingDependencyIdsForUser`,
`followUpDependencyIdsForUser`, `getTagIdsForUser` — `TaskRepositoryImpl.observe(id)`),
which is 0.9–2.8 ms per emission, inside a 16 ms frame.

The runs were taken on a busy machine (load average 56–64 on 16 cores), so the
absolute numbers are larger than in the earlier round of this same issue; the
form of the measurement and the harness itself are unchanged, and every run
prints its load average.

The measurement harness is a program, not a test class: a dataset-dependent
test would either skip itself or fail for a reason that has nothing to do with
the claim. It lives in
`shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadPerformanceHarness.kt`
(orchestration and report) and
`shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadForms.kt`
(the read forms and the write), and is run through `:shared:readOverheadPerf`.

## Rationale

The verdict follows from the numbers rather than from an argument about
complexity. Two facts do the work.

First, the price is not in the query any more: the statement is 0.05–0.14 ms
including the connection, and the query itself is 0.02–0.07 ms of that. There
is nothing left for a rewrite of the SQL to remove.

Second, the price does not grow with the database: the same reads on 10 000
tasks and on 100 000 tasks cost the same 0.16–0.52 ms. A change to the read
path would therefore have to buy a constant, not a curve — and the constant is
already small enough to sit inside a frame.

The split says where the constant sits, and that is what makes the verdict
safe: the largest part is the invalidation observer, which is exactly the
contract the issue forbids touching. The other two parts — the call path and
the Flow plumbing — are Room's own machinery, and replacing them means
reimplementing what Room does, for a fraction of a millisecond.

What a change would cost is concrete too. Replacing Room's Flow machinery means
taking on the invalidation contract — which tables invalidate which read, and
when — and the risk of getting it wrong is a screen that stops updating or
updates on the wrong write. The issue forbids touching that contract, and a
measurement that prices a constant at 0.16–0.52 ms does not justify taking it
on.

The alternative — proposing a change with its own evidence — was not chosen
because there is no candidate worth measuring: the parts are named, they are
Room's, and their size is known. If the price of a read ever matters again, it
will be because the number of reads per screen changed, not because the
per-read cost grew; that is a different question and belongs in its own issue,
like #3194 for the checklist reads.

## Consequences

- The read path is unchanged: no DAO signature, no SQL, no invalidation
  contract, no write path. The measurement is the whole deliverable.
- The number "0.3–2.1 ms per read" in the performance report was an estimate
  and is now a measurement: 0.16–0.52 ms per read, split into the call path
  (0.02–0.09 ms), the Flow plumbing (0.02–0.08 ms) and the invalidation
  (0.09–0.25 ms), with the observer measured on its own (0.07–0.54 ms)
  published next to the parts rather than subtracted from them.
- The observer's share inside a real Flow read is a **measured share**, not a
  lower bound: it is the difference between the DAO's Flow and the same Flow
  with the observer replaced by a single emission, measured on one pool.
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
- Read forms and write: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadForms.kt`
- Gradle task: `:shared:readOverheadPerf` in `shared/build.gradle.kts`
- Issue: https://github.com/gazon1/sing/issues/3196
- Previous measurements: https://github.com/gazon1/sing/issues/3195, https://github.com/gazon1/sing/issues/3176, https://github.com/gazon1/sing/issues/3157
- Dataset generator: https://github.com/gazon1/sing/issues/3090
- Related open question: https://github.com/gazon1/sing/issues/3148
