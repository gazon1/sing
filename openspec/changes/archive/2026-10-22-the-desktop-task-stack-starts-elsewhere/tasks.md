# Tasks — the desktop task stack starts elsewhere

**Status:** implemented · **Priority:** medium — a visible divergence between platforms,
found while deduplicating the navigation layer.

## The decision first

- [x] Decide whether the entry seeds the stack with the requested route, or pushes the
      requested route onto a placeholder seed — the seed **is** the requested route
- [x] Confirm the choice keeps the Desktop's "the graph survives a tab switch" behaviour —
      the hoisted `AppNavStacks` are untouched; only what the tasks entry seeds changed
- [x] Record the decision as an ADR addendum — `2026-10-03-nav3-backstack-top-vs-start-dispatch`
      and `2026-10-02-nav-entries-dedup-deferred`

## The seed

- [x] The tasks entry seeds its stack with the route the outer graph asked for
- [x] No platform seeds a stack with a route that was not asked for — the Android entry
      already did; the Desktop one no longer does

## The date

- [x] The floating action button on the Today tab opens the new-task screen with
      today's date filled in, on every platform — one resolver, `fabActionForNav3`, builds
      the route for both
- [x] The floating action button on the Inbox tab opens it with no date, on every
      platform — the same resolver, the other branch

## The section

- [x] Creating a task from a saved agenda view pre-selects that view's section, on
      every platform — `AgendaNavigator.openTask` carries `sectionPrefillKey` into the
      start route, and the seed carries it into the editor

## The extra screen

- [x] Pressing back from a task's detail screen leaves the graph, on every platform —
      nothing is pushed under the detail route any more
- [x] The graph does not carry a screen the user never asked for underneath the one they
      did — the placeholder push is gone

## The tests

- [x] A test that the stack the tasks entry produces contains the requested route at
      its top, on both platforms — `NavStackSeedTest`
- [x] A test that the new-task screen opens with the requested date, on both platforms —
      `NavStackSeedTest` holds the date and the section through `toTasksRoute`, and the
      route both platforms seed with is the one the resolver built
- [x] A test that the new-task screen opened from a saved agenda view carries that
      view's section, on both platforms — the same test
- [x] A test that back from the detail screen exits the graph, on both platforms —
      `NavStackSeedTest` holds that no platform pushes the requested route a second
      time, which is what left a screen under the detail one

## The gate

- [x] The architecture rule that enforces the seed covers the case where the seed and
      the start are the same kind of screen with different parameters — the rule that
      was supposed to cover it never ran: its Konsist scope was `commonMain`, and the
      Desktop entry is in `jvmMain`, so it returned before it looked at anything. It is
      deleted rather than repaired
- [x] The rule cannot be undone by the next route that grows a field — the replacement
      reads the *source* of both platform entry files and compares the seed with the
      `start` expression, so a route that grows a field changes both sides at once

## The documents

- [x] The entry-dispatch spec stops describing one platform's seed as if it were both —
      the delta in this change replaces REQ-1 and REQ-2
- [x] The addendum to the deduplication ADR stops calling this a real difference
- [x] ADR `2026-10-03-nav3-backstack-top-vs-start-dispatch` gets an addendum: its
      premise — that the start and the seed are the same screen — stopped being true
      when the start route grew parameters

## Explicitly not doing

- Moving the tasks graph out of common code — it is already there
- Changing how a task is opened from the agenda
- Removing the platform-specific tasks entry — it may stay, with different arguments
  (it stayed; the arguments are the same on both platforms now)
