---
title: "NoDate bisect — the domain is sound; the break is above AgendaEvaluator"
date: 2026-09-30
tags: [agenda, testing, debugging, bisect]
status: accepted
follows: 2026-09-30-desktop-compose-ui-flow-tests
---

# NoDate root cause — partial

## Context

The desktop flow suite reported that an undated task is written successfully —
`TaskRepository.observeAll()` returns it — while the agenda renders "No tasks",
and stays empty across a tab switch that recreates the ViewModel
(`2026-09-30-desktop-compose-ui-flow-tests.md`, Open).

`RelativeBucket.NoDate` maps to `DateRange(1970-01-01, 1970-01-01)`, which read
as a sentinel that no task can match. That reading is wrong: `SelectorMatcher`
special-cases the bucket with a direct `task.dueDate == null` branch, so the
domain does not depend on the sentinel at all.

## What the bisect established

A layered bisect was run, cheapest and most deterministic layer first.

**Step 0 — which preset did the desktop test open?** Rejected. `AgendaPresets.Inbox`
declares a "No Date" section (order 6); only `AgendaPresets.Today` does not, and
the failing test opened Inbox. So "the test opened the wrong preset" does not
explain an empty agenda.

**Step 1 — the pure function.** `AgendaNoDateRegressionTest` (5 tests, green)
pins that an active undated task lands in Inbox → No Date, carries
`AgendaBadge.NoDate`, and belongs to exactly one section. **The domain is
sound.**

**Steps 2–4** — the Fake ⇄ Room contract, the ViewModel `combine`, and the
`scopedUserId` question — were not run. They are deferred to
`deferred-backlog.md#nodate-steps-2-4` with the reasoning for each.

## Decision

Record the bisect result rather than a fix. There is nothing to fix in the domain,
and the remaining layers are not yet exonerated. Shipping a "fix" now would
change working code to match a symptom that has not been located.

## Rationale

- The 1970 sentinel is misleading but harmless, because the matcher never uses
  it. Reading the enum mapping instead of the matcher is what sent the first
  hypothesis at the wrong layer.
- A green domain test is the *control* that makes the next bisect meaningful:
  steps 2–4 can now be trusted to localise the break instead of re-litigating
  code that was never wrong.
- The desktop symptom is a harness observation, not a user report. Treating a
  test-only symptom as a product bug is how the previous four UI-testing ADRs
  ended up accepted-but-never-built.

## Consequences

- `AgendaNoDateRegressionTest` stays as a regression guard. It is cheap,
  deterministic, and documents a contract (`No Date` is matched directly, not
  through a range) that a future reader could plausibly "simplify" into the
  broken form.
- One incidental contract was pinned in passing: **the Inbox preset does not
  filter by completion.** `TaskDao.watchActive` selects on `archived_at IS NULL`
  and nothing else, so a completed task is still listed, badged `Completed`.
  Only archiving removes a row. The first draft of that test asserted the
  opposite and was wrong; the test now states the real behaviour, because the
  obvious wrong reading is the one a reader will reach for.
- The open question in `2026-09-30-desktop-compose-ui-flow-tests.md` stays open.
  It is narrowed to the layers above `AgendaEvaluator`, not answered.

## Open

- Steps 2–4: whether `FakeAppDatabase` diverges from Room on this path, whether
  `AgendaViewModel`'s `combine(scopedUserId, todayFlow)` is the break, or
  whether the desktop symptom was an artefact of the harness. Per the bisect's
  own rule, "everything green" does not license closing it as a known issue
  without a second full-suite run.
- Whether a task with a `startDate` but no `dueDate` counts as "No Date". Schema
  v16 added start/end; no ADR states the semantic. The rule currently lives
  twice — `Selector.DateBucket.NoDate` matches on `dueDate` alone, and
  `AgendaEvaluator.computeBadge` returns `NoDate` on the same condition. Both
  should route through a single `TaskComputed.hasNoDate`.

## Links

- `deferred-backlog.md#nodate-steps-2-4`
- `2026-09-30-desktop-compose-ui-flow-tests.md`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/domain/AgendaNoDateRegressionTest.kt`
