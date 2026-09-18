---
title: "BottomSheetHost centralises LaunchedEffect sheet state boilerplate"
date: 2026-09-22
tags: [ui-components, sheet-state, compose]
status: accepted
---

## Context

Every bottom sheet in the codebase needed the same boilerplate:

```kotlin
val sheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden)
LaunchedEffect(Unit) { sheetState.show() }
ModalBottomSheet(sheetState = sheetState, ...) { ... }
```

`LaunchedEffect { sheetState.show() }` is required for the slide-in animation to trigger when the sheet is conditionally shown via `if (sheets.active != null)`. Removing it breaks the visual appearance — the sheet composes at zero height.

## Decision

Create `BottomSheetHost` in `core/ui/components/`:

```kotlin
@Composable
fun BottomSheetHost(
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberBottomSheetState(initialValue = SheetValue.Hidden),
    content: @Composable () -> Unit,
) {
    LaunchedEffect(Unit) { sheetState.show() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        content()
    }
}
```

Apply to `ProjectDetailScreen` (10-variant sheet wrapper) and `ProjectEditorScreen` (IconPicker, ParentPicker inline sheets).

## Rationale

`LaunchedEffect { sheetState.show() }` cannot be removed — it must live somewhere. Centalising it in `BottomSheetHost` means:
- Leaf sheets receive a clean `BottomSheetHost(onDismiss = ...) { content }` API.
- No repeated `LaunchedEffect` boilerplate across screens.
- `sheetState` parameter allows callers to share state if needed.

## Consequences

- Every new bottom sheet should use `BottomSheetHost`, not raw `ModalBottomSheet` + `rememberBottomSheetState` + `LaunchedEffect`.
- `LaunchedEffect { sheetState.show() }` must **never** appear in leaf sheet code.
