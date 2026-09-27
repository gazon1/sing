---
name: singularity-todo-shared-ui-components
description: Screen decomposition and the shared widget library for Singularity Todo Compose screens. Use when a Screen.kt grows past ~150 lines, when state/logic/UI mix in one Composable, when an inline widget (AlertDialog, Box+Spinner, Card+Row+Switch) is copy-pasted across screens, when a card takes 4+ separate callbacks, when choosing between DropdownMenu / ModalBottomSheet / AlertDialog, when designing a content-slot API, or when applying the document-style 4-section layout. Router — load the leaf file for the specific concern.
---

# Shared UI Components & Composable Decomposition

This skill covers two complementary concerns:

1. **The shared widget library** at `core/ui/components/` — the primitives screens consume.
2. **The decomposition rules** for splitting a `Screen.kt` into smaller, testable,
   focused composables.

Read this router first when touching any Compose screen, then open the one leaf that
matches the task. Everything here interlocks: every decomposition should consume shared
widgets, and every shared widget should be readable in 30 seconds.

## Which file to load

| Your task | Open |
|---|---|
| Using an existing shared widget, or adding one to the library | [`widget-library.md`](widget-library.md) |
| Splitting a large `Screen.kt`; extracting a sub-composable | [`decomposition.md`](decomposition.md) |
| A component takes 4+ callbacks, or you need a "header + body" seam | [`content-slot-api.md`](content-slot-api.md) |
| Dropdown menu vs modal bottom sheet vs alert dialog | [`menus-and-dialogs.md`](menus-and-dialogs.md) |
| Document-style task detail layout (4-section rule) | [`document-style-layout.md`](document-style-layout.md) |

Two rules that apply to every one of them:

- **A component with 4+ separate callback parameters is a decomposition problem.** Give it
  a content slot instead of a longer parameter list — see `content-slot-api.md`.
- **Tests follow the composable, not the screen.** A sub-composable extracted from a
  screen gets its own test file next to the feature; the screen keeps only a smoke test
  that it renders.

## Related

- `singularity-todo-ui-event-vs-state` — `UiEvent` vs continuous state, `EventBus`
- `singularity-todo-pure-formatters` — formatters testable without Compose
- `singularity-todo-document-style-detail` — the detail-screen UX spec this layout serves
- `singularity-todo-sheet-extraction` — extracting sheets and dialogs from a screen
- `singularity-todo-compose-overview` — router for the whole Compose cluster
