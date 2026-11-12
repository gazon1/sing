---
title: "Upcoming owns the whole future, grouped by date"
date: 2026-11-11
status: accepted
supersedes: 2026-11-05-the-agenda-tabs-cover-a-horizon
tags: [agenda, navigation, presets]
---

# Upcoming owns the whole future, grouped by date

## Context

The three bottom-bar agenda tabs were a partition with a hole. Inbox held the
undated tasks, Today held the overdue and today's tasks, and Upcoming held
tomorrow, the rest of this week and next week. A task due three weeks out was in
no tab; the answer was a separate **Plans** destination whose timeline showed
the future as periods, plus the Calendar and a saved view.

The horizon was a stated decision (#1949, ADR
`2026-11-05-the-agenda-tabs-cover-a-horizon`), and it was the decision rather
than the gap that made the hole acceptable. The product has since reversed it:
Issue #2828 makes Upcoming the complete chronological feed of future dated tasks and
retires the Plans destination. The reasons are about the user's mental model,
not about code size: two chronological future lists under two navigation
surfaces was two places to look, and the bottom-bar one was the one people
actually open.

## Idea

Two ways to get the timeline into Upcoming:

1. **Keep the Plans screen and reach it from Upcoming.** One destination stays,
   two rendering paths for one list — the duplicate surface the issue exists to
   remove.
2. **Teach the agenda's evaluator to group a section by due date, and make
   Upcoming one such section.** One screen, one renderer, one row vocabulary;
   Plans' timeline becomes a rendering mode of the agenda rather than a feature.

The second was chosen.

## Decision

**Upcoming is one section over the open-ended future bucket, rendered as one
section per period of the profile's chosen grouping.**

- `RelativeBucket.Future` is the bucket from tomorrow onward. It has no
  one-week or one-month edge; the Today tab owns today, and the bucket starts at
  the first day Today does not own.
- `Section.groupBy` tells the evaluator to split the section's matched tasks into
  one rendered section per period: a day, a Monday–Sunday week or a calendar
  month. The grouping is the profile's choice, stored per profile and changed
  from a day/week/month control on the screen. Only periods that carry a task
  become headers — a chronological feed, not a calendar page with an empty header
  per day, week or month.
- There is no period count. The old 30 days / 12 weeks / 12 months lists are not
  restored: the periods come from the tasks that are there, so a task due in
  three years still has its header at every grouping.
- Each generated section carries its own `SectionPrefill` with the first future
  day of the period, so the header's "+" creates a task due in that period and
  never in the past.
- The completed work is out of the feed. Upcoming is the work still ahead; the
  Plans timeline excluded completed tasks for the same reason, and the agenda's
  Today tab keeps owning the completed tasks due today.
- The grouping field lives on `Section`, not on `AgendaDefinition`. A saved view
  stores its sections and rebuilds the definition from them
  (`SavedAgendaViewModel`, `decodeSavedSections`); a definition-level flag would
  be dropped the moment the user saved the tab as a view.
- The Plans destination is removed from the app menu, both platforms' entry
  providers, the FAB resolver and `ScreenFamily`. The `feature/plans` screen,
  its ViewModel and its settings are deleted, not left unreachable. The grouping
  preference is re-created under the agenda's own namespace, keyed by the
  profile-scoped user id.
- **`AgendaPresets.All` and the saved views seeded from it keep their bounded
  reach** (to the end of next month). The two surfaces answer different
  questions and their reaches are decided separately; widening the wide view is
  #2021's decision.

## Rationale

*Why not a second timeline renderer.* The agenda already computes the row's
markings, project, series count, selection and undo state. A second renderer
would either rebuild those or drift from them. Grouping the evaluated rows
reuses all of it and keeps one task row on screen.

*Why the flag is per section.* Saved views are the constraint that settles it.
The definition is rebuilt from `sections` alone, so a flag stored anywhere else
silently disappears on save. A per-section flag also lets a future saved view mix
a date-grouped section with ordinary bucket sections.

*Why only periods with tasks.* Rendering a header per calendar day, week or
month makes the tab useless exactly where it matters — between two distant
plans. The grouper takes its periods from the data, so the tab never renders an
empty one.

*Why the grouping came back.* The day/week/month switch was the one Plans
capability whose loss users would notice on a long horizon: a year of tasks as
365 day headers is a wall, and a month header is the shape a planner wants. What
was wrong was the old horizon (30/12/12), not the switch. The switch now chooses
a projection of the complete future, not a slice of it.

*Why completed work is out.* A future feed is a plan of work still ahead. A
completed task is no longer ahead, and leaving it in inflates the feed and
counts it in every header. Today keeps showing what was completed today, the
archive keeps the rest, and search and saved views still reach it.

*Why the open-ended bucket is a bucket.* The selector vocabulary already speaks
in relative buckets resolved against `today`, and a preset is pure data. A date
range would need the evaluation date at definition time and would go stale at
midnight; a predicate written as `Not(Today, Overdue, NoDate)` would make the
feed's lower bound a consequence of two other rules, and the section would leak
past work again the day one of them changed. `Future` states the bound once.

*Why retire rather than hide.* A menu entry that opens a screen nobody meant to
keep is the "feature is wired but dead" defect with a navigation path. Deleting
the destination, the screen and its tests is the honest version.

## Consequences

- The Upcoming tab reaches as far as the data does. A task due next month or next
  year is in the same tab as tomorrow's, under its own date header.
- The bottom-bar tabs stay a partition: Inbox owns undated, Today owns overdue
  and today, Upcoming owns tomorrow onward.
- `AgendaPresets.All` is no longer the widest view — it is the widest *bucketed*
  view. Its reach and the seeds of its saved views are unchanged; the docs and
  tests no longer claim it holds every tab's tasks.
- Tab vocabulary: the Upcoming tab answers "the unfinished tasks due after
  today, deferred ones included"; `TaskFilter.Upcoming` and the AI's `upcoming`
  selection answer "due after today" with the opposite choices on `someday` and
  completion. `TaskVocabulary` now declares that divergence instead of hiding it.
- The Plans timeline scenarios (`PLANS-01`…`PLANS-10`) are deprecated and kept as
  history; the surviving behaviour is carried by `AGENDA-09` (day grouping and
  the Today exclusion) and `AGENDA-10` (chronological order and no empty period).
- The old plans granularity preference stays in the settings file of existing
  installs and is read by nothing. The new choice is stored under
  `agenda.upcoming_grouping.<profile>`, so it survives leaving the tab and is
  separate for every profile.
- The Calendar is untouched and remains the calendar-oriented way to browse
  dates.

## Links

- Issue #2828 — the product decision this implements.
- `2026-11-05-the-agenda-tabs-cover-a-horizon.md` — the horizon decision this
  supersedes.
- `2026-10-31-the-agenda-tabs-are-disjoint.md` — the partition, which still
  holds with a wider Upcoming.
- `2026-11-05-the-wide-view-reaches-next-month.md` — the wide view's own reach,
  deliberately left alone.
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/AgendaPresets.kt`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/domain/logic/DateSectionGrouper.kt`
