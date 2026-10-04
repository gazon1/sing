---
title: "Maestro flows must be isolated from each other by the runner"
date: 2026-10-04
status: accepted
tags: [testing, maestro, ci, ui-automation, process]
---

# Maestro flows must be isolated from each other by the runner

## Context

The first local run of the `smoke` tag — the set the new `maestro-smoke` CI job
executes, and which had therefore never run anywhere — returned **9 passed,
10 failed**.

Every one of the ten was the same bug. `run-maestro.sh` executed each flow
against a single long-lived app process, so whatever the previous flow left on
screen was still there when the next one began.

The captured hierarchy for `tasks-date-buckets` is the clearest evidence. Its
failure was `Assertion is false: id: nav_tab_today is visible`, and the tree
Maestro captured underneath contained a task overflow sheet reading
"Архивировать / Удалить" — put there by `tasks-archive-via-menu`. The assertion
was true about the screen and false about the app, and nothing in the failure
output pointed at the real culprit.

The pattern across the whole set: the **nine** flows that passed are exactly the
**nine** that run `helpers/launch-clean.yaml`; the **ten** that failed are
exactly the **ten** that do not.

## Idea

- **A.** Make every flow open with `launch-clean`. 22 of 58 flows do not, and
  some of them *must not* — a persistence flow cannot clear its state.
- **B.** Reset the app in the runner, between flows.
- **C.** Run each flow in its own install/launch cycle, treating the flow file
  as the unit of isolation.

## Decision

**B**, in `run-maestro.sh`: before each flow, `am force-stop` the app and then
relaunch it **without** clearing state.

Both halves are load-bearing, and the second one is not obvious:

- **force-stop** is what removes the residue. A `clearState` would be actively
  wrong here — it would destroy the seeded profile or task the flow under test
  depends on. Isolation of the *UI* is not isolation of the *data*.
- **The relaunch is not optional.** `DebugSeedActivity` resolves its Koin graph
  from the running process; its own KDoc says "Requires the app to already be
  running so Koin is initialised". A deep link into a stopped app seeds nothing,
  and the flow then fails at its *next* assertion — one step removed from the
  cause, which is the most expensive kind of failure to diagnose. This is the
  same reasoning as `helpers/relaunch.yaml`, applied to every flow rather than
  to the two that remembered to call it.

### The relaunch must wait for the activity, not for a duration

The first implementation used `monkey -p <pkg> 1` followed by `sleep 2`. It made
the suite **worse**: 9/19 passed before it, 8/19 after.

`monkey` returns as soon as it has dispatched the intent. The flow's first
command then runs against a cold process, `nav_tab_today` is not there yet, and
the failure is indistinguishable from a UI regression — the assertion says the
tab is missing, and the tab really is missing, for a reason no amount of reading
the flow will reveal.

The fix polls `dumpsys activity activities` for `topResumedActivity=…<app>/`
with a bounded timeout (20s, `APP_RESUME_TIMEOUT`). A sleep is a guess: it fails
silently on a slow machine and wastes a minute on a fast one. The resumed
activity is the condition a flow's first command actually needs, and it is
observable, so the wait cannot pass vacuously the way a sleep can. On timeout it
warns and runs the flow anyway — the position before the wait existed, minus the
hang.

**A was rejected** because it pushes the cost onto every flow author and cannot
be enforced: 22 files would each need the line, and the one that forgets is
silent. **C was rejected** as a per-flow cost that buys nothing the runner
cannot buy once.

`launch-clean` stays where it is — as the *data* reset, for flows that want one.
The two mechanisms are orthogonal and the runner comment now says so.

## Rationale

The runner is the only place that knows a suite is a suite. A flow's contract is
"given this starting state, this assertion holds" — and today the starting state
included whatever the previous flow felt like leaving behind. That contract is
not expressible in a flow file, because a flow file cannot see its neighbours.

It also puts the fix where the *symptom* appeared. The failure said
"tasks-date-buckets is broken". The defect was in the runner. A fix in the flow
would have been a fix at the wrong altitude: it would have made that flow pass
and left the next one to inherit the same sheet.

Cost: ~3s per flow. For a 19-flow suite that is a minute, against a defect class
that made half the suite meaningless.

## Consequences

- Flows no longer need `launch-clean` to be isolated from each other. They still
  need it when they assert an *absence* ("this task is not visible"), because
  data from a previous flow can still satisfy or break such an assertion by
  accident. 22 of 58 flows do not open with it, and the runner no longer makes
  that their problem for anything else.
- A failure now names the flow that actually misbehaved. Before this change, the
  flow after a messy one inherited the blame.
- `MAESTRO_MAX_RETRIES` becomes cheaper to reason about: a retry re-runs against
  a freshly launched app, so a retry no longer compounds whatever state the
  first attempt left.
- Parallelising the suite later is now safe by construction. It was not before —
  one shared process is exactly what makes concurrent flows unsafe.
- **Two unrelated defects surfaced in the same run** and are fixed separately;
  they are recorded in
  `docs/decisions/deferred-backlog.md#a-flow-can-be-unrunnable-and-every-check-still-pass`,
  because they are the better argument for `maestro-ci-job-unproven`.

## Links

- `scripts/run-maestro.sh` — the per-flow reset
- `Maestro/helpers/launch-clean.yaml` (data reset), `Maestro/helpers/relaunch.yaml`
- `Maestro/helpers/seed-task.yaml`, `Maestro/helpers/seed.yaml` — deep-link seeding
- `docs/decisions/deferred-backlog.md` — `maestro-ci-job-unproven`,
  `maestro-gate-can-test-a-stale-apk`
- `docs/decisions/2026-10-04-one-gate-recipe.md`
