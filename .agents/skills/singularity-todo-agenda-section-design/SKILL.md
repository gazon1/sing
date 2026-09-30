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

## Which sections need `discard` in the Inbox preset

```kotlin
val Inbox = agenda("Inbox") {
    section("Overdue",   RelativeBucket.Overdue,   discard = true)  // contained by nothing
    section("Today",     RelativeBucket.Today,     discard = true)  // tomorrow ∉ today
    section("Yesterday", RelativeBucket.Yesterday,   discard = true)  // tomorrow ∉ yesterday
    section("Tomorrow",  RelativeBucket.Tomorrow,  discard = true)  // tomorrow ∈ ThisWeek, ThisMonth
    section("This Week", RelativeBucket.ThisWeek,   discard = true)  // tomorrow ∈ ThisMonth
    section("Next Week", RelativeBucket.NextWeek,   discard = true)  // next-week ∈ ThisMonth
    section("This Month",RelativeBucket.ThisMonth,  discard = false) // terminal: no range covers it
    section("No Date",   RelativeBucket.NoDate,    discard = false) // terminal
}
```

## Bucket vs range: what overlaps with what

| Bucket | Overlaps with |
|---|---|
| `Yesterday` | Nothing narrow — `Today` excludes it |
| `Today` | Nothing narrow |
| `Tomorrow` | `ThisWeek`, `ThisMonth` |
| `ThisWeek` | `ThisMonth` |
| `NextWeek` | `ThisMonth` |
| `ThisMonth` | Nothing broad |
| `NoDate` | Nothing — null never overlaps with any date |

## Defensive item keys

Use `"{section.name}/{task.id.value}"` as the `LazyColumn` item key, not
`task.id.value` alone. A misconfigured preset (missing `discard`) will then
render duplicate-looking rows instead of crashing.

## Testing a new section

1. Add a task due on the section's boundary date
2. Open Inbox and verify it appears in **exactly one** section
3. Switch to Upcoming (which has different sections) and verify the same task
   appears or doesn't appear as the preset intends

## Related

- `docs/decisions/2026-09-30-agenda-section-discard-missing.md`
- `AgendaPresets.kt` — the preset definitions
- `AgendaEvaluator.kt` — the evaluation loop
