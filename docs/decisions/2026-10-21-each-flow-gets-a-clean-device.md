---
title: "Each flow gets a clean device, or the run measures its own order"
date: 2026-10-21
status: accepted
tags: ["architecture", "e2e", "maestro", "tooling"]
---

## Context

The E2E tier runs every flow against **one** device and **one** database. The
only thing that separates two flows is a sweep the runner sends between them:

    scripts/src/emulator/maestro/_fixtures.py
        encoded = urllib.parse.quote(f"{run_id},Buy milk")
        cmd = f"am start -a android.intent.action.VIEW -d todo-debug://seed?cleanup={encoded}"

The app matches titles by **prefix**, so that removes the rows whose title
starts with the run id, and the rows whose title starts with `Buy milk`. Those
are the only two prefixes it knows.

The suite seeds more than those. `Maestro/flows` creates rows under at least
five other fixed titles — `pokupki`, `renameme`, `savedalpha2026`,
`seed_task`, `write_spec` — and those rows are **never** swept. They are still
on the device for every flow after them.

The consequence is not visible in the report. A flow that asserts an empty list
fails because of the *previous* flow's data; a flow that asserts its own task
fails because another flow's row is in the way. Nothing in the JUnit XML says
"the state was not yours".

The full run of 2026-10-21 made this measurable for the first time, because
the nine flows that had never run now run — so the order changed:

| | before | after |
|---|---|---|
| flows that reported | 85 | 94 |
| passed | 49 | 43 |
| failed | 36 | 51 |

Seven flows passed before and failed after. Six of them pass when they are run
**alone** against a clean device:

| flow | in the full run | alone on a clean device |
|---|---|---|
| `01-kill-restart-persist` | fail | pass |
| `01-offline-create-survives-reconnect` | fail | pass |
| `04-health-stagnant-drilldown` | fail | pass |
| `04-signin-with-seeded-data` | fail | pass |
| `05-session-invalidation` | fail | pass |
| `search-finds-task` | fail | pass |
| `15-prefill-from-inbox` | fail | **fail** |

Six of the seven regressions were the suite's own state, not the app's.

## Idea

- **A.** Give every flow its own data: a profile of its own, or a database of
  its own, or a sweep that knows every title the suite uses.
- **B.** Keep one shared device, and make the *reading* of a failure honest:
  a flow that fails in a full run has to fail alone before it counts as a
  defect.
- **C.** Do nothing, and let the number speak for itself.

## Decision

**A, in the cheapest form that gives it.**

- The runner wipes the **content** of the active profile before *every* flow —
  the first one included. That is the `reset=content` link the seed activity
  already provides, and it is now the default scope of
  `--maestro-cleanup` (`run` and `off` stay available).
- The narrow scope — remove the rows whose title starts with the run id or
  with `Buy milk` — stays as an option, but it is no longer the default,
  because its boundary is a list of titles and the suite keeps adding titles
  to it.
- A failure in a full run is therefore a statement about the app, not about
  the run before it. The one exception stays visible: a flow that fails
  *alone* on a clean device is still the thing to triage first, because it is
  the cheapest reproduction of the same failure.

**C is not on the table.** A number that changes with the order of the flows
is worse than no number: it looks like a measurement, and it is not one.

**Why the seed activity's warning does not apply.** It says the content wipe
is "not concurrency-safe by construction … never while two suites share an
emulator". The runner now holds a **per-device lock** for the whole run, so
there is no second suite on the device to wipe. The lock is what made this
decision possible; before it, the narrow scope was the only safe one.

**What is still not per-flow.** The wipe is scoped to the **active profile**.
A flow that switches profiles leaves the other profile's rows behind, and
the wipe that follows it happens in the profile that is active at that
moment. Per-profile isolation is the part of idea A that this change does
not deliver, and it is named here rather than left for the reader to
discover.

## Rationale

- **The order-dependence has to be visible, because it is already invisible.**
  Before this, the run reported "N flows failed" and the reader took that as N
  defects. Six of the seven regressions were rows left behind by a flow that
  had already been reported as failed.
- **A list of titles is the wrong boundary.** The narrow scope removes what
  the runner was told about. The suite creates rows by typing them, by
  renaming, and by seeding fixed titles; each new one is a hole in the list,
  and the hole shows up as a failure in a flow that has nothing to do with it.
- **The content wipe is the boundary the app already offers.** It is one link,
  it needs no list, and it removes everything the previous flow created —
  including the rows nobody remembered to name.
- **Reproducing a failure alone is cheap.** It is one flow, one device, one
  minute. It is also the only way to tell a defect from a contaminated run,
  and it needs no product change at all.
- **The empty-state flows are the canary.** `01-empty-inbox`,
  `03-empty-projects` and `04-empty-archive` assert that a list is empty.
  They are the first thing a leak breaks, and they are the cheapest detector
  for a leak that has happened.

## Consequences

- **The full run's failure count is a property of the app again.** Each flow
  starts on a device with no content of its own, so the order of the flows
  stops being a variable of the measurement.
- **A flow that fails alone on a clean device is a defect candidate** — that
  is the shortest path from "red" to "here is the bug".
- **The wipe costs about a second per flow.** The link is sent before every
  flow, so the run is a little slower; the run is still minutes, not hours.
- **Per-profile isolation is still owed.** A flow that switches profiles
  leaves the other profile's rows behind, and until every flow has a profile
  of its own, a green run is a statement about the app *and* about the
  profiles the flows happened to leave active.

## Links

- `scripts/src/emulator/maestro/_fixtures.py` — the sweep and its prefix list
- `scripts/src/emulator/maestro/_cli.py` — where the sweep runs, and where the
  next flow's session is reset
- `Maestro/flows/negative/01-empty-inbox.yaml`,
  `Maestro/flows/projects/03-empty-projects.yaml`,
  `Maestro/flows/archive/04-empty-archive.yaml` — the empty-state flows
- `Maestro/flows/search/02-cyrillic.yaml`, `tasks/09-rename-empty.yaml`,
  `search/07-saved-search.yaml`, `auth/04-signin-with-seeded-data.yaml`,
  `Maestro/flows/system/search-finds-task.yaml` — the fixed titles the sweep
  did not know
- `2026-10-20-a-dead-device-is-written-down-in-the-report.md` — the other way a
  run lies about itself
- `2026-10-21-a-seeded-task-needs-a-due-date-to-be-on-the-today-tab.md` — the
  other way a flow can fail for a reason that is not the app's
