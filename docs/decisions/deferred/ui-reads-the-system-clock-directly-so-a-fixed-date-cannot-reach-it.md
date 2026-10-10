---
title: "Ui Reads The System Clock Directly So A Fixed Date Cannot Reach It"
date: 2000-01-01
status: CLOSED
tags: ["deferred"]
---

**Status: CLOSED — tracked GitHub issue is closed****

**Tracked as:** [#91](https://github.com/gazon1/sing/issues/91)

**Found in:** 2026-10-04, while adding a `clock` parameter to the desktop test
harness (`runDesktopAppTest`) — the fix the backlog had asked for since MR-0.

**What was added:** `runDesktopAppTest(clock = …)` binds a `FakeClock` as the
last Koin module, so it wins over `coreModule()`'s `single<Clock> { Clock.System }`.
That part works and is used by `AgendaBadgePolicyFlowTest.overdue_task_shows_overdue_badge`.

**What it cannot reach, which is the actual finding:** most of the UI does not
read the injected `Clock` at all. `todayInSystemZone()` is a top-level function
in `core/platform/Clock.kt` that calls the system clock directly, and it is what
`CalendarContent.kt:49`, `CalendarScreen.kt`, `CalendarPreview.kt` and others
call. `AgendaTabDefinitionFlowTest` and `CalendarFlowTest` use it in the test
body too.

The first attempt at closing this applied the harness clock to
`CalendarFlowTest` and failed with
`Condition (some node with testTag 'calendar_day_2026_09_15' is on screen) still
not satisfied` — the app rendered October (the host's real month) because the
injected clock never reached it. That test was reverted; the harness parameter
stays, because it does work for the VMs that take a `Clock` by injection.

**Why this matters more than the parameter:** the `NoDirectClockSystem` detekt
rule exists to keep production code off the system clock, and `todayInSystemZone`
is the sanctioned escape hatch — which means the escape hatch is exactly where
the untestable UI lives. A `Clock` in the graph is not the same as a `Clock` in
the composition.

**Do this first:** thread an injected `Clock` (or the `LocalDate` derived from
it) into `CalendarContent` / `CalendarScreen` the way `today` is already a
parameter there — it is a `val today: LocalDate = todayInSystemZone()` default,
so the plumbing exists and only the default is wrong. Then `CalendarFlowTest`
can pin a date like every other flow test. Until then, any UI assertion that
depends on "now" is a test that reports the calendar.

**Scope, measured 2026-10-05** (the plan that produced this entry called the
class "wider than believed"; these are the counts, so the next attempt starts
from facts rather than from the suspicion):

- 17 call sites of `todayInSystemZone()` across 10 files under `commonMain`:
  `feature/agenda/domain/logic/RelativeBucket.kt`,
  `feature/calendar/CalendarPreview.kt`,
  `feature/calendar/presentation/screen/{CalendarContent,CalendarScreen}.kt`,
  `feature/nav/AppDestination.kt`, `feature/search/query/SearchQueryResolver.kt`,
  `feature/tasks/domain/usecase/CreateTaskFromDraft.kt`,
  `shell/FabActionResolver.kt`, `core/observability/RoomUsageRecorder.kt`,
  and `core/platform/Clock.kt` itself.
- 17 direct `Clock.System.now()` calls under `feature/**`.

Two of those reach the core of what a scenario matrix would assert:
`CreateTaskFromDraft` (creating a task with `due = today`) and
`SearchQueryResolver` (searching by date ranges around today). Neither is
deterministic today, so a `TASK-*` or `SEARCH-01` scenario written against
either would be non-deterministic by construction — which is why the fix is a
class-level injection plus a rule, not a point fix in the calendar.

---
