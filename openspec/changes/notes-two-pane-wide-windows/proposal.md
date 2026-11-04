# notes-two-pane-wide-windows

**Status:** applied · **Issue:** #1524

## What

On a window at least 840 dp wide, the notes screen SHALL show the note list and
the selected note side by side. Below that width nothing changes: a note is a
screen of its own, and opening one replaces the list.

The pane is the same note preview the narrow layout pushes as a route. Nothing
about navigation changes: no route is added, no back stack is restructured, and
the notes graph is the one it was.

## Why

The desktop app is a phone app in a wide window (#1509 capped the lists at
640 dp and centred them; that made a wide window *readable*, not *useful*). On a
1920-pixel monitor the notes screen draws a 640-pixel column of titles and then
throws the whole right-hand two thirds away, and opening a note spends the other
half of the screen on the same emptiness.

This is the first pair, not the whole rollout: agenda, projects and search still
open their detail as a route on a wide window. The seam this change introduces —
one breakpoint, one place — is what those follow-ups reuse.

## Scope

### In scope

- The breakpoint, stated once, as a pure predicate
- The notes list and the note preview, side by side above it
- The empty pane's prompt, and the selection being cleared when the window
  narrows below the breakpoint
- The delete and backlink paths inside the pane behaving as they do on the
  full-screen preview
- A desktop window size the tests can choose

### Out of scope

- Every other list/detail pair (agenda → task detail, projects, tags, search);
  they are follow-ups on the same seam
- Editing inside the pane: the editor stays a full-screen route
- Auto-selecting the first note when nothing is selected
- The desktop app's default window size
