# notes-two-pane-wide-windows — tasks

**Issue:** #1524 · **Spec:** `notes` (REQ-NOTES-WIDE-1, REQ-NOTES-WIDE-2)

Read first: ADR `2026-11-05-a-two-pane-notes-screen-above-840-dp`; skills
`singularity-todo-desktop-compose-ui-tests` (the harness and the window-size rule)
and `singularity-todo-test-tag-strategy` (the two `slow` tags below).

---

## 1. State the breakpoint once

- [x] Add a 840 dp breakpoint and a pure predicate for it in `core/ui`.
      **Test:** `WideLayoutTest` — one dp below is narrow, the breakpoint itself
      and one dp above are wide.
- [x] No screen compares a width of its own.
      **Test:** `git grep` for `840` outside `core/ui` returns only documentation.

## 2. Make the notes screen two-pane above it

- [x] Measure the width the notes screen is given, and compose the list and the
      detail pane side by side when it is wide.
      **Test:** `NotesTwoPaneFlowTest.a_wide_window_opens_the_note_beside_the_list_it_came_from`.
- [x] Give the pane an explicit empty state when nothing is selected.
      **Test:** the same flow, before the click.
- [x] Clear the selection when the window narrows below the breakpoint.
      **Test:** not covered by an automated test — a desktop test cannot resize
      the window mid-flow; recorded as open in the change's report.

## 3. Keep the narrow layout intact

- [x] Above the breakpoint a note row selects; below it the row opens the preview
      route, as before.
      **Test:** `NotesTwoPaneFlowTest.a_narrow_window_still_opens_the_note_in_place_of_the_list`,
      with the existing `NotesFlowTest` and `NotesFlowTagReachabilityFlowTest`
      unchanged and green.
- [x] The note card's default behaviour is the route.
      **Test:** `NotesScreenTest`, unchanged.

## 4. Share one preview between the route and the pane

- [x] Move the preview's loading, delete confirmation and backlinks into a host
      that takes its "back" from the caller, and keep the route screen as a thin
      caller of it.
      **Test:** `NotesFlowTagReachabilityFlowTest.the_note_preview_exposes_the_edit_action_the_flows_wait_for`, unchanged.
- [x] Deleting from the pane clears the selection instead of leaving the notes
      graph.
      **Test:** not covered by an automated test — the delete confirmation sheet
      is driven through the same host as the route, whose behaviour is unchanged.

## 5. Let a desktop test choose its window

- [x] Give the desktop harness a width and a height, defaulting to the sizes it
      already used.
      **Test:** the whole desktop suite, at its unchanged default.
