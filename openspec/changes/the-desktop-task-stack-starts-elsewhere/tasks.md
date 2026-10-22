# Tasks — the desktop task stack starts elsewhere

**Status:** deferred · **Priority:** medium — a visible divergence between platforms,
found while deduplicating the navigation layer.

## The decision first

- [ ] Decide whether the entry seeds the stack with the requested route, or pushes the
      requested route onto a placeholder seed
- [ ] Confirm the choice keeps the Desktop's "the graph survives a tab switch" behaviour
- [ ] Record the decision as an ADR addendum

## The seed

- [ ] The tasks entry seeds its stack with the route the outer graph asked for
- [ ] No platform seeds a stack with a route that was not asked for

## The date

- [ ] The floating action button on the Today tab opens the new-task screen with
      today's date filled in, on every platform
- [ ] The floating action button on the Inbox tab opens it with no date, on every
      platform

## The section

- [ ] Creating a task from a saved agenda view pre-selects that view's section, on
      every platform

## The extra screen

- [ ] Pressing back from a task's detail screen leaves the graph, on every platform
- [ ] The graph does not carry a screen the user never asked for underneath the one they
      did

## The tests

- [ ] A test that the stack the tasks entry produces contains the requested route at
      its top, on both platforms
- [ ] A test that the new-task screen opens with the requested date, on both platforms
- [ ] A test that the new-task screen opened from a saved agenda view carries that
      view's section, on both platforms
- [ ] A test that back from the detail screen exits the graph, on both platforms

## The gate

- [ ] The architecture rule that enforces the seed covers the case where the seed and
      the start are the same kind of screen with different parameters
- [ ] The rule cannot be undone by the next route that grows a field

## The documents

- [ ] The entry-dispatch spec stops describing one platform's seed as if it were both
- [ ] The addendum to the deduplication ADR stops calling this a real difference
- [ ] ADR `2026-10-03-nav3-backstack-top-vs-start-dispatch` gets an addendum: its
      premise — that the start and the seed are the same screen — stopped being true
      when the start route grew parameters

## Explicitly not doing

- Moving the tasks graph out of common code — it is already there
- Changing how a task is opened from the agenda
- Removing the platform-specific tasks entry — it may stay, with different arguments
