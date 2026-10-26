# Tasks — plans-timeline-and-projects-menu

Two parts. The timeline lands first, and the menu second: in the other order the Plans tab would
be the timeline with the project list stranded, because the list's only entry point today is that
tab. Each task that changes behaviour names the test that proves it.

## Part A — the timeline

- [ ] shared/ — Promote the row vocabulary out of the agenda feature: the row wrapper and the
      badge enum move into the tasks feature, and the badge policy moves with them. The six badge
      cases and their meanings do not change.
      *Test:* the existing agenda badge and evaluator tests, unchanged in meaning and passing
      against the promoted types.
- [ ] shared/ — Add the granularity, the period and the section types, with the horizon attached
      to the granularity.
      *Test:* `PlansGranularityTest` — the horizon of each granularity.
- [ ] shared/ — Add the period builder: the periods a granularity produces from today, a week
      running Monday to Sunday, a month being a calendar month.
      *Test:* `TimelinePeriodsTest` — the periods are disjoint, ordered, and cover the horizon.
- [ ] shared/ — Add the grouper: open tasks into sections, overdue first, empty periods dropped,
      completed tasks out.
      *Test:* `TimelineGrouperTest` — one task in one section at every granularity, overdue first
      and in no dated section, no empty section, no completed task.
- [ ] shared/ — Add the settings port, its DataStore implementation and the feature's namespace;
      register the contributor the settings machinery expects.
      *Test:* `PlansSettingsRepositoryTest` — the granularity round-trips, and an unreadable stored
      value falls back to the day.
- [ ] shared/ — Add the ViewModel: the granularity from the settings, the tasks from the two date
      ranges, the sections from the grouper.
      *Test:* `PlansViewModelTest` — a change of granularity re-groups the sections, and a task
      that changes date moves between them.
- [ ] shared/ — Add the screen: the section list, the granularity control, the per-section add
      control, the empty state.
      *Test:* `PlansScreenTest` — the control names the selection, a header names its period, and
      the empty state appears when the horizon is empty.
- [ ] shared/ — Bind the tab: the Plans destination renders the timeline, the screen family is
      registered, and the action button creates a task due today.
      *Test:* `FabActionResolverTest` — the Plans action creates a task with today's date.
- [ ] androidApp/ — Write the carrier for PLANS-01 … PLANS-10, driving the real screen.
      *Test:* the ten scenarios, each claimed on the platform it runs on.
- [ ] shared/ — Close the thirty-six holes the declared scenarios opened in the coverage matrix.

## Part B — the menu

- [ ] shared/ — Add the tree node and the builder, using the repository's depth-first traversal so
      that a cycle in the hierarchy is caught rather than looped over.
      *Test:* `ProjectTreeBuilderTest` — the nesting, a project whose parent is gone, and a cycle.
- [ ] shared/ — Add the tree ViewModel and the shared tree composable.
      *Test:* `ProjectsTreeViewModelTest` — the tree is live against the repository.
- [ ] shared/ — Give the menu an entry that is not a destination, and render the projects section
      from the shared composable.
      *Test:* `MenuBottomSheetTest` — the section is present and its nodes are nested.
- [ ] shared/ — Move the Calendar out of the tab list and into the menu list, and into the
      Android sheet's own section list.
      *Test:* `DestinationKindTest` — the tab list no longer holds the Calendar and the menu list
      does.
- [ ] shared/ — Keep the full project list reachable from the menu's projects section.
      *Test:* `MenuBottomSheetTest` — the entry opens the list.
- [ ] desktopApp/ — Render the same tree and the same entries in the navigation drawer.
      *Test:* the Desktop drawer test, asserting the same entries and the same tree as the sheet.
- [ ] androidApp/ — Write the carriers for MENU-01 … MENU-06 and NAV-01, NAV-02.
      *Test:* the eight scenarios, each claimed on the platform it runs on.
- [ ] shared/ — Close the holes those scenarios opened, and lower the ratchet floor to what is
      left.

## Both parts

- [ ] shared/ — Verify the change against the implemented behaviour, and update the capability
      specs if what was built differs from what was specified.
- [ ] shared/ — Re-derive the decisions digest: `./scripts/refresh-decisions-digest.sh`.
