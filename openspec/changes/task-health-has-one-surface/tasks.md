# Tasks — task-health-has-one-surface

## Phase 0 — the audit (#2977)

- [x] **Trace the Statistics Health tab and its drill-downs.** The tab counts with
      `summarizeTaskHealthFromSelectors`; each card opens an agenda route; the
      routes map to `AgendaPresets.byOverdue()`, `byBlocked()` and `byStagnant()`.
- [x] **Trace the task row actions.** Every bucket list is an `AgendaScreen`, so a
      row opens the task, completes it, deletes it and shows its markings — the
      drill-down reaches the task, and the markings say what is wrong with it.
- [x] **Compare the health buckets with #2977's attention candidates.** Overdue,
      blocked and stagnant exist and are reachable. "Due today" is the Today tab.
      "Due soon" has no window anywhere in the model. Project deadline risk is a
      stored date no health surface reads.
- [x] **Write the gap matrix.** Below.

### Gap matrix

| Signal | Existing rule | Existing surface | What is actually missing |
|---|---|---|---|
| Overdue | `TaskComputed.isOverdue` via `Selector.DateBucket(Overdue)` | Today tab, Statistics Overdue card → Overdue preset, `overdue` selection | Nothing — the rule, the count, the list and the AI answer all exist and agree. |
| Blocked | `Blocking.blockedTaskIds` (BLOCKS edges only) via `Selector.Blocked` | Statistics Blocked card → Blocked preset, `blocked` selection, row marking | The card's count and the AI answer were computed by two copies of the precedence. |
| Stagnant | `Selector.Stagnant(window)` | Statistics Stagnant card → Stagnant preset, `stagnant` selection | The card counted with the stored threshold; the preset used the 21-day default. The AI copy read a stored `0` as a zero-day window. |
| Due today | `Selector.DateBucket(Today)` | Today tab (`Today` section) | Nothing. |
| Due soon | **No rule exists** — no window, no constant, no selector | — | A new signal would be a new date calculation, which #2977 forbids. Out of scope. |
| Project deadline risk | `Project.dueDate` is stored and shown on the project screen | Project detail | No health surface consumes it, and deriving task risk from it would be a new model. Out of scope. |
| Why a task is in a bucket | The bucket *is* the reason, computed and then thrown away | Row markings show one fact each | The count states no rule, and row markings are per-fact rather than a primary reason. |

**Conclusion:** the gap is not a missing surface. It is that the surfaces exist,
compute the same thing twice, and one of them disagrees with the card that opened it.
That is what was closed.

## Phase 1 — one classifier

- [x] **shared/**: `HealthRules` owns the precedence; `summarizeTaskHealthFromSelectors`
      classifies through it.
      Test: `TaskHealthTest` (existing, unchanged behaviour) and `HealthClassificationAgreementTest`.
- [x] **shared/**: `stagnationWindowOf` owns what a stored threshold means.
      Test: `the stored staleness setting maps zero to the default and positive numbers to days`.
- [x] **shared/**: the AI tools classify through `HealthRules` instead of a copy.
      Test: `every surface reports the same bucket` — drives both entry points over one fixture.

## Phase 2 — the drill-down counts what the card counted

- [x] **shared/**: the Stagnant route reads the stored threshold, as the Today route
      reads work hours. The decision is `stagnantDefinition(Int)`, a pure function.
      Test: `StagnantDefinitionTest` — a configured 60-day threshold reaches the
      definition, and a 30-day-idle task is selected by the 21-day window and not by
      the 60-day one. Verified to fail against the old `byStagnant()` call.
- [x] **shared/**: the screen waits for the stored value before composing, because
      `koinViewModel` keys the ViewModel by class inside the entry's store — so the
      *first* definition it is given is the one it keeps. Starting from the default and
      swapping later would leave the ViewModel evaluating 21 days under a 60-day card.
      Test: `StagnantDrilldownHonoursThresholdFlowTest` — under 60 days the 30-day task
      is absent, under the 21-day default it is present. Verified to fail with the fix
      reverted. (An earlier version of this test seeded two tasks through two
      `tasks(koin)` calls; `TasksRobot` restarts its id counter per instance, so the
      second seed overwrote the first and the test passed for the wrong reason. It now
      uses one robot and asserts both tasks were stored.)
- [x] **shared/**: reopening the drill-down after a threshold change uses the new value.
      Test: `reopening_the_drill_down_after_a_threshold_change_uses_the_new_value` —
      absent under 60, present under 21, with only a `goBack` between.
- [x] **shared/**: a stored `0` reaches the preset as the default window, not zero days.
      Test: `an unset threshold becomes the default, not a zero-day window`.

### What the lifecycle does and does not cover

- **Loading** — the screen renders nothing until the stored value arrives (sentinel
  `-1`; `0` is a real setting that means "default").
- **Changing the threshold while the screen is open** — the list does not recompute,
  because Koin keys the ViewModel by class and `koinViewModel` does not recreate on a
  parameter change. This is **not reachable through the UI**: the only writer of the
  setting is the Settings screen, which the user must open in place of the drill-down.
  No refresh mechanism was added, because the review's rule is to add one only when the
  existing lifecycle is proven insufficient for a reachable path.
- **Closing and reopening** — re-reads the stored value; pinned by the test above.

## Phase 3 — the count explains itself

- [x] **shared/**: each health card states the rule its count was made with.
      Test: covered by the existing `StatisticsHealthTabFlowTest` card assertions; the
      hint is inside the card node, and no new test tag was added.

## Phase 4 — boundaries

- [x] **shared/**: window boundary (exactly the threshold, one millisecond under),
      due-date boundary (yesterday / today / tomorrow), completion and discard,
      deferred tasks, and the empty list.
      Test: `HealthClassificationAgreementTest`, one test per boundary.

---

**Verification command:**

```bash
./gw :shared:jvmTest --tests "*HealthClassificationAgreementTest*"
./gw :shared:jvmTest --tests "*StagnantDefinitionTest*"
./gw :shared:jvmTest --tests "*TaskHealthTest*" --tests "*TaskReadSurfaceTest*"
```

**Not done here, deliberately:** no new route, screen, enum, table or priority
scale. #2977's "smallest useful surface" order puts improving the existing Health tab
first, and the audit found nothing the existing tab and its three drill-downs cannot
express.
