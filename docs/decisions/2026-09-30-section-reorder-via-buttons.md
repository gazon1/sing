---
title: "Section reordering ships as buttons, not the drag handle that was drawn"
date: 2026-09-30
status: accepted
tags: [agenda, ui, a11y, gap]
---

## Context

**PR-2.13 — "Wire `SavedAgendaIntent.SectionsReordered`"** was correctly diagnosed: the
intent existed, `SavedAgendaViewModel` handled it, `draftState.reorderSections` existed.
Nothing dispatched it.

The reason turned out to be one layer down. The screen renders `SectionEditorCard`,
whose KDoc read:

> *Displays the section name and selector description, with a drag handle for
> reordering and a delete button.*

and whose body passed `handle = { Box { DragHandleIcon() } }`. A `DragHandleIcon` is a
static vector. No `pointerInput`, no drag state, no callback — the handle was decoration
describing an intent the component did not have.

Meanwhile **[ReorderableSectionList]** — a complete, custom drag-and-drop
implementation with long-press detection, haptics on drag start and on each boundary
crossing, auto-scroll, elevation and scale animation, and a `onSectionsReordered`
callback — had **zero call sites**.

## Idea

Swap `SectionEditorCard` for `ReorderableSectionList` and the PR is done.

## Decision

`SectionEditorCard` gets **move up / move down buttons**, and the drag handle is
removed. `ReorderableSectionList` stays unused.

## Rationale

The obvious fix does not compile. `ReorderableSectionList` is itself a `LazyColumn`, and
the section list lives *inside* `SavedAgendaScreen`'s `LazyColumn`. Nesting one
`LazyColumn` in another with unbounded height throws at runtime, and the obvious
workarounds — fixed height, or flattening the outer list into the inner one — are a
restructure of the whole screen, well outside a wiring PR.

Buttons are the better answer here for a reason beyond the nesting:

- **They are accessible.** A long-press drag handle is operable by neither a screen
  reader nor a keyboard. Move up / move down are two labelled buttons that a
  TalkBack user can find by name and a keyboard user can tab to. Choosing drag would
  have shipped an inaccessible feature that also happened to satisfy the PR.
- **They keep a stable layout.** `canMoveUp` / `canMoveDown` disable the first and last
  row's button rather than hiding it, so controls do not shift under the user's finger
  as they work down the list, and row heights never jump.
- **They need no gesture disambiguation.** On a small screen where a section list sits
  next to other draggable content, a long-press is easy to trigger accidentally.

`ReorderableSectionList` is genuinely good code and is the right affordance on desktop,
where pointer precision and a hover cursor make dragging comfortable. That is a
follow-up: extract the screen's list, or give the component a non-lazy `Column` mode.
Until then it stays unused, and its KDoc now says so, so the next reader does not read
it as an oversight.

## Consequences

- `SavedAgendaIntent.SectionsReordered` is now reachable; reordering a saved agenda's
  sections works on both platforms.
- `SectionEditorCard` gained four parameters (`canMoveUp`, `canMoveDown`, `onMoveUp`,
  `onMoveDown`). One call site.
- The move logic lives in the screen (a two-line `toMutableList()` + `add`/`removeAt`)
  rather than in the ViewModel. The intent takes the whole new list, so there is no
  "move to index" intent to bypass it.
- `ReorderableSectionList` is dead code that compiles and is covered by nothing. It is a
  Phase 4 removal candidate, or a Phase 3 follow-up to actually use it on desktop.
- The `key` for the reordered list is `"${section.name}#${section.order}#$index"`,
  which includes the index — so reordering *does* change every row's key and Compose
  will rebuild them. Correct, if slightly wasteful; left alone here.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/components/SectionEditorCard.kt`
- `.../agenda/presentation/components/ReorderableSectionList.kt` (unused)
- `.../agenda/presentation/screen/SavedAgendaScreen.kt:301-323`
- `.../agenda/presentation/viewmodel/SavedAgendaViewModel.kt:193-196`
- Same family: `2026-09-29-destroyed-but-not-deleted-callbacks.md`,
  `2026-09-30-dead-affordances-removed.md`
