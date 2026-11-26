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

The four forms are **not** run in one fixed order. The order is shifted by one
step from slot to slot, so that every form is measured in every position the
same number of times. A fixed order is a systematic bias: the first form of a
slot pays for the cold pool, the cold caches and the cold code path, and the
last one is measured on whatever the earlier forms warmed up — so `flow −
statement` and its three components would partly be the difference between
**positions**, and no number of samples would remove that. The shift removes the
bias and changes nothing else: the forms still run one after another in the same
slot, under the same clock.

**Every form takes its connection from the same pool — the readers' pool.** No
form holds a connection while another is being measured, and no form is
measured on a connection taken from a different pool: otherwise the difference
between the forms would be a difference between pools rather than between
calls. A reader connection cannot be held across a nested writer registration
— Room refuses to upgrade a reader connection to a writer one
(`Cannot upgrade connection from reader to writer`) — so the statement form
takes its connection for the duration of its own measurement only, and the
price of taking a connection is therefore **inside** `statement`.

**The statement form is timed twice in the same call:** the whole call, and the
**SQL work** inside the connection — the preparation, the binding, the execution
and the mapping. What is left of the whole call is what the call paid before
the statement was prepared: the hop to the database's dispatcher, taking a
connection from the pool and handing it back. The two are paired **by slot**, so
the connection's share is the difference of the samples of one slot and not the
difference of two separately measured percentiles — the difference of two
percentiles is not the percentile of the difference, and a report that claimed
the price of the SQL from such a difference would claim something the
measurement does not establish. Both numbers are published: the SQL work next to
the statement, the connection's share next to it. The connection does not enter
the differences of the parts: it is paid identically by every form.

The connection is also measured on its own, without a query, and is not carried
into the tables: it is paid identically by all the forms and does not enter the
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
not move with the machine — that the observer really did see the re-read, and
that the four forms answered the same rows, so that the split compares one read
and not two. Both checks stop the run instead of publishing a number:

- the wait for an emission of the observer is bounded (30 s), and a wait that
  runs out **fails the run**: a slow machine is one thing, an observer that did
  not see the re-read is a broken measurement, and a benchmark that publishes
  numbers for it publishes numbers about nothing;
- the number of re-reads the observer really produced is checked against the
  number of iterations, so a run that saw fewer re-reads than it wrote cannot
  present its numbers as if it had seen all of them.

The arithmetic of the measurement is checked separately, without a clock, by
`ReadOverheadMeasurementTest`: the nearest-rank percentile, the identity
"overhead = call path + Flow plumbing + invalidation" in every slot, the refusal
to pair forms whose samples do not have the same count, and the evenness of the
shifted order. The forms themselves and the observer's reaction are checked on a
**real Room database** by `ReadOverheadFormsIntegrationTest`: all four forms
answer the same rows, and the write that invalidates the read really does
trigger a re-read — with a bounded wait, so a missing re-read fails the test.
The numbers are published as ranges over six runs, so the
run-to-run spread is visible rather than smoothed away, and every run prints the
JVM, the core count and the load average of the machine it ran on: a latency
without the state of the machine is not a measurement.

## Decision

**The read path stays as it is.** No production code changes.

The measurement says the remaining price of a read is Room's own machinery, and
that it is a per-call constant rather than a function of the database:

- the statement — connection included — costs **0.06–0.17 ms**;
- the call path costs **0.00–0.05 ms**;
- the Flow plumbing costs **0.03–0.10 ms**;
- the invalidation costs **0.10–0.26 ms** — the largest of the three parts, and
  priced in place, as a share of the Flow price rather than a lower bound;
- the observer measured on its own costs **0.06–0.68 ms**; it is published next
  to the parts and not subtracted from them;
- the whole overhead of a read costs **0.16–0.56 ms**;
- the SQL work inside the connection — the preparation, the binding, the
  execution and the mapping — costs **0.02–0.08 ms**, and it is measured, not
  derived: it is taken inside the connection in the same slot as the whole call;
- the connection's share of the slot — the hop to the dispatcher, taking the
  connection from the pool and handing it back — costs **0.03–0.09 ms**, and it
  is the difference of the samples of one slot, not the difference of two
  separately measured percentiles;
- taking a connection from the pool costs **0.05–0.26 ms** (p50), and it is
  inside `statement`;
- on a change, the observer's latency — the wait from the end of the write to
  the re-read it triggers — is **0.02–0.83 ms**.

A 10k-task profile and a 100k-task profile produce the same numbers, so
nothing in the remaining price grows with the profile. The task detail screen
reads four Flows (`watchByIdForUser`, `blockingDependencyIdsForUser`,
`followUpDependencyIdsForUser`, `getTagIdsForUser` — `TaskRepositoryImpl.observe(id)`);
the three of them measured here cost **0.86–1.70 ms** per emission together,
inside a 16 ms frame.

The runs were taken on a busy machine (load average 41–48 on 16 cores), so the
absolute numbers are larger than in the earlier round of this same issue; the
form of the measurement and the harness itself are unchanged, and every run
prints its load average. The published ranges are over six independent runs of
each dataset; the observer's latency is the noisiest of the numbers, because it
waits for the tracker's background work rather than for SQLite.

The measurement harness is a program, not a test class: a dataset-dependent
test would either skip itself or fail for a reason that has nothing to do with
the claim. It lives in
`shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadPerformanceHarness.kt`
(orchestration and report) and
`shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadForms.kt`
(the read forms and the write). It runs through the harness of the SQL
comparison (#3174) as its second experiment —
`:shared:dependencyQueryPerf -Pperf.experiment=read-overhead` — so that the
measurement does not add a second benchmark framework to the repository: one
program, one dataset pipeline, one set of arguments.

## Rationale

The verdict follows from the numbers rather than from an argument about
complexity. Two facts do the work.

First, the price is not in the query any more: the statement is 0.06–0.17 ms
including the connection, and the SQL work is 0.02–0.08 ms of that. There
is nothing left for a rewrite of the SQL to remove.

Second, the price does not grow with the database: the same reads on 10 000
tasks and on 100 000 tasks cost the same 0.16–0.56 ms. A change to the read
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
measurement that prices a constant at 0.16–0.56 ms does not justify taking it
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
  and is now a measurement: 0.16–0.56 ms per read, split into the call path
  (0.00–0.05 ms), the Flow plumbing (0.03–0.10 ms) and the invalidation
  (0.10–0.26 ms), with the observer measured on its own (0.06–0.68 ms)
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
- The order of the four forms is shifted from slot to slot, so that no form is
  always measured first or always last. Under a fixed order the first form of a
  slot paid for the cold pool and the cold code path, and the difference
  between the forms was partly the difference between their positions; the
  shift removes that bias, and a test checks that the shift is even.
- The checklist reads, still unmeasured, are left exactly as they are — the
  same rule as before: one measured read at a time (#3194).

## Links

- Measurement and results: `docs/architecture/dependency-query-performance.md`, section "Обвязка вызова"
- Harness: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadPerformanceHarness.kt`
- Read forms and write: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadForms.kt`
- Arithmetic of the measurement: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadMeasurementTest.kt`
- Forms and observer on a real database: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/tasks/performance/ReadOverheadFormsIntegrationTest.kt`
- Gradle task: `:shared:dependencyQueryPerf -Pperf.experiment=read-overhead` in `shared/build.gradle.kts`
- Issue: https://github.com/gazon1/sing/issues/3196
- Previous measurements: https://github.com/gazon1/sing/issues/3195, https://github.com/gazon1/sing/issues/3176, https://github.com/gazon1/sing/issues/3157
- Dataset generator: https://github.com/gazon1/sing/issues/3090
- Related open question: https://github.com/gazon1/sing/issues/3148
