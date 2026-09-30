---
title: "AgendaPresets: every narrow bucket section needs discard=true"
date: 2026-09-30
status: accepted
tags: [agenda, domain-logic, agenda-presets, lazycolumn]
---

# AgendaPresets: every narrow bucket section needs `discard=true`

## Context

`AgendaDefinition.Section` has a `discard: Boolean` flag. When `true`, tasks matched by
this section's selector are removed from subsequent sections in the same definition.
This is the intended mechanism for exclusive bucket assignment: a task due "tomorrow"
should appear *only* in the "Tomorrow" section, not also in "This Week" or "This Month"
which have broader range selectors.

`LazyColumn` requires unique item keys. When the same task ID is rendered in two sections
of the same list, the second render crashes: `IllegalArgumentException: Key "tomorrow-task"
was already used`.

## Idea

The Inbox preset had no `discard = true` on any of its narrow bucket sections
(Today, Yesterday, Tomorrow, This Week, Next Week). A task due tomorrow matched:
- "Today" section (bucket: `RelativeBucket.Today` — false for tomorrow, actually fine)
- "Tomorrow" section (bucket: `RelativeBucket.Tomorrow` — true)
- "This Month" section (range: `RelativeBucket.ThisMonth` — true for tomorrow)

With item key = `task.id.value` alone, `LazyColumn` received the same key twice and crashed.
A task due today would appear in both "Today" and "This Week" sections.

The bug was silent in production because: (a) most tasks are undated and never hit this
overlap, (b) the sectioned list renders without keying on task ID internally.

## Decision

All overlapping bucket sections in `AgendaPresets.Inbox` and `AgendaPresets.Upcoming` now
carry `discard = true`:

```kotlin
val Inbox: AgendaDefinition = agenda("Inbox") {
    section("Overdue",   Selector.DateBucket(RelativeBucket.Overdue),   order = -1, discard = true)
    section("Today",     Selector.DateBucket(RelativeBucket.Today),     order =  0, discard = true)
    section("Yesterday", Selector.DateBucket(RelativeBucket.Yesterday),  order =  1, discard = true)
    section("Tomorrow",  Selector.DateBucket(RelativeBucket.Tomorrow),    order =  2, discard = true)
    section("This Week", Selector.DateBucket(RelativeBucket.ThisWeek),    order =  3, discard = true)
    section("Next Week", Selector.DateBucket(RelativeBucket.NextWeek),   order =  4, discard = true)
    section("This Month",Selector.DateBucket(RelativeBucket.ThisMonth),   order =  5)
    section("No Date",   Selector.DateBucket(RelativeBucket.NoDate),      order =  6)
}
```

The same fix applied to `Upcoming` (which also had a Tomorrow section without `discard`).

## Defensive depth

Item keys in `AgendaContent.kt` were changed from `{it.task.id.value}` to
`{"${section.name}/${it.task.id.value}"}`. This degrades duplicate-key misconfiguration
from a crash into a visually duplicate row, which is recoverable and easier to debug.

## Rationale

A task can only occupy one bucket in a well-formed agenda. The `discard` flag is the
explicit mechanism to enforce this. Leaving it `false` on overlapping buckets is a latent
bug that only surfaces when a task's due date happens to fall in multiple selector ranges —
which is common for near-future dates (tomorrow ∈ {Tomorrow, This Week, This Month}).

## Consequences

- No LazyColumn crash for tasks in the near-future window.
- Slightly altered task distribution for tasks due in the next two weeks (they appear in
  the narrowest applicable bucket only, not all matching broader buckets).
- This is the correct semantic for bucket-based agenda sections.
- The `AgendaTabDefinitionFlowTest` regression suite now covers three cases: undated task
  in Inbox No Date, tomorrow task in Inbox Tomorrow, Upcoming without No Date section.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaPresets.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/model/AgendaDefinition.kt` (`Section.discard` semantics)
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/AgendaContent.kt` (defensive key change)
- Regression test: `desktopApp/src/jvmTest/kotlin/com/singularity/todo/feature/flows/tasks/AgendaTabDefinitionFlowTest.kt`
