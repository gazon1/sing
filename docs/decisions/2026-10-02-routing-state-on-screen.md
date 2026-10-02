---
title: Routing state lives on the screen, not in the ViewModel
date: 2026-10-02
adr-number: 2026-10-02-routing-state-on-screen
status: accepted
tags: [architecture, ui, android, desktop]
---

# Routing state lives on the screen, not in the ViewModel

## Context

Two project skills give contradictory guidance on where to store "routing state" — which
bottom sheet, dialog, or popover is currently open:

- **`document-style-detail` skill (§ActiveSheet Sealed Interface, line 54):** "All bottom sheets
  and dialogs are routed through a single `ActiveSheet` sealed interface **in the ViewModel** —
  never local `remember { mutableStateOf<Sheet?>(null) }` in the Composable."

- **`ui-event-vs-state/routing-state.md`:** Routing state (`activeSheet`) belongs **on the
  screen** via `remember { mutableStateOf<Sheet?>(null) }`, NOT in the VM. Rationale:
  Composable-local, no persistence requirement, no repository call needed.

The project code follows `routing-state.md`, not `document-style-detail`. The contradiction
must be resolved.

## Idea

Routing state (which overlay is open) is **not** UI state and **not** a one-shot event.
It is a third category: ephemeral Composable-local state. It belongs on the screen,
managed by `rememberOverlayState<T>()` (the shared primitive from Phase 2), with the
`ActiveSheet` sealed interface used only as a **type tag** — not as the state container.

The VM never holds the currently-open sheet. It only holds domain data derived from the
state (e.g., which project is pre-selected in a picker). Routing decisions are handled
on the screen.

## Decision

1. **Routing state lives on the screen.** Use `rememberOverlayState<SealedSheet>()` to
   hold the current sheet/dialog. The screen calls `overlayState.show(Sheet)` and
   `overlayState.hide()`. The VM has no `MutableStateFlow<Sheet?>` for routing.

2. **The sealed interface lives on the screen as a type argument.** `ActiveSheet` /
   `ProjectDetailSheet` / `NoteEditorSheet` are `sealed interface` hierarchies used as
   type tags for `rememberOverlayState<T>()`, not as state containers in the VM.

3. **The VM is unaware of which sheet is open.** Navigation-from-sheet (e.g., tapping
   a note in the backlinks sheet) is handled by a callback passed into the sheet composable.
   The sheet receives `onNoteSelected: (NoteId) -> Unit` and calls it directly — no
   `SharedFlow` round-trip needed.

4. **`document-style-detail` skill is wrong on this point.** The rule "never
   `remember { mutableStateOf<Sheet?>(null) }`" was a misapplication of the
   "no stale state" principle. `rememberOverlayState<T>()` solves the stale-cast
   problem without VM pollution.

## Rationale

The three-category model (`ui-state`, `one-shot-event`, `routing-state`) is semantically
correct:

| Category | Lifetime | Location | Example |
|---|---|---|---|
| UI state | Persists across rotation | VM `StateFlow` | task list, filter |
| One-shot event | Fire-once | VM `SharedFlow` | snackbar, navigate-back |
| Routing state | Closes on rotation/back | Screen `remember` | activeSheet, menuExpanded |

Routing state is ephemeral by design: rotating the device closes all sheets. There is
no domain logic that needs to react to "which sheet is open" — only the UI layer does.
Putting it in the VM would create a class of bugs where the VM's routing state becomes
stale relative to the screen's actual state, with no upside.

The `rememberOverlayState<T>()` primitive (Phase 2) provides the same type safety as a
sealed interface in a VM StateFlow, without the tight coupling. The sealed interface
remains useful as a **discriminated union type tag** for the overlay, not as the state
holder.

## Consequences

- `document-style-detail` skill must be updated to remove or qualify the "never
  remember sheet state" rule.
- Existing screens that use `MutableStateFlow<Sheet?>` in the VM should migrate to
  `rememberOverlayState<Sheet>()` on the screen side.
- Sheet composables receive callbacks for domain actions, not routing events.

## Links

- `singularity-todo-ui-event-vs-state/routing-state.md`
- `singularity-todo-shared-ui-components` (Phase 2 `rememberOverlayState` primitive)
- Phase 5 refactor: `NoteEditorScreen`, `NotePreviewScreen` use `rememberOverlayState` correctly
