---
title: "The title door orders the ids of the matched tasks and reads their rows afterwards"
date: 2026-11-25
status: accepted
---

# The title door orders the ids of the matched tasks and reads their rows afterwards

## Context

Issue #3199 asked for a measurement of the FTS ordering cost on large task
databases: what does the title door of the search index cost on 10k / 50k /
100k tasks, how much of that is the index matching rows and how much is
ordering and materialising the matching set, and which query plans and which
indexes are involved.

The door is `FtsDao.searchTaskTitles` — the tasks whose title matches the
index, the first twenty, most recently updated first. Behind it sat the raw
door `searchTaskTitlesByMatch`, which took the `MATCH` expression Room runs.
It is read by the Related Tasks picker (`SearchTextIndexImpl.recentTaskTitles`)
and by the internal-link suggestions. Before the change it was one statement:

```sql
SELECT t.*, offsets(tasks_fts) AS match_offsets FROM tasks t
JOIN tasks_fts ON t.rowid = tasks_fts.docid
WHERE tasks_fts MATCH :match
  AND t.user_id = :userId
  AND t.archived_at IS NULL
ORDER BY t.updated_at DESC
LIMIT :limit
```

The measurement harness
(`shared/src/jvmTest/.../performance/FtsOrderingPerformanceHarness.kt`, run
through `:shared:ftsOrderingPerf`) restores the shared datasets of #3090 and
measures sixteen statements per scenario, interleaved round by round, in
three pictures of the same dataset: the state a restore leaves (every row with
the same `updated_at`), the state of a working database (the dataset's own
order put back into `updated_at`) and a probe picture (`updated_at =
-created_at`) that separates the cost of sorting from the cost of fetching.

What it found was not a slow index. The matching itself costs 0.8–6.8 ms at
10k–100k for a term that matches every row. The cost was in what the sorter
carried.

The statement sorted **the whole task row and the position of the match**
for every row the term matched — a hundred thousand of them at 100k — and then
threw away all but twenty. The limit sits after the `ORDER BY`, so it filters
*after* the sorter has already carried every payload. Splitting that payload
apart prices it:

| what the sorter carries | 10k | 50k | 100k |
|---|---:|---:|---:|
| the id alone | 7.3 ms | 53.9 ms | 82.5 ms |
| the task row, no positions | 23.0 ms | 227.5 ms | 214.0 ms |
| the id and the positions | 27.9 ms | 194.0 ms | 303.4 ms |
| the task row and the positions (what shipped) | 41.4 ms | 285.7 ms | 456.2 ms |

The sorting itself is cheap next to that. The harness prices the match set
without an order and with one, both statements reading **the whole matching
set** so that the difference is the sort and nothing else: at 100k in the
working picture, 45.5 ms without the order and 77.8 ms with it — the sort
costs about 32 ms. The shipped statement paid 456.2 ms at 100k: the payload
the sorter carried cost about 378 ms, twelve times the sort.

## Idea

Order the ids, read the rows of the survivors afterwards.

## Decision

`FtsDao.searchTaskTitles` is now two statements — `searchTaskTitleIdsByMatch`
and `searchTasksByIds` — behind one default interface method:

```sql
-- phase A: the order
SELECT t.id FROM tasks t
JOIN tasks_fts ON t.rowid = tasks_fts.docid
WHERE tasks_fts MATCH :match
  AND t.user_id = :userId
  AND t.archived_at IS NULL
ORDER BY t.updated_at DESC, t.rowid DESC
LIMIT :limit

-- phase B: the rows of the survivors
SELECT t.* FROM tasks t
WHERE t.id IN (:ids)
  AND t.user_id = :userId
  AND t.archived_at IS NULL
```

Phase B reads at most `limit` rows by primary key — the plan says `SEARCH t`,
never `SCAN t` — and the method re-orders the answer by the ids phase A
returned, so the order the door answers with is the order phase A decided.

The two phases are **one transaction**: the method carries `@Transaction`, and
Room wraps both reads in it. Two reads a moment apart would be two answers —
a task whose title was edited between the phases could be offered although it
no longer matches, and a row could be read in a state the order no longer
describes. Inside a transaction both reads see the same snapshot, so the
offered rows are exactly the rows the order was decided over.

The order carries a tiebreaker on purpose: `t.rowid DESC`. Two tasks updated
at the same moment have no order between them, and the order SQLite's sorter
happened to give them was the order of the bytes of the rows — not a rule
anybody could rely on. The tiebreaker makes it a rule: among the tasks
updated at the same moment, the most recently inserted one comes first. It
also makes the two-phase form provably equal to the single statement with the
same `ORDER BY`, which is what the regression test asserts.

The match positions are **not** carried through this door. `offsets(tasks_fts)`
gives the position of the word inside the title; the readers of this door offer
a title for a link and never read the position. FTS4 can report the position
only while it is answering a `MATCH`, so reading it back is a second pass over
the whole match set — measured as `two_phase_offsets`: 361.6 ms at 100k, where
the two-phase read without positions costs 80.2 ms.

No index was added to the schema. `INDEX tasks(user_id, updated_at DESC)`
changes the plan for the worse: the same query on a selective term costs
3340 ms at 100k with the index and 0.259 ms without it, because with the index
SQLite walks it and evaluates `MATCH` row by row instead of walking the ready
list of matches once. Rewriting the query to keep the match set as a subquery
repairs that (10.9 ms for the rare term) but still loses to the two-phase read
on the frequent term (209.5 ms against 80.2 ms). The schema therefore stays as
it was, `SCHEMA_VERSION` stays 55, and no migration is needed.

## Rationale

The measurement is what chose this, and it chose it over the alternatives that
were measured too — a composite index, a rewritten query on that index, and a
single statement with the ordering in a subquery (158.3 ms at 100k, worse than
the two-phase read).

The claim of the change is narrow and it is measured separately from the
matching cost. The harness prices the matching (`fts_match_count`), the join
(`match_count`), the limit without an order (`match_limit_no_order`) and the
order with it (`match_order`) as separate statements, and the match-order
statements are written with a `CROSS JOIN` so that the difference between them
is the order and not a change of plan. Because both of those statements carry
a `LIMIT`, the difference between them prices the order **as the production
statement pays for it** — the unordered one stops after twenty rows while the
ordered one has to walk the whole match set to learn which twenty come first.
The price of the sort itself is therefore measured separately, by
`match_all_no_order` against `match_all_order`: both read the whole matching
set, and the difference between them (32 ms at 100k) is the sort and nothing
else.

What the price shows is that the ordering was never the expensive part — about
32 ms of the 456 ms the shipped statement cost at 100k — and that the
expensive part was the payload: about 378 ms of moving rows and offset blobs
through the sorter.

The tiebreaker is the one change that makes something *slower*, and it is
stated as such. In the picture a restore leaves, where every row carries the
same `updated_at`, the first sort key distinguishes nothing and the second key
does all the work: the new statement costs 8.6 ms against 5.6 ms at 10k, and
102.8 ms against 54.5 ms at 100k. In the picture of a working database the
tiebreaker is nearly free (80.2 ms against 86.1 ms at 100k), because the first
key already distinguishes the rows. The price buys a rule where there was an
accident, and on a restored database — where the old door answered with
twenty arbitrary rows out of a hundred thousand — it buys an answer that means
something: the twenty most recently inserted.

The change is a rewrite of one statement, not a new abstraction, because the
shape of the cost was local: one statement, one door, one sorter.

## Consequences

- The title door is now two reads, and both of them are in one transaction.
  That is what makes them one answer: without it, a task whose title was
  edited between phase A and phase B could be offered even though it no longer
  matches, and a row could be read in a state the order no longer describes.
  Inside a transaction both reads see the same snapshot, so the offered rows
  are exactly the rows the order was decided over. A task archived between the
  two phases is still dropped, because phase B states the archive filter again.
- `FtsDao.searchTaskTitles` answers with `List<TaskEntity>` instead of
  `List<TaskMatchRow>`: the door no longer carries the position of the match.
  `SearchTextIndexImpl.recentTaskTitles` maps the tasks it gets, and no reader
  of this door read the position. The raw door behind it is now
  `searchTaskTitleIdsByMatch` — the ids, in the order the door decided — and
  `searchTasksByIds` reads the rows of the survivors. The old raw door, which
  answered with the row and the position, is gone from the interface: the only
  thing it could add to the ordering was the payload the measurement priced.
- On a working database the cost of the door on 100 000 tasks falls from
  456.2 ms to 80.2 ms for a term that matches every task; on a restored
  database — where every `updated_at` is the same — it rises from 54.5 ms to
  102.8 ms, and that rise is the price of the deterministic tiebreaker.
- The position of the match is still carried by the doors that show it —
  the task search and the note search — and their cost is unchanged.
- The note door (`searchNoteTitlesByMatch`) has the same shape and was **not**
  touched: the issue is about task databases, and its own measurement is a
  follow-up.
- The regression test `FtsTaskTitleOrderingTest` asserts the shape and not a
  duration: that the two-phase door answers exactly what the single statement
  with the same order answers, that the tie order is a rule and is the same
  every time the door is asked, that the second phase is a `SEARCH` and never a
  `SCAN`, that the ordering statement selects the id alone, that the two phases
  share one transaction, and that a task of another user and an archived task
  are never offered. A clock would measure the machine, and the machine is
  shared.

## Links

- Measurement and results: `docs/architecture/fts-ordering-performance.md`
- Harness: `shared/src/jvmTest/kotlin/com/singularity/todo/feature/search/performance/FtsOrderingPerformanceHarness.kt`
- Regression test: `shared/src/jvmTest/kotlin/com/singularity/todo/core/database/FtsTaskTitleOrderingTest.kt`
- Gradle task: `:shared:ftsOrderingPerf` in `shared/build.gradle.kts`
- Issue: https://github.com/gazon1/sing/issues/3199
- Dataset generator: https://github.com/gazon1/sing/issues/3090
- Related measurement: `docs/architecture/related-task-picker-performance.md`
