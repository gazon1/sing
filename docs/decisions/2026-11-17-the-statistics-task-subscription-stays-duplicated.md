---
title: "The duplicated statistics task subscription is measured, not assumed away"
date: 2026-11-17
status: accepted
tags: [statistics, room, performance, testing, ponytail]
---

# The duplicated statistics task subscription is measured, not assumed away

## Context

`StatisticsViewModel` subscribes to `deps.taskRepository.observeByFilter(TaskFilter.All)`
four times — once for the general statistics snapshot, once for the health counts,
once for the attention nudges, once for the insights. A review of the derived
attention nudges feature flagged the duplication as a possible defect: two
observers of the same table, two independent recomputations, two Room query
plans for one screen.

The review was explicit that this is a hypothesis, not a finding, and asked for a
measurement before any refactor — and for the two collectors to stay independent
even if a shared snapshot were ever introduced (REQ-TH-018 requires that a
failure reading projects cannot blank the health counts, and a failure reading
tasks cannot blank the attention list).

`observeByFilter` is a cold flow. `TaskRepositoryImpl.observeByFilter` delegates to
`taskDao.watchActive(uid)` under `currentUser.observeForCurrentUser { … }`, and
`TaskExtras.withExtras` decorates that with cross-reference streams for tags and
dependencies. Each subscription therefore runs its own set of Room queries —
four per subscription, not one.

## Idea

Measure the real cost of the duplication on a realistic task count before
changing anything. A refactor that trades a guaranteed contract (independent
error handling per collector) for an unmeasured saving is a net loss.

## Decision

**Keep the duplicated subscriptions. Do not merge the collectors.**

The measurement was taken with a temporary JVM harness against the real in-memory
Room database (warm cache), subscribing to the same repository the ViewModel uses:

| tasks | emission | 1 collector | 4 collectors | 8 collectors |
|---|---|---|---|---|
| 500 | first | 14.1 ms | 29.1 ms | 33.0 ms |
| 500 | update | — | 16.3 ms | — |
| 2000 | first | 37.6 ms | 48.7 ms | 41.4 ms |
| 2000 | update | — | 67.0 ms | — |

The flow is cold: every subscriber runs its own query, and the cost of an
emission of the whole task list grows with the number of subscribers. But the
absolute numbers are tens of milliseconds, not seconds — and the Statistics screen
is not a hot path. Four collectors at 2000 tasks cost roughly 50–70 ms per
emission of the full list.

Against that, merging the collectors would cost the independent error handling
that REQ-TH-018 demands, and would couple the health counts to the project read
that the attention list performs.

**The measurement is not kept as a test.** A timing assertion is flaky by
construction, and pinning the number of SQL queries would bind the test to Room's
internals. The result lives in this entry instead.

## Rationale

**Why measurement before refactor.** The review's own recommendation was not to
refactor. The measurement confirms it rather than assuming it: the duplication
costs a constant multiplier of tens of milliseconds on a rarely opened screen,
while the benefit of merging — fewer query plans — does not outweigh a contract
the feature explicitly promises.

**Why the four subscriptions are not three or two.** The statistics snapshot,
health counts, attention nudges and insights are four separate flows with four
separate lifecycles. They were not introduced as an accident of the nudges work;
the nudges feature added the fourth and the others predate it.

**Why no timing test.** A test asserting "emission takes less than N ms" fails on
a loaded CI machine. A test asserting "exactly four SQL queries are issued"
passes only until Room changes its query plan. Neither would have caught a real
regression in user-visible behaviour, which is why the desktop flow tests and the
ViewModel tests cover the behaviour instead.

## Consequences

- The Statistics screen performs four task query plans per emission instead of
  one. At 2000 tasks this is ~50–70 ms per emission, on a screen the user opens
  deliberately. If the screen ever becomes a hot path (a widget, a background
  refresh, a list that stays subscribed), the duplication must be re-measured.
- REQ-TH-018 keeps its meaning: the health collector and the nudges collector
  fail independently, and each direction is covered by a desktop flow test.
- The measurement harness was deleted after use. Nothing in the repository pins a
  performance number, so the decision cannot silently drift into "we measured it
  once and the number still holds".

## Links

- `2026-11-15-task-health-has-one-classifier.md` — the single owner of the
  health classification, referenced by both collectors.
- `2026-11-16-derived-attention-nudges-in-app-first.md` — the feature this
  measurement belongs to.
- `openspec/changes/derived-attention-nudges/specs/tasks/spec.md` — REQ-TH-017
  (hidden-item counter) and REQ-TH-018 (independent collectors).
