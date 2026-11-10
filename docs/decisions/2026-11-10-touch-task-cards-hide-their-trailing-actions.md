---
title: "Touch task cards hide their trailing actions behind a long press"
date: 2026-11-10
status: accepted
tags: ["ui", "tasks", "android", "a11y"]
---

# Touch task cards hide their trailing actions behind a long press

## Context

`TaskCard`'s `DefaultTaskCardTrailing` drew pin, AI and delete as icon buttons
next to the text on every platform. On a phone that row of 32 dp icons took
width from the only thing worth reading — the title — and each icon was a tap
target sitting beside the row's own tap target. Issue #2739 asks for the content
and the primary row interaction to stay prominent, without losing an action.

The call sites differ:

- **project detail**: `onPin` and `onDelete` (`DeleteTask` is a soft delete with
  an "Undo" snackbar);
- **search results**: `onPin`, plus an `onAiClick` that opened the task — the
  same destination as the row tap;
- **archive**: only the same redundant navigation behind `onAiClick`.

Agenda rows never used `TaskCard`; they already have the long-press
`TaskContextMenuSheet`.

## Decision

1. `TaskCard` takes `touchLayout: Boolean = isAndroidPlatform`. On a touch layout
   the default `trailing` slot draws nothing; on a pointer layout it draws the
   actions as before. One presentation decision at the shared boundary rather
   than a platform check per screen.
2. On a touch layout the same `TaskCardActions` callbacks open from a long-press
   `DropdownMenu` on the card (Pin/Unpin, Delete). The menu is derived from the
   caller's actions, so per-surface policy stays explicit in `TaskCardActions`
   and no handler is written twice.
3. The card exposes pin and delete as accessibility custom actions too, so a
   screen-reader user reaches them without the long press.
4. Search's `onAiClick` is removed: it navigated to the task, duplicating the
   card tap, while the icon said "AI actions". No AI action is invented.

## Rationale

Deleting the buttons alone would have removed project pin/delete and search pin
on Android, so the actions needed a touch route. The long-press idiom already
exists in agenda, which makes it recognisable in the same app. Deriving the menu
from `TaskCardActions` keeps one implementation per action and keeps each
surface's policy in the place the slot API already states it. `customActions`
is the accessibility route that costs no visual affordance.

Card-level AI is deliberately not a row: the callback has no AI operation behind
it — the deferred card-AI decision requires preview and confirm — so rendering
it would misrepresent a navigation as an AI capability.

## Consequences

- Desktop is unchanged: same trailing buttons, same call sites, same tests.
- Android loses the archive card's "AI actions" icon; its only behaviour was
  navigating to the task, which the row tap still does.
- A hardware keyboard cannot open the menu without the long-press gesture. The
  actions stay reachable through the accessibility custom actions and, for pin,
  through the task detail screen; the limit is recorded at the call site.
- Search results are query snapshots, not a live view of the task store: a pin
  made from a row is not reflected until the query runs again (#2810). The flow
  asserts reachability; the stale row is tracked there.
- Coverage: `TaskCardTouchPresentationTest` (desktop harness, both
  presentations) and two Maestro flows (`projects/03-task-card-actions`,
  `search/10-task-card-actions`) that assert the icons are absent and the
  actions work. Both flows ran on a realme RMX3938 on 2026-11-10.

## Links

- Issue #2739.
- [`2026-09-30-card-level-ai-actions-deferred.md`](./2026-09-30-card-level-ai-actions-deferred.md),
  [`2026-10-02-ai-proposal-confirmation.md`](./2026-10-02-ai-proposal-confirmation.md)
  — why card-level AI has no row.
- [`2026-09-25-taskcard-slot-api-and-orphan-vm-cleanup.md`](./2026-09-25-taskcard-slot-api-and-orphan-vm-cleanup.md)
  — the slot API this extends.
