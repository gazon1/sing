---
title: "The timeline is its own feature, and the menu is one menu drawn twice"
date: 2026-10-26
status: accepted
tags: [navigation, agenda, ui, projects]
---

## Context

The Plans tab was a project list. It was reached from the bottom navigation bar, and it opened
`ProjectsRoute.List` — a flat list of projects, sorted, searchable, with the project editor behind
its FAB. The projects themselves are a hierarchy: a project has a parent, and the tree can be
several levels deep. The tab could not show that hierarchy, so it showed the projects flat and the
hierarchy was something you had to enter to see.

The user asked for three things at once. The Plans tab was to stop being the project list and
become a timeline of upcoming work — days in a list, each day holding the tasks due on it, with
the size of the day switchable to a week or a month. The project hierarchy was to move into the
menu, as a tree. And the Calendar, which had a button in the bottom navigation bar, was to leave
that bar and join the menu.

Those three are one change, because the first one is what empties the tab the third one is
leaving.

The pieces the app already had, read against the working tree:

- The agenda's sections are a **fixed, ordered list**, each with one selector, evaluated in order
  by a collector that honours a `discard` flag. The list is what a saved view *is* — it is
  something the user stores, names and edits. It is not a function of the calendar.
- The agenda's row wrapper and its badge vocabulary lived inside the agenda feature, and the badge
  policy depended on nothing but the tasks feature. (Upstream has since replaced the badge with
  the markings and moved the policy into the tasks feature; see the decision below.)
- The task repository can already be asked for a date range. The query excludes trashed tasks and
  does not exclude completed ones. It also cannot answer "is this task blocked?" on its own: the
  blocking rule reads the loaded set, and a prerequisite that is missing from it does not block.
- The bottom sheet has its own private list of sections; the Desktop drawer reads
  `DestinationKind.menuEntries`. The sheet's KDoc claims the two are the same list, and they are
  not.
- A menu entry is an `AppDestination`. A project in a tree is not one.
- `core/tree` already has a depth-first traversal that detects cycles.

The failure mode this change walks into is documented and has been paid for once already: a task
that lands in two sections is rendered twice, and a list keyed on the task id alone crashes with
`Key "task-id" was already used`.

## Idea

Keep one vocabulary for a task row, one menu that both platforms draw, and one timeline that owns
its own grouping — and let the timeline's sections be generated rather than declared.

## Decision

**The timeline is a new feature, `feature/plans`.** It is not a new agenda preset and not a
variation of the agenda's screen. The agenda's screen is built around saved views — a dropdown of
stored definitions, a section editor, a selector composer — and the timeline has none of them. Its
definition also cannot be a fixed list: the sections are a function of today's date, and the
horizon is a function of the granularity.

**The timeline generates its periods and assigns each task to the one that contains it.** The
periods for a granularity are disjoint, so "a task is in exactly one section" is structural rather
than a rule to remember. The renderer still keys a row as the section and the task together, so a
future mistake renders a duplicate row instead of crashing the screen.

**The row vocabulary is the tasks feature's, and both screens read it.** The timeline's row is
`TimelineTask`; it carries the markings — the set of things a task is marked with — that the shared
policy computes. Two copies of that vocabulary would be two vocabularies, and what a user sees on a
row would depend on which screen they happened to be on.

While this decision was being written, the vocabulary was a single badge (`AgendaBadge`, six
cases) owned by the agenda feature, and this change moved it out. The upstream work that landed in
`origin/main` on 2026-10-26 replaced the badge with the markings — a set, and a superset of the
badge's cases — and moved the policy into the tasks feature on the way. The timeline was adapted to
the markings rather than keeping the badge alive: the decision is unchanged, the vocabulary it names
is upstream's, and the timeline asks the same policy the agenda asks.

**The menu grows an entry that is not a destination, and both platforms render the same tree
composable.** The projects tree is not a destination, so the menu model becomes a hierarchy of
kinds rather than a list of destinations. The tree is drawn by one composable, used by the Android
sheet and by the Desktop drawer, so the two menus are the same menu by construction rather than by
review.

**The Calendar leaves `DestinationKind.tabs` and joins `DestinationKind.menuEntries`,** and the
Android sheet's own section list gains the entry with it. The Calendar screen is untouched; only
where it is reached from changes.

**The full project list stays reachable** from the Projects section of the menu. The tree is the
default way in; the list still searches, sorts and manages the closed projects, and moving the
projects out of the tab bar must not cost the user those.

**The timeline shows open work only.** A completed task has left the plan.

## Rationale

Each of these was measured against the working tree rather than chosen for tidiness.

*Why not a preset.* A thirty-day preset is thirty static sections whose labels go stale the next
morning. Its "Today" section would have to be a relative bucket while every other day is a fixed
range — two kinds of section in one list, which is exactly what the discard rules exist to keep
straight. And the granularity switch cannot be expressed at all: the agenda's definition is fixed
when its ViewModel is constructed, and the timeline's changes with the granularity and with the
day.

*Why promote the row model rather than import it.* Importing would have been one line and would
have kept one enum — a copy is what drifts, not an import. But the name would keep saying "Agenda"
on a screen that is not the agenda, and the next screen that wants a task row would make the same
choice again. The promotion touches nine files and changes no behaviour.

*Why one tree composable.* The sheet and the drawer are drawn by different code. A tree
implemented twice is two trees that drift, and "the same menu on both platforms" would become a
thing a reviewer has to notice.

*Why the horizon belongs to the granularity.* The horizon is what "upcoming" means, so it belongs
to the granularity, and it bounds what the timeline *shows*.

*Why the whole task list is read and grouped, rather than a range of it.* The first draft asked
for two ranges — the overdue pool and the horizon — on the reasoning that the timeline selects on
one field. That reasoning was incomplete: whether a task is blocked is not a field of the task but
a relation between tasks, and the relation is read from the loaded set. A range query would show a
task as unblocked that the agenda shows as blocked, and the same task would be free on one screen
and held on another. The horizon therefore decides what is *displayed*, and the whole list is what
is *read*.

*Why the projects tree survives a cycle.* Project hierarchies are edited by hand. A cycle is a user
mistake, and the traversal that is already in `core/tree` turns it into an error instead of a
hang.

## Consequences

- The Plans tab is no longer a way into the projects, so the project list needs a home of its own.
  The change lands in two parts for that reason: the timeline first, with a plain menu entry
  standing in for the list, and the tree replacing that entry in the second part. Each part is a
  coherent app on its own.
- The Plans tab's FAB creates a task rather than a project, with the due date prefilled, and each
  section header carries its own add control that prefills that section's date — today when the
  section holds today, the section's first date otherwise, so the new task lands in the section it
  was added from.
- The agenda's public surface changed — the row and badge types left the agenda feature. The
  change was mechanical and the behaviour was identical; upstream's markings then replaced the
  badge, and the timeline followed them.
- The Calendar is one interaction away instead of one tap away: the menu is a sheet that opens, on
  both platforms.
- A screen that a user opens every day now has a preference of its own, stored per profile in the
  feature's own namespace.
- The timeline's sections are generated rather than declared, so the eighteen scenarios this
  feature owns are declared in the specification and carried by tests that drive the real screen.
  Until those carriers land, the scenarios are holes in the coverage matrix — recorded in the
  ratchet floor with the reason written next to the number.

## Links

- `2026-09-30-agenda-section-discard-missing.md` — the discard semantics, and the crash this
  change's renderer is built not to reproduce.
- `2026-10-04-navigation-policy.md` — how a destination is opened from another, which the
  Calendar's move out of the tab list has to respect.
- `2026-09-15-desktop-menus.md` — the Desktop menu and drawer this change extends.
- The specification and the plan: `specs/016-plans-timeline-projects-menu/spec.md` and
  `specs/016-plans-timeline-projects-menu/plan.md`.
- The skill that records the failure mode: `.agents/skills/singularity-todo-agenda-section-design/SKILL.md`.
