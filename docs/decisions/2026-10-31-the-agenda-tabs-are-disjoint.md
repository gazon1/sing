---
status: accepted
date: 2026-10-31
---

> **Superseded in part (2026-11-04):** the `TaskFilter.Inbox` filter and the AI tool's
> `inbox` selection are now `TaskFilter.NotDeferred` and `active_not_deferred`. The tab
> partition and the Inbox tab described here are unchanged. See
> [2026-11-04-inbox-names-the-unscheduled-tab.md](2026-11-04-inbox-names-the-unscheduled-tab.md).
>
> **Superseded in part (2026-11-06):** the `inbox` alias is gone — the wire no longer
> answers to that word, and a caller that sends it is refused rather than given a
> wider answer than the one it asked for. See
> [2026-11-06-one-name-one-question.md](2026-11-06-one-name-one-question.md).

# The three agenda tabs are disjoint

## Context

The bottom bar shows three agenda tabs: Inbox, Today and Upcoming. For a
long time they were nested subsets of each other:

- `Today` = Overdue + Today
- `Upcoming` = Overdue + Today + Tomorrow + This Week + Next Week
- `Inbox` = all of the above plus Yesterday, This Month and No Date

With one task in the database all three tabs rendered the identical screen —
"Overdue - 1 / Review design doc" on every one of them. The tabs were three
names for one view, and the name was wrong too: "Inbox" promises the unsorted
tasks, but it held the dated ones, while the genuinely undated tasks sat in a
"No Date" section *inside* it.

The cause is a design mistake, not a bug in one preset: each preset was
written as "the sections I want to see", and nothing said that the three
presets must not want the same section. The overlap was invisible in the
presets and obvious on the screen.

## Idea

Give the tabs a partition of the tasks instead of three overlapping views.
Each tab owns a set of date buckets, no bucket is owned twice, and a task
belongs to exactly one tab.

## Decision

The three tabs are disjoint, and the presets are:

| Tab | Preset | Sections |
|---|---|---|
| Inbox | `AgendaPresets.Inbox` | No Date |
| Today | `AgendaPresets.Today` | Overdue, Today |
| Upcoming | `AgendaPresets.Upcoming` | Tomorrow, This Week, Next Week |

The wide view — every bucket, the old Inbox preset — is kept as
`AgendaPresets.All`. It is not a tab; it is the seed a new saved view starts
from, and the full list the Plans timeline shows.

`RelativeBucket.ThisWeek` runs from Monday to Sunday, so it contains today
and the days already gone — the days the Today tab owns. The Upcoming
preset's "This Week" section is therefore the week bucket minus `Today` and
`Overdue`:

```kotlin
Selector.AllOf(
    listOf(
        Selector.DateBucket(RelativeBucket.ThisWeek),
        Selector.Not(
            Selector.AnyOf(
                listOf(
                    Selector.DateBucket(RelativeBucket.Today),
                    Selector.DateBucket(RelativeBucket.Overdue),
                ),
            ),
        ),
    ),
)
```

`AgendaTabDisjointnessTest` (commonTest) pins the contract: no two tabs
declare the same section, and a task — overdue, due today, due tomorrow, due
later this week, due next week, undated — appears in exactly one of them.

## Rationale

The alternative — dropping the third tab and keeping the full agenda
reachable from the menu — was not chosen because the tabs then stop being a
partition: an undated task would appear in no tab at all, and the tab bar
would lose the answer to "what have I not scheduled yet?".

The `Not` selector is used rather than a new date range for two reasons. The
buckets are resolved against the evaluation's `today`, so the sections stay
correct at midnight without the definition being rebuilt; and the range
buckets that could express "the rest of this week" (`DateRange`) need the
date at definition time, which would make the preset a function of the
clock rather than pure data — the property every preset is supposed to have.

## Consequences

- Positive: the three tabs answer three different questions — "what is not
  dated yet", "what is now or late", "what is coming".
- Positive: the tab named "Inbox" holds the unsorted tasks, which is what
  the name promises.
- Negative: a task dated further out than next week is in no agenda tab. It
  is in the Plans timeline, in the Calendar, and in a saved view seeded from
  `AgendaPresets.All` — but not in the bottom bar.
- Negative: the `TaskFilter.NotDeferred` filter (every live task that is not
  deferred, used by the pomodoro picker and by the AI tool's `active_not_deferred`
  selection) is wider than the Inbox tab — it holds dated and undated tasks, and
  finished ones. The two answer different questions, and the filter no longer borrows
  the tab's name. (This bullet named the filter `TaskFilter.Inbox` and the selection
  `inbox` when the partition was written; ADR
  `2026-11-04-inbox-names-the-unscheduled-tab` renamed both, and the repository read
  now matches the name.)
- Superseded (2026-11-05): the work-day preset no longer replaces the Today
  tab's sections. The plan is layered inside the tab, which keeps owning
  Overdue and Today on a working day — so the partition above holds for every
  configuration, not only for the date-based presets. See ADR
  `2026-11-05-the-work-day-plan-sits-inside-the-today-tab`.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaPresets.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/domain/AgendaTabDisjointnessTest.kt`
- `.agents/skills/singularity-todo-agenda-section-design/SKILL.md`
- `docs/decisions/2026-11-05-the-wide-view-reaches-next-month.md` — the wide
  view's own reach, which reaches further than the bar's
