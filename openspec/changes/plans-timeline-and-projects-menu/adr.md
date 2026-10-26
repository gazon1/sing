# ADR: plans-timeline-and-projects-menu

This change implements one decision. Its rationale is not repeated here — the decision record is
the authority, and this file says which record applies.

| | |
|---|---|
| **ADR** | `docs/decisions/2026-10-26-plans-timeline-and-projects-menu.md` |
| **Slug** | `2026-10-26-plans-timeline-and-projects-menu` |
| **Status** | accepted |

## What the decision settles

- The timeline is a feature of its own, not a mode of the agenda and not a saved agenda view.
- The sections are generated from the granularity and today's date, and are disjoint by
  construction.
- The row and badge vocabulary moves from the agenda feature into the tasks feature, so that both
  screens speak one language about a task.
- The menu grows an entry kind that is not a destination, and both platforms draw the projects
  tree from one composable.
- The Calendar leaves the tab list and joins the menu list; the Calendar screen itself is
  unchanged.
- The full project list stays reachable from the menu.

## What this change does not decide

- The look of the tree, the granularity control or the section header. Those follow the app's
  existing screens rather than being designed here.
- The contents of the Calendar. It is not touched.
- Whether a year-level granularity is ever added. The set is open; this change does not close it.
