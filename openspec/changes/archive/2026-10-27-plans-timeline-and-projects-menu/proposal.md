# plans-timeline-and-projects-menu

## What

Three changes to the app's navigation frame, landing as one:

1. The **Plans tab** stops being a project list and becomes a chronological timeline of upcoming
   work — one section per day, week or month, each holding the tasks of that period, with the
   granularity switchable and remembered.
2. The **project hierarchy** moves out of the tab bar and into the app menu, where it is an
   expandable tree.
3. The **Calendar** leaves the bottom navigation bar and becomes an entry of the menu — the bottom
   sheet on Android, the navigation drawer on Desktop.

## Why

The Plans tab was a flat list of projects, and the projects are a hierarchy. The tab could not
show that hierarchy, so it showed the projects flat and the hierarchy was something you had to
enter to see. And a screen named "Plans" that lists projects is not a plan — a plan is what is
coming, and there was no place in the app that showed it.

The three changes are one change because the first is what empties the tab the third is leaving.
The Calendar's button was one of six in a bar that is full, and the screen it opened duplicates
what a day/week/month timeline does.

- **The timeline** answers "what is coming". It groups by time rather than by project, because
  time is what the question is about, and it lets the user change the size of the answer: a day is
  right for today and wrong for a fortnight.
- **The tree** is what the tab could not be. The hierarchy is only visible when the projects are
  nested, and a menu has room for a tree where a tab bar does not.
- **The Calendar** is still a screen and stays one. What changes is where it is reached from.

## Scope

### In scope

- A timeline screen: sections by day, week or month, each holding the tasks due in that period
- A granularity control, and the choice remembered per profile
- Overdue work leading the timeline, and work without a date staying out of it
- A new task created from a section, due on that section's period
- A project tree in the menu, expandable, with children indented under their parent
- The Calendar as a menu entry, and no longer a tab
- The full project list, still reachable from the menu
- The same menu on Android and on Desktop

### Out of scope

- The Calendar screen's own behaviour — it moves in the navigation, it does not change
- The Upcoming view and the agenda's saved views — untouched
- Editing projects from the tree — the tree navigates, the project screen edits
- A year-level granularity — the set is open, and the year is a later addition
- How projects, tasks and their dates are stored or synchronised

## What the user gets

A user who opens Plans sees today first, then tomorrow, then each following day, with the tasks
of each day under it. They can switch to weeks or to months when a day is too fine a grain. They
find their projects in the menu, where the hierarchy is visible and every project is one tap away.
The Calendar is still there, one tap away, in the menu instead of the bar.

## Related

- The decision: `docs/decisions/2026-10-26-plans-timeline-and-projects-menu.md`
- The specification: `specs/016-plans-timeline-projects-menu/spec.md`
- The plan: `specs/016-plans-timeline-projects-menu/plan.md`
