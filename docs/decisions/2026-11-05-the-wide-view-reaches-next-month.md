---
title: "The wide agenda view reaches to the end of next month"
date: 2026-11-05
status: accepted
tags: [agenda, presets, navigation]
---

## Context

`AgendaPresets.All` is the wide agenda view: every dated task, grouped by
relative date bucket. It is not a bottom-bar tab — the three tabs are `Inbox`,
`Today` and `Upcoming` — but it is the seed a new saved view starts from, so
its reach is the reach of every saved view built that way.

Its dated sections ended at `RelativeBucket.ThisMonth`, which was also its
terminal catch-all. A task due later in the month landed in "This Month"; a
task due in a *later* month matched no section of the view at all and was
therefore invisible in it, and in every saved view seeded from it.

The bucket enum already carried a `RelativeBucket.NextMonth` bucket, with its
range, its label and its own test — but no preset used it, so it was
unreachable from every view.

The bottom bar's own horizon was already decided: the three tabs are disjoint
and reach to next week (ADR `2026-10-31-the-agenda-tabs-are-disjoint`). That
record stated, as a fact, that a task dated further out than next week is in
the Plans timeline, in the Calendar and in a saved view seeded from
`AgendaPresets.All` — but it did not say whether the wide view is *meant* to
stop there. A fact in a neighbouring record is not a decision about this one.

## Idea

Two readings were on the table:

1. **The wide view reaches further.** "Next Month" becomes a section of
   `AgendaPresets.All`, the bucket stops being dead code, and a task due in
   the next month is in the view — and in every saved view seeded from it.
2. **The wide view keeps its reach, and the reach is written down.** The
   "Next Month" bucket is removed, and the record says in its own words that
   the wide view stops at the end of the current month.

The first was chosen.

## Decision

**The wide agenda view reaches to the end of next month.**

`AgendaPresets.All` holds a "Next Month" section (`RelativeBucket.NextMonth`)
after "This Month" and before "No Date". A task due in the next month is in
exactly one section of the wide view; a task due after the end of the next
month is in none of them, and is reached through the Calendar, the Plans
timeline, or a saved view with its own date range.

The bottom bar's horizon is unchanged: the three tabs reach to next week and
no further. The two horizons are different on purpose — the bar answers "what
is now or coming", the wide view answers "what is scheduled at all" — and they
now agree in the sense that both are stated: this record for the wide view,
`2026-10-31-the-agenda-tabs-are-disjoint` for the bar.

Every bucket in `RelativeBucket` is now reachable from a preset. The wide view
holds one section per bucket, and `AgendaWideViewReachTest` pins that.

## Rationale

*The bucket existed and was fully implemented.* `RelativeBucket.NextMonth` had
its inclusive range, its label and its test; only the preset that would use it
was missing. Making it a section is a smaller and less risky change than
deleting it — and deleting it would have meant removing a constant from a
`@Serializable` enum whose values live in stored saved-view JSON.

*An unreachable bucket is a bucket nobody can reach.* The wide view is the
view that holds the tasks of every other preset, so a bucket that no preset
uses is a bucket that no view can show. Using the bucket is what makes the
enum honest.

*The view's name was true only by accident.* `All` is the widest view there
is, and it is the seed of every new saved view. A saved view seeded from it
that silently dropped every task due next month was a view that did not show
what it was named after. The reach is still bounded — a task due beyond the
next month is in no section — but the bound is now a stated one.

*Keeping the reach was the weaker option.* It would have left the wide view
narrower than its name, kept a gap in every saved view seeded from it, and
paid for that with the removal of a serialized enum constant. The reach was
worth recording either way; the only question was what it was.

*The month buckets meet at the edges, and the sections still partition the
tasks.* Late in a month the week buckets reach into the next one — on Friday
30 October the current week ends on 1 November — so a next-month task can be
claimed by "This Week" rather than by "Next Month". It still lands in exactly
one section: the week sections discard what they match, and the month buckets
are disjoint.

## Consequences

- The wide agenda view reaches to the end of the next month, and a task due
  beyond that is in no section of it.
- Every saved view seeded from `AgendaPresets.All` reaches the same far.
- The three bottom-bar tabs are unchanged and still reach to next week; the
  wide view is not a tab.
- Every `RelativeBucket` is reachable from a preset — never add a bucket to
  the enum without a section that uses it.
- A task due in the next month appears in exactly one section of the wide
  view, whichever section that is; `AgendaWideViewReachTest` pins it, at the
  month edge as well as in the middle of a month.
- The Calendar and the Plans timeline are untouched — they have their own
  horizons.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaPresets.kt`
- `shared/src/commonTest/kotlin/com/singularity/todo/feature/agenda/domain/AgendaWideViewReachTest.kt`
- `docs/decisions/2026-10-31-the-agenda-tabs-are-disjoint.md` — the bottom
  bar's horizon, which this record sits beside
