---
title: "Maestro 2.10.0's schema is the contract, and a flow that cannot be parsed never runs"
date: 2026-10-20
status: accepted
tags: ["architecture", "e2e", "maestro", "tooling"]
---

## Context

Nine of the 94 flows produced **no JUnit report at all**. The runner counted
them as "flows that produced no JUnit report" — honest, and useless: a flow
that never ran cannot say why it did not run.

The reason was in Maestro's stderr, which the runner did not print. Re-running
each of the nine by hand gave the whole story in one line each:

| Flow | What Maestro said |
|---|---|
| `attachments/02-delete`, `attachments/05-renders-correctly` | `Unknown Property: contentDescription` |
| `search/04-filter-description`, `search/07-saved-search` | `Unknown Property: iconDescription` |
| `search/05-filter-pinned` | `Invalid Command: navigateBack` |
| `auth/08-signup-network-error`, `auth/09-signin-network-error` | `Invalid Command: exec` |
| `auth/10-attach-account-validation-error` | `Invalid Command: waitForAnimations` |
| `tasks/subtasks/TASK-SUB-01` | `Unknown Property: absent` |

None of these are app defects. They are flows written against a Maestro that
does not exist here: the installed engine is 2.10.0, and `contentDescription`,
`iconDescription`, `navigateBack`, `waitForAnimations`, `absent` and `exec` are
not in its schema. The two auth flows even documented the requirement — "this
flow requires `exec` access to `adb`" — as if `exec` were a command that
exists.

## Idea

- **A.** Leave the flows alone and let the runner keep reporting "no report".
- **B.** Fix the flows against the schema the installed Maestro actually has.
- **C.** Patch the flows to the newest Maestro's schema.

## Decision

**B** — the flows are written against the Maestro that is installed.

- `contentDescription` / `iconDescription` → `text`, which is what Maestro
  matches an accessibility label by.
- `navigateBack` → `back`.
- `waitForAnimations: <ms>` → `waitForAnimationToEnd: { timeout: <ms> }`.
- `absent:` → `notVisible:`, `assertAbsent:` → `assertNotVisible:`.
- `exec:` → `setAirplaneMode: { value: enabled | disabled }`, a native command
  that does the same thing without a shell.

**C is not on the table.** The installed engine is the one the suite runs on,
and the nightly runs the same image; a flow written against a schema this
engine does not have cannot be run at all.

**The runner now prints stderr when a flow fails.** The reason a flow died
used to be visible only by re-running it by hand — which is how the nine above
took forty minutes to attribute. Maestro puts the reason on stderr and
nothing on stdout, so logging stdout alone printed an empty line and left the
reader with a failure and no cause.

## Rationale

- **A flow that cannot be parsed is invisible.** It produces no report, so
  every count that reads reports — passed, failed, unreported — describes a
  suite that is smaller than it looks. The flows were not failing; they were
  not running.
- **The schema is a fact about the engine, not a preference.** `setAirplaneMode`
  exists in 2.10.0 and does exactly what the `exec` block was hand-rolling
  with `adb shell settings put global airplane_mode_on`.
- **The reason belongs in the log.** A failure whose cause is only reachable
  by re-running it by hand is a failure that will be misattributed again.

## Consequences

- **All 94 flows now run and report.** The suite's denominator is the suite.
- **The `manual` tag no longer hides a parse error.** The two network-error
  flows are tagged `manual` because they need the *real* auth build — the
  stub always succeeds, so they cannot pass in the E2E build. They now fail
  on their assertion instead of failing to parse, which is the honest state:
  the failure says the precondition is not met.
- **A flow that uses a command the engine does not have fails in seconds and
  loudly**, and the reason is in the run's log.
- **`scripts/ci/validate-maestro-selectors.py` still passes**: it checks that
  the ids a flow names exist in the app, and none of these changes touch an
  id.

## Links

- `Maestro/flows/attachments/02-delete.yaml`, `attachments/05-renders-correctly.yaml`
- `Maestro/flows/search/04-filter-description.yaml`, `search/05-filter-pinned.yaml`,
  `search/07-saved-search.yaml`
- `Maestro/flows/auth/08-signup-network-error.yaml`, `auth/09-signin-network-error.yaml`,
  `auth/10-attach-account-validation-error.yaml`
- `Maestro/flows/tasks/subtasks/TASK-SUB-01.yaml`
- `scripts/src/emulator/maestro/_runner.py` — `_log_failure`
- `2026-10-21-a-seeded-task-needs-a-due-date-to-be-on-the-today-tab.md` — the
  other reason the seeded fixtures stayed invisible
