# Tasks — plans-timeline-and-projects-menu

Two parts. The timeline lands first, and the menu second: in the other order the Plans tab would
be the timeline with the project list stranded, because the list's only entry point today is that
tab. Each task that changes behaviour names the test that proves it.

## A note on the row vocabulary

The first task of Part A, as it was written, promoted a badge enum out of the agenda feature. That
did not happen, and it did not need to: upstream replaced the badge with the task markings, and the
timeline row follows the markings rather than introducing a vocabulary of its own. The task below
records what was actually built — the timeline's row carries the shared markings — so the list
matches the code.

## Part A — the timeline

- [x] shared/ — The timeline's row carries the shared task markings: the row is the task list's own
      row, and the markings come from the one shared policy, so a task looks the same wherever the
      user meets it.
      *Test:* `TimelineGrouperTest` — the row's markings agree with the shared policy.
- [x] shared/ — Add the granularity, the period and the section types, with the horizon attached
      to the granularity.
      *Test:* `PlansGranularityTest` — the horizon of each granularity.
- [x] shared/ — Add the period builder: the periods a granularity produces from today, a week
      running Monday to Sunday, a month being a calendar month.
      *Test:* `TimelinePeriodsTest` — the periods are disjoint, ordered, and cover the horizon.
- [x] shared/ — Add the grouper: open tasks into sections, overdue first, empty periods dropped,
      completed tasks out.
      *Test:* `TimelineGrouperTest` — one task in one section at every granularity, overdue first
      and in no dated section, no empty section, no completed task.
- [x] shared/ — Add the settings port, its DataStore implementation and the feature's namespace;
      register the contributor the settings machinery expects.
      *Test:* `PlansSettingsRepositoryTest` — the granularity round-trips, an unreadable stored
      value falls back to the day, and two profiles keep their own granularity.
- [x] shared/ — Add the ViewModel: the granularity from the settings, the tasks from the two date
      ranges, the sections from the grouper.
      *Test:* `PlansViewModelTest` — a change of granularity re-groups the sections, and a task
      that changes date moves between them.
- [x] shared/ — Add the screen: the section list, the granularity control, the per-section add
      control, the empty state.
      *Test:* `PlansGranularityControlScenarioTest`, `PlansSectionHeaderScenarioTest` and
      `PlansEmptyHorizonScenarioTest` — the control names the selection, a header names its
      period, and the empty state appears when the horizon is empty.
- [x] shared/ — Bind the tab: the Plans destination renders the timeline, the screen family is
      registered, and the action button creates a task due today.
      *Test:* `FabActionResolverTest` — the Plans action creates a task with today's date.
- [x] androidApp/ — Write the carrier for PLANS-01 … PLANS-10, driving the real screen.
      *Test:* the Maestro flows under `Maestro/flows/plans/`, each claiming the scenario it drives.
- [x] shared/ — Close the holes the declared scenarios opened in the coverage matrix.

## Part B — the menu

- [x] shared/ — Add the tree node and the builder, using the repository's depth-first traversal so
      that a cycle in the hierarchy is caught rather than looped over.
      *Test:* `ProjectTreeBuilderTest` — the nesting, a project whose parent is gone, and a cycle.
- [x] shared/ — Add the tree ViewModel and the shared tree composable.
      *Test:* `ProjectsTreeViewModelTest` — the tree is live against the repository.
- [x] shared/ — Give the menu an entry that is not a destination, and render the projects section
      from the shared composable.
      *Test:* `ProjectsMenuTreeScenarioTest` — the section is present and its nodes are nested.
- [x] shared/ — Move the Calendar out of the tab list and into the menu list, and into the
      Android sheet's own section list.
      *Test:* `DestinationKindTest` — the tab list no longer holds the Calendar and the menu list
      does.
- [x] shared/ — Keep the full project list reachable from the menu's projects section.
      *Test:* `ProjectsMenuAllProjectsScenarioTest` — the entry opens the list.
- [x] desktopApp/ — Render the same tree and the same entries in the navigation drawer.
      *Test:* `MenuParityFlowTest` — the same entries and the same tree as the sheet.
- [x] androidApp/ — Write the carriers for MENU-01 … MENU-06 and NAV-01, NAV-02.
      *Test:* the Maestro flows under `Maestro/flows/menu/` and `Maestro/flows/nav/`, each claiming
      the scenario it drives.
- [x] shared/ — Close the holes those scenarios opened, and lower the ratchet floor to what is
      left.

## Both parts

- [x] shared/ — Verify the change against the implemented behaviour, and update the capability
      specs if what was built differs from what was specified.
- [x] shared/ — Re-derive the decisions digest: `./scripts/refresh-decisions-digest.sh`.
