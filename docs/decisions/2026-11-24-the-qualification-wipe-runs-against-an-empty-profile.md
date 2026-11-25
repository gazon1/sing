---
title: "The qualification wipe runs against an empty profile"
date: 2026-11-24
status: accepted
tags: [maestro, e2e, testing, tooling]
---

## Context

The qualification runner wipes the app's content before every flow: it sends
`todo-debug://seed?reset=content` and waits for the app to come back
(`scripts/src/emulator/maestro/_fixtures.py`, `_device.py`). The flows rely on
that: `agenda/07-large-overdue` asserts on absolute counts ("Overdue · 300"),
so a row an earlier flow left behind makes every count in it wrong.

The wipe is a deep link, and a deep link *starts* the app when nothing is
running. Two things follow, and both were found while the qualification sweep
for `2026-11-23-the-seed-helper-waits-for-the-entity-it-seeded` was running:

1. On a cold start the wipe runs before the E2E session is installed. The
   wipe deletes the rows of the profile the app has *at that moment*
   (`ProfileContentResetter.resetContent()` reads `currentUser.scopedUserId`),
   and at that moment the app has no profile identity yet — so the wipe
   reaches an empty scope and deletes nothing. Measured on an emulator: 375
   rows before the link, 375 rows after it, while the same link on a running
   app took the count to 0.
2. The wait that follows the link is for the main activity to come back
   (`wait_for_main_activity_resumed`). On a cold start the main activity is
   already the resumed one — the poll sees it at once, returns, and the
   force-stop that follows kills the reset half-way.

So the runner's own reset is both ineffective on a cold start and racy
against the force-stop that follows it.

## Idea

- The wipe has to run against a profile that exists: the app has to be up,
  with its session installed, before the link is sent.
- The wipe has to be *finished* before the flow starts: the link brings the
  debug activity to the front, the wipe runs there, and the app comes back to
  the main activity when it is done.
- The runner's reset link must not be trusted as the "device is clean"
  guarantee in a qualification run, because a qualification is exactly the
  place where a wrong count must not be possible.

## Decision

The qualification sweep cleans the device itself, before every flow, with the
same link — but sent to an app that is already running:

1. `am force-stop` the app.
2. `am start -n com.singularity.todo/.MainActivity` and wait for the main
   activity to be resumed. The session is installed when the app starts.
3. Send `todo-debug://seed?reset=content` and wait for the *debug* activity to
   be in the foreground. This wait is what tells the two apart: without it the
   poll for the main activity returns at once, because the main activity was
   already there.
4. Wait for the main activity to come back — the wipe is over.
5. Only then run the flow.

The driver is `build/diagnostics/qual_one.sh` (a diagnostic script, not a
committed tool). The runner's own reset is left alone: it is main's behaviour
and it is not this entry's business to change.

The consequence for the flows is written down where the flows read it: the
profile has to be empty when the flow starts, and the harness that runs a
qualified flow is what makes it empty.

## Rationale

The alternative — fixing the runner — was not taken, for three reasons.

- The runner is shared tooling with its own ownership, and the fix is not
  local: the reset link, the resume wait and the force-stop are three
  separate places in `_fixtures.py` and `_device.py`, and the fix changes when
  a device is considered clean for every flow in the suite.
- The qualification is a bounded, one-off activity that needs the guarantee
  for its own runs. It can carry the guarantee itself, in a script that says
  what it does.
- The cost of the wrong guarantee is not hypothetical: a flow that asserts on
  an absolute count passes against a profile that holds the rows of the
  previous flow, and the qualification records a pass for a run that measured
  the wrong thing.

The other alternative — accepting the wipe as it is and removing the absolute
counts from the flows — was not taken either: the absolute count is what
makes `agenda/07-large-overdue` a regression test at all. The count is the
evidence that the chunk landed.

## Consequences

- A qualified run is a run against a profile that was wiped, and the wipe is
  done by the harness rather than by the runner.
- `Maestro/helpers/launch-clean.yaml` says the runner resets the content
  before every flow. That is the intent; on a cold start it does not happen.
  The flows that depend on an empty profile depend on the harness, and the
  qualification driver is the harness.
- The sweep is slower than a bare run: the clean-up costs about 30 s a flow
  (the app has to start, the link has to run, the app has to come back).
- The bug in the runner is real and is not fixed here. It is a separate piece
  of work for the tooling: the runner has to send the reset to a running app,
  and it has to wait for the debug activity before it waits for the main one.
- `agenda/07-large-overdue` is sized against the runner's five-minute budget
  for a flow. At four hundred rows in sixteen chunks the flow took 216 s on a
  host that was busy with the sweep itself — 72 % of the budget — and the
  budget is a hard cut-off. It now seeds three hundred rows in twelve chunks,
  which is fifteen times the count at which the agenda collapses the overdue
  section (`AgendaPresets.LARGE_OVERDUE_THRESHOLD`).

## Links

- `scripts/src/emulator/maestro/_fixtures.py` — the runner's reset link.
- `scripts/src/emulator/maestro/_device.py` — the wait for the main activity.
- `shared/src/commonMain/kotlin/com/singularity/todo/core/wipe/ProfileContentResetter.kt`
  — the wipe, and the profile identity it reads.
- `Maestro/helpers/launch-clean.yaml` — the helper that says what a clean
  device is.
- `Maestro/flows/agenda/07-large-overdue.yaml` — the flow whose absolute
  counts need the clean device.
- `docs/decisions/2026-11-23-the-seed-helper-waits-for-the-entity-it-seeded.md`
  — the entry whose sweep found this.
