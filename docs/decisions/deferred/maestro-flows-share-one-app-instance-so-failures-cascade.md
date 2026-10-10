---
title: "Maestro Flows Share One App Instance So Failures Cascade"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED**

**Tracked as:** #105

**Found in:** 2026-10-04, the first ever local run of the `smoke` set — the
set the new `maestro-smoke` CI job runs, which had never executed anywhere.

**Result: 9 passed, 10 failed.** All ten failures were this one bug.

`run-maestro.sh` ran every flow against **one long-lived app process**. Whatever
a flow left on screen — a modal bottom sheet, a snackbar, a half-typed editor —
was still there when the next flow started. The captured hierarchy for
`tasks-date-buckets` shows why it looked like that flow's own bug: the tree
underneath was a task overflow sheet reading "Архивировать / Удалить", put
there by `tasks-archive-via-menu`. `nav_tab_today` was genuinely not visible,
because a sheet was covering it.

The corroborating detail: the nine flows that passed are exactly the nine that
run `helpers/launch-clean.yaml` (which does `launchApp: clearState: true`); the
ten that failed are exactly the ten that do not. `seed-task.yaml` runs it
internally, which is why the flows that seed through it mostly recovered.

**Why this survived so long:** the `agenda` tag — the only tag any gate ran —
is 8 flows that all use `launch-clean`. The broken ones are spread across
`smoke`, `tasks` and `system`, none of which had ever been run as a set.

**Fix (harness):** `run-maestro.sh` now force-stops the app before each flow and
relaunches it **without** clearing state. Both halves are load-bearing:

- `force-stop` removes the residue. A data *clear* would be wrong — it would
  destroy the seeded profile or task the flow under test depends on.
- The relaunch is not optional. `DebugSeedActivity` resolves its Koin graph
  from the running process (its own KDoc says "Requires the app to already be
  running"), so a deep link into a stopped app seeds nothing and the flow fails
  at the next assertion.

**Two unrelated defects found in the same run**, both invisible to every
existing check:

1. `Maestro/flows/tasks/09-rename-empty.yaml` used `- longPress:`. The Maestro
   command is `longPressOn` — `longPress` is not a command at all, so the flow
   failed at *parse* time with "Invalid Command". `05-archive-via-menu.yaml`
   uses the correct spelling three lines apart, which is why nobody noticed.
2. `DebugSeedActivity` dispatches on a `when` whose first matching key wins, so
   `todo-debug://seed?task=X&profile=Y` creates the task in the *current*
   profile and silently ignores `profile=Y`. `profile/02-isolation.yaml` used
   exactly that URL, so its task landed in Personal and the flow's isolation
   assertion failed — while the test it was written to guard could not have
   passed either way. Fixed by switching profiles explicitly, then seeding.

**Do this first:** the harness fix covers every future run, but 22 of 58 flows
still do not open with `launch-clean`, so each depends on the harness for
isolation rather than declaring it. That is fine and is the cheaper default —
but any flow that asserts "this does not exist" needs a clean start of its own,
because a previous flow's data will otherwise satisfy or break the assertion by
accident.

---
