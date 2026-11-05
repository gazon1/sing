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

| Tab | Preset | Sections |
|---|---|---|
| Inbox | `AgendaPresets.Inbox` | No Date |
| Today | `AgendaPresets.Today` | Overdue, Today |
| Upcoming | `AgendaPresets.Upcoming` | Tomorrow, This Week, Next Week |

`AgendaPresets.All` above is **not** a tab — it holds every bucket, and it is
the seed a new saved view starts from.

Two rules keep the tabs apart:

- **A tab never repeats another's bucket.** `Upcoming` used to open with
  "Overdue" and "Today", which made it a superset of the Today tab; with one
  task in the database all three tabs rendered the same screen.
- **A bucket that spans another tab's days is narrowed.** `ThisWeek` runs from
  Monday to Sunday, so it contains today and the days already gone — the days
  the Today tab owns. The Upcoming preset's "This Week" section is therefore
  `DateBucket(ThisWeek)` minus `Today` and `Overdue`:

```kotlin
section(
    "this_week",
    "This Week",
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
    ),
    order = 1,
)
```

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
