# Design: plans-timeline-and-projects-menu

The rationale for these choices lives in the ADR
(`docs/decisions/2026-10-26-plans-timeline-and-projects-menu.md`). This document records what the
change touches and what it risks.

## Cross-cutting concerns

This change crosses three seams at once: the navigation frame (a destination leaves the tab list),
the menu model (a new kind of entry), and the feature layout (a new module). Those are the reasons
the change is one ADR rather than three.

### The new module

The timeline is a new feature module, not a mode of the agenda feature. The reason is in the ADR:
the agenda's sections are a saved, fixed list, and the timeline's are a function of the calendar.

The module owns:

- the granularity, and the horizon that goes with it
- the periods a granularity produces
- the grouping of tasks into sections
- the screen, its ViewModel and the settings repository that remembers the choice

### The row vocabulary

The vocabulary a row carries — the markings, computed by the shared policy — belongs to the tasks
feature, and both screens read it. The timeline's row is `TimelineTask`, which carries the markings
the agenda shows. This change was written against a single badge owned by the agenda feature; the
upstream work that landed in `origin/main` on 2026-10-26 replaced that badge with the markings and
moved the policy into the tasks feature, and the timeline was adapted to it. The point did not
change: two screens cannot end up with two vocabularies.

### The menu

The menu model becomes a hierarchy of entry kinds rather than a list of destinations. A project in
a tree is not a destination, and turning projects into destinations would put project ids into the
navigation key space, where the destination set is a closed, serialisable type.

The tree itself is drawn by one composable, used by both shells. That is what makes "the same menu
on both platforms" structural rather than a review item.

## Data flow

```text
granularity (profile settings) ─┐
                                ├─→ periods ─→ sections ─→ screen
today (clock) ──────────────────┘      ▲
                                       │
tasks (repository, whole list) ────────┘
```

The whole task list is read, and the horizon — which belongs to the granularity — decides what of
it the sections hold. The horizon was first written as two range queries (the overdue pool and the
horizon), and that was wrong: a partial set makes `Blocking.blockedTaskIds` treat a prerequisite
that is missing from it as *not blocking*, so a task would be blocked in the agenda and free on the
timeline. The grouper is a pure function and is where the "one task, one section" property is
established.

## What could go wrong

| Risk | How it is held |
|---|---|
| A task rendered twice | The periods are disjoint, so it cannot happen; the row key is the section and the task together, so a mistake renders a duplicate rather than crashing |
| The two menus drifting apart | One tree composable, used by both shells |
| A cycle in the project hierarchy hanging the menu | The tree is built with the traversal that detects cycles |
| The tab bar losing an entry by accident | The scenario asserts the other tabs are still there |
| The project list losing its search, sorting and closed projects | The list stays reachable from the menu's Projects section |
| The timeline's data cost growing with the task table | The query asks for the range the screen shows |

## Rollback

The two parts are separable and either can be reverted on its own.

- Reverting the timeline part restores the project list to the Plans tab. The menu part, if it has
  already landed, still offers the projects as a tree, so no capability is lost.
- Reverting the menu part leaves the timeline in place and puts the Calendar back in the tab bar.

Neither revert touches stored data: the timeline stores one preference, the tree stores nothing.
