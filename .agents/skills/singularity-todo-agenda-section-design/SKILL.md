---
name: singularity-todo-agenda-section-design
description: Agenda section design — bucket selectors, discard semantics, and LazyColumn key safety. Use when adding or modifying AgendaPresets sections, when a task appears in multiple sections, or when LazyColumn throws IllegalArgumentException on duplicate keys.
---

# Agenda Section Design

## How tasks are assigned to sections

Every `Section` has a `selector: Selector` and an optional `discard: Boolean`.

`AgendaEvaluator` walks sections in order and collects matching tasks. If `discard` is
`false` (the default), matched tasks are **not removed** — a later section with a
broader selector can also match the same task.

If `discard` is `true`, matched tasks are removed from the pool so subsequent
sections never see them.

## The `discard` flag is required for overlapping buckets

"Tomorrow" (bucket: `RelativeBucket.Tomorrow`) overlaps with:

- "Today" — no overlap (bucket is exclusive)
- "This Week" — **overlaps**: tomorrow is always within this week
- "This Month" — **overlaps**: tomorrow is always within this month

If "Tomorrow" has `discard = false`, a task due tomorrow appears in both the
"Tomorrow" section and the "This Month" section. With `LazyColumn.items(key = { it.task.id.value })`,
the second occurrence crashes: `IllegalArgumentException: Key "task-id" was already used`.

**Rule:** Every bucket that can be contained by a later range bucket MUST have
`discard = true`. Conversely, a range bucket that contains all earlier buckets
must NOT have `discard` (it is the terminal collector).

## Which sections need `discard` in the All preset

```kotlin
val All = agenda("All") {
    section("Overdue",   RelativeBucket.Overdue,   discard = true)  // contained by nothing
    section("Today",     RelativeBucket.Today,     discard = true)  // tomorrow ∉ today
    section("Yesterday", RelativeBucket.Yesterday,   discard = true)  // tomorrow ∉ yesterday
    section("Tomorrow",  RelativeBucket.Tomorrow,  discard = true)  // tomorrow ∈ ThisWeek, ThisMonth
    section("This Week", RelativeBucket.ThisWeek,   discard = true)  // tomorrow ∈ ThisMonth
    section("Next Week", RelativeBucket.NextWeek,   discard = true)  // next-week ∈ ThisMonth
    section("This Month",RelativeBucket.ThisMonth,  discard = false) // the months are disjoint
    section("Next Month",RelativeBucket.NextMonth, discard = false) // terminal dated bucket
    section("No Date",   RelativeBucket.NoDate,    discard = false) // terminal
}
```

The wide view reaches to the end of next month — "Next Month" is its last dated
section, and a task due after that is in none of them. That reach is decided in
`docs/decisions/2026-11-05-the-wide-view-reaches-next-month.md`.

## The three tabs are disjoint

The bottom bar shows three agenda tabs, and a task belongs to exactly one of them:

| Tab | Preset | Content |
|---|---|---|
| Inbox | `AgendaPresets.Inbox` | No Date |
| Today | `AgendaPresets.Today` | Overdue, Today |
| Upcoming | `AgendaPresets.Upcoming` | tomorrow onward, one section per chosen period |

`AgendaPresets.All` above is **not** a tab — it holds every bounded bucket, and
it is the seed a new saved view starts from. Its reach still stops at the end of
next month (ADR `2026-11-05-the-wide-view-reaches-next-month`).

Upcoming is the complete future feed: it has no far edge. Its single section
holds `DateBucket(RelativeBucket.Future)` minus `Selector.Completed` and sets
`groupBy = DateGrouping.Day`. The screen passes the profile's chosen grouping
(`Day`, `Week` or `Month`) to the evaluator, and the evaluator emits one section
per period that carries a task, in chronological order. A period with no task
has no header, so the tab is a timeline, not a calendar page full of empty days.

There is no period count. The old Plans limits (30 days / 12 weeks / 12 months)
must not come back: the periods come from the tasks, so a task due in three
years still has its header at every grouping.

Each generated section carries its own `SectionPrefill` with the first future
day of the period, which is what makes its "+" create a task in that period and
never in the past.

The grouping lives on `Section`, not on `AgendaDefinition`: a saved view stores
its sections and rebuilds the definition from them, so a flag on the definition
would be lost on save. The chosen value is stored per profile
(`DateGroupingSettingsRepository`) and the control is drawn when the loaded
state has a non-null `dateGrouping`.

Completed tasks are out of the feed: Upcoming is work still ahead. Today keeps
the tasks completed today, and search, the archive and saved views keep the
rest.

See ADR `2026-11-11-upcoming-owns-the-whole-future`.

On a working day with work hours configured, the Today tab keeps these two
sections and adds the user's work blocks between them (Overdue, the blocks,
then the rest of today), so the partition holds for that configuration too —
see ADR `2026-11-05-the-work-day-plan-sits-inside-the-today-tab`.

Two rules keep the tabs apart:

- **A tab never repeats another's range.** Upcoming starts tomorrow because
  Today owns the overdue and today's tasks. It used to open with "Overdue" and
  "Today" as well, which made it a superset of the Today tab; with one task in
  the database all three tabs rendered the same screen.
- **The future bucket is a strict lower bound.** `RelativeBucket.Future` matches
  `dueDate > today`; do not express it as `Not(Today, Overdue, NoDate)`. That
  makes the lower bound depend on the overdue rule, and the feed leaks past work
  the day that rule changes.
- **Completion is excluded on top of the date.** The section is
  `AllOf(Future, Not(Completed))`; the `Future` bucket alone still matches a
  completed future task.

`AgendaTabDisjointnessTest` (commonTest) pins the contract: no two tabs
declare the same section, and every task shows up in exactly one of them.

## Bucket vs range: what overlaps with what

| Bucket | Overlaps with |
|---|---|
| `Yesterday` | Nothing narrow — `Today` excludes it |
| `Today` | Nothing narrow |
| `Tomorrow` | `ThisWeek`, `ThisMonth` |
| `ThisWeek` | `ThisMonth` — and `NextMonth` at the month edge, when the week runs into it |
| `NextWeek` | `ThisMonth` — and `NextMonth` at the month edge |
| `ThisMonth` | Nothing broad — the months are disjoint |
| `NextMonth` | Nothing broad |
| `Future` | Everything after today — it is the open-ended bucket, not a range |
| `NoDate` | Nothing — null never overlaps with any date |

## Defensive item keys

Use `"{section.name}/{task.id.value}"` as the `LazyColumn` item key, not
`task.id.value` alone. A misconfigured preset (missing `discard`) will then
render duplicate-looking rows instead of crashing.

## Testing a new section

1. Add a task due on the section's boundary date
2. Open the tab that owns the section and verify the task appears in **exactly one** section
3. Check the other two tabs: the task must not show up there either — the tabs
   are disjoint (`AgendaTabDisjointnessTest`)

## Related

- `docs/decisions/2026-09-30-agenda-section-discard-missing.md`
- `docs/decisions/2026-11-05-the-wide-view-reaches-next-month.md` — the wide view's reach
- `AgendaPresets.kt` — the preset definitions
- `AgendaEvaluator.kt` — the evaluation loop
