---
title: "Notes row actions and Calendar header controls are unreachable from the UI"
date: 2026-09-29
status: accepted
tags: [notes, calendar, ui, gap]
---

## Context

Writing PR-3 flows for Notes and Calendar turned up two features that are modelled
in the ViewModels but have no reachable control on Android. Neither is a crash, and
neither is visible from the code — both are declared, wired to intents nobody
dispatches, and there was no test that pressed them.

**Notes row actions.** `NotesListViewModel` implements pin, archive, delete,
copy-to-profile, selection mode and a sort menu, and `NoteFilter` has
`All / Pinned / Archived`. On device the list renders a title, a relative
timestamp and three filter chips — nothing else. Long-pressing a note row does
nothing at all (unlike the task row, which long-presses into a context menu).
So a note can be created, opened, edited and filtered, but never pinned,
archived, deleted or reordered through the app.

**Calendar header controls.** `CalendarViewModel` has `GoToday`, `GoNext`,
`GoPrevious`, `ToggleMiniCalendar` and `MonthPageChanged` intents, and the screen
has a mode switcher wired to it. The mode control is the only one with a visible
affordance: it is labelled with the current mode and opens the four-way switcher.
The Today button, the prev/next arrows and the mini-calendar toggle have no
testTag and no reachable element — and, unlike the mode control, tapping them
does nothing.

Day cells are a separate matter and work: they expose their number as text, which
is unique within a month grid, so `tapOn: text: "15"` opens that day.

## Idea

1. Wire the missing controls.
2. Cover what is reachable and record the gaps.
3. Leave it undocumented.

## Decision

We did (2) for this PR, and recorded the gaps here so they are visible rather
than rediscovered.

Notes flows cover what the screen offers: create via quick-add, edit the body and
see it survive a round trip, and switch the All / Pinned / Archived chips. They
assert that Pinned and Archived come up empty for unpinned, unarchived notes —
which is the honest current behaviour, not a test written around a bug.

Calendar flows cover the month view, the four-way mode switcher, and tapping a
day. The mode control is the one header affordance that works, so the flows use it.

`PROJECT_DETAIL_QUICK_ADD` was added to `TestTags` because the project detail's
quick-add field had no selector, unlike the equivalent field on the notes screen.

(1) is real work that changes product behaviour, not a test-suite change, so it
belongs in its own change rather than inside a test PR. (3) is how these became
invisible in the first place.

## Consequences

- Pin, archive, delete, copy and multi-select on notes are **not testable today**;
  the flows cannot be written until the controls exist. Same for the Calendar
  Today / prev / next / mini-calendar controls.
- A flow that assumes a note can be deleted will hang rather than fail clearly.
  `Maestro/TAGS.md` records this under "Missing testTags" alongside the selectors
  that do exist.
- `ProjectDetailContent`'s quick-add field is now addressable, so
  `projects-add-task` selects by id rather than by placeholder text.
- The notes quick-add destination is not stable: it lands on the note's preview
  or straight in the editor. `notes-edit-body` taps "Edit" optionally and waits
  for the editor, so it does not depend on which one it got.

## Links

- `Maestro/flows/notes/`, `Maestro/flows/calendar/`, `Maestro/flows/projects/`
- `Maestro/TAGS.md` — "Missing testTags"
- `feature/notes/presentation/viewmodel/NotesListViewModel.kt`
- `feature/calendar/presentation/viewmodel/CalendarViewModel.kt`
- Same pattern: `2026-09-29-editor-row-onclick-noop-default.md`
