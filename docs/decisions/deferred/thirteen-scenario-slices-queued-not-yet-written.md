---
title: "Thirteen Scenario Slices Queued Not Yet Written"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** [#170](https://github.com/gazon1/sing/issues/170)

**Found in:** the plan `Ремонт измеримости и сценарии покрытия` (срезы 2-14), and
then re-confirmed by code on 2026-10-05 once the traceability machinery landed.

**Situation:** the scenario layer exists and holds exactly one scenario,
`TASK-REC-01`, delivered as the pilot. The other thirteen are written nowhere — not
here, not in an issue, not in an OpenSpec change. The matrix says
"1 scenarios · 2/2 claimed cells automated · 0 holes", which is true and says almost
nothing: one scenario is not a matrix.

**Measured 2026-10-05, per area, so the queue is facts rather than suspicion:**

| Area | Test files | Production files | Reachable in UI |
|---|---|---|---|
| `feature/checklist` | **0** | 6 | yes — `ChecklistEditorSheet` via `TaskEditorSheetsHost.kt:140` |
| `feature/timetracking` | **0** | 11 | yes — `TimeTrackingSection` at `TaskDetailViewScreen.kt:218` |
| `feature/statistics` | 1 | 3 | yes, no tag on the chart |

The first two are why slices 2-4 come first rather than being an arbitrary order: they
are areas with **zero tests in any layer** that a user can reach. Filling the matrix
from the areas that already have tests would produce a full matrix that mostly means
"what was already covered is now also a scenario" — the same illusion the class-count
floor was created to remove.

**Deliberately not slices.** Cloud sync has no host screen (ADR
`2026-09-29-sync-config-screen-has-no-host`), bulk task operations have no multi-select
(#36), the 7 tables outside the backup payload are #77, and `Regexp`/`DateRange` agenda
templates are JSON-only. Each is a gap with its own issue, not a scenario to write, and
they are reported as `unreachable` with a link to an OPEN record — an audit that treats
an honest gap as a failure gets its gaps filled with fiction.

**Try next:** `TASK-SUB-01` first (it guards a bug that actually shipped), then
`TASK-CHK-01` and `TASK-TT-01`. Each slice is one PR carrying its spec, its test, and
the seed or tag *it* needs — not a pre-paid batch of tags for every reachable control,
because some will turn out unnecessary.

---

### Which of them a Maestro flow could close: none, measured 2026-10-07

Asked to tag flows so the matrix would stop standing at `holes: 32 / dark_scenarios: 16`,
the answer was **zero tags**, because zero could be placed honestly. The 32 holes
decompose as follows, and every branch is a structural reason rather than an
unfinished job:

| Holes | Why no flow can close them |
|---|---|
| 16 (8 SYNC specs × 2 tiers) | the sync specs are *defined* by a second device; `sync/01-offline-create-survives-reconnect.yaml` is single-device and does not verify any of them |
| 6 of those | already counted as `unreachable` in the ratchet — same reason, recorded |
| 6 AUTH specs | **there is no auth flow in `Maestro/flows/` at all**; six specs, zero candidate flows |
| `TASK-CHECK-01` | its spec claims `targets: [desktop]`, so an Android flow structurally cannot close it — it needs a JVM Compose test |
| `CAL-FILT-01` | its `expected` records that the feature **does not exist**: "Nothing happens, and nothing ever did: there is no `CalendarFilterPanel` in the tree". A carrier would assert absence, which is a different claim from the one the spec makes |
| `TASK-TIME-01` (2 cells) | the UI exists (`feature/timetracking/presentation/components/TimeTrackingSection.kt`) but no flow drives it; the `pomodoro/*` flows are a different feature. Its `○` is not a tagging gap, it is a missing flow |

**Already ruled out:** not an argument that tagging is wrong in general. `TASK-REC-01`
is tagged and legitimately so — one flow, one honest carrier.

**Why this matters more than the number.** The ratchet's own note for `TASK-TIME-01`
records the failure mode this avoids: "a spec narrowed to match a bug is neither honest
nor a coverage claim". A tag on a flow that does not exercise the scenario would make
`holes` fall while coverage stays exactly where it was — the matrix would stop being a
measurement and become a decoration. Same defect class as a gate that reports green
without running, and worse, because the number would look like progress.

**Order of work, given the table above:**

1. **Write flows, then tag them.** The order is the whole point. A new flow for
   `TASK-TIME-01` that starts and stops a timer, verified on a device, earns its tag;
   a tag on `pomodoro/02-start-focus-task.yaml` does not.
2. **`CAL-FILT-01` first, because it needs no flow.** Its spec says the feature does
   not exist. Either delete the spec or implement the filter — both are smaller than a
   test, and both stop the scenario layer carrying a permanent fiction.
3. **`TASK-CHECK-01` is a desktop carrier problem**, not a Maestro problem: its spec
   claims `[desktop]`, and the checklist section is JVM-testable today.
4. **The AUTH specs need a whole flow family** — six specs, zero flows, which is the
   largest single block of uncovered product behaviour in the matrix.
