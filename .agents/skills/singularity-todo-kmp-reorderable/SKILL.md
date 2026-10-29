---
name: singularity-todo-kmp-reorderable
description: Why `sh.calvin.reorderable` has no KMP multiplatform artifact and what to use instead. Use when implementing drag-and-drop reordering in Compose Multiplatform code (commonMain). Documents the platform-only artifact situation, workarounds (LazyColumn with no DnD, MultiplatformDragAndDrop, custom Compose), and why the deferred approach is correct.
---

# Drag-and-Drop Reordering in KMP

## The hard fact

`sh.calvin.reorderable` is the standard Compose drag-and-drop reorder library. **It has NO multiplatform artifact.** Only:

- `reorderable:reorderable:2.x` — JVM-only (Desktop)
- `reorderable:reorderable-android:2.x` — Android-only

There is no `reorderable:reorderable-multiplatform:2.x` or similar KMP common artifact. Confirmed Sep 2026.

This means: **you cannot import `sh.calvin.reorderable.*` from `commonMain`** in a KMP project. The library will fail to resolve.

---

## Current state in this project (MR4 result)

There is no drag-and-drop in the agenda's section list, and there is no stub
waiting for one either. The `ReorderableSectionList` that MR4 left behind — a
plain `LazyColumn` with a `DragHandle` icon that did nothing when tapped — was
removed with the other dead UI components, and the screen reorders its
sections with **buttons**: `SectionEditorCard` renders an explicit move-up and
move-down control per row. The deferral decision and the reasons are in ADR
`2026-09-30-section-reorder-via-buttons`; the short version is that a
long-press drag handle is neither operable by a screen reader nor from a
keyboard, while the buttons are both.

So the question this skill answers is no longer "which library would the stub
use" but "what would a drag-and-drop implementation have to do to be worth it
here" — and the answer has to clear the bar the buttons already cleared.

---

## Options for KMP drag-and-drop

### Option A: `MultiplatformDragAndDrop` (Material3 1.8+, experimental)

JetBrains has been working on official KMP drag-and-drop. Material3 1.8 added `DragAndDropTarget` modifiers to commonMain.

```kotlin
// commonMain, Material3 1.8+
import androidx.compose.material3.ExperimentalMaterial3Api

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReorderableLazyColumn(...) {
    LazyColumn(state = lazyListState) {
        items(items, key = { it.id }) { item ->
            Box(modifier = Modifier.dragAndDropSource { offset -> startTransfer(offset) }) {
                // render item
            }
        }
    }
}
```

**Status (Sep 2026):** still experimental, API surface still changes, no production-ready example for reorderable lists. **NOT recommended yet.**

### Option B: Custom `pointerInput` reordering

Write your own drag detection using `detectDragGestures` and reorder the list inside `LaunchedEffect`:

```kotlin
// commonMain, no library needed
@Composable
fun ReorderableLazyColumn(
    items: List<Item>,
    onMove: (from: Int, to: Int) -> Unit,
) {
    val state = rememberLazyListState()
    LazyColumn(state = state) {
        itemsIndexed(items, key = { _, it -> it.id }) { index, item ->
            var offsetY by remember { mutableFloatStateOf(0f) }
            Box(
                modifier = Modifier
                    .graphicsLayer { translationY = offsetY }
                    .pointerInput(Unit) {
                        detectDragGesturesAfterLongPress(
                            onDrag = { _, dragAmount ->
                                offsetY += dragAmount.y
                            },
                            onDragEnd = {
                                // compute target index from offsetY + item height
                                onMove(index, targetIndex)
                                offsetY = 0f
                            },
                        )
                    },
            ) { /* render item */ }
        }
    }
}
```

**Pros:** works everywhere, no library.
**Cons:** tedious to get right (item height, snapping, scrolling on edge). No built-in drag handle visual.

### Option C: Per-platform implementations

`expect/actual` Composable:

```kotlin
// commonMain
@Composable
expect fun ReorderableList(items: List<Item>, onMove: (Int, Int) -> Unit)

// androidMain
@Composable
actual fun ReorderableList(items: List<Item>, onMove: (Int, Int) -> Unit) {
    // use sh.calvin.reorderable-android
}

// jvmMain
@Composable
actual fun ReorderableList(items: List<Item>, onMove: (Int, Int) -> Unit) {
    // use sh.calvin.reorderable (JVM)
}
```

**Pros:** uses battle-tested library on each platform.
**Cons:** two implementations to maintain.

---

## What this project chose — and why

**Stub (no DnD) for now, real implementation deferred.**

Reasoning (from MR4 plan):

- `sh.calvin.reorderable` is platform-only — no common artifact
- `MultiplatformDragAndDrop` is experimental and unstable (Sep 2026)
- Per-platform `expect/actual` doubles maintenance
- Custom `pointerInput` is tedious and bug-prone
- **The feature (reorder saved agenda sections) is nice-to-have, not MVP** — schedule for a future MR when one of the above stabilizes

The visible affordance (drag handle icon) signals "future DnD" without misleading users about working functionality.

---

## When to revisit

Add real drag-and-drop when ANY of the following happens:

1. `MultiplatformDragAndDrop` graduates from experimental in a Material3 release we adopt.
2. `sh.calvin.reorderable` publishes a multiplatform artifact (check [sh.calvin.reorderable releases](https://github.com/Calvin-LL/Reorderable/releases) periodically).
3. A user reports a concrete use case that requires reorder (right now, "reorder saved agenda sections" is a v2 polish — Create/Edit/Save is the v1 MVP).
4. The agenda section reorder is the bottleneck for adoption.

Until then: **keep the stub, file an ADR when the situation changes.**

---

## How to add it later (template)

```kotlin
// shared/src/commonMain/kotlin/.../ReorderableSectionList.kt

@Composable
fun ReorderableSectionList(
    sections: List<Section>,
    onReorder: (List<Section>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lazyListState = rememberLazyListState()
    var draggedIndex by remember { mutableStateOf(-1) }
    var targetIndex by remember { mutableStateOf(-1) }

    LazyColumn(
        state = lazyListState,
        modifier = modifier,
    ) {
        itemsIndexed(sections, key = { _, s -> "${s.name}#${s.order}" }) { index, section ->
            SectionRow(
                section = section,
                isDragging = index == draggedIndex,
                dragHandle = {
                    IconButton(
                        onClick = { /* long-press handled below */ },
                        modifier = Modifier.pointerInput(Unit) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { draggedIndex = index },
                                onDrag = { change, dragAmount ->
                                    // ... compute target from dragAmount
                                },
                                onDragEnd = {
                                    if (draggedIndex != targetIndex) {
                                        onReorder(sections.toMutableList().apply {
                                            add(targetIndex, removeAt(draggedIndex))
                                        })
                                    }
                                    draggedIndex = -1
                                    targetIndex = -1
                                },
                            )
                        },
                    ) {
                        Icon(Icons.Default.DragHandle, contentDescription = "Drag")
                    }
                },
            )
        }
    }
}
```

**Test:** drag-and-drop is fundamentally UI, so unit tests don't help much. Instead, ensure `onReorder` is called with the correct list when the order changes — test the intent dispatch at the VM level (`SavedAgendaIntent.SectionsReordered` → `vm.state.value.draft.sections` updated).

---

## Reference

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/components/SectionEditorCard.kt` — the screen that reorders, with buttons
- `docs/decisions/2026-09-30-section-reorder-via-buttons.md` — why buttons, and what a drag handle would have to beat
- `docs/decisions/2026-09-16-agenda-mr4-saved-views-create-reorder.md` — MR4 ADR with the deferral decision
- [sh.calvin.reorderable GitHub](https://github.com/Calvin-LL/Reorderable) — official library, monitor for multiplatform artifact
- [Compose Material3 1.8 release notes](https://developer.android.com/jetpack/androidx/releases/compose-material3) — monitor for stable `MultiplatformDragAndDrop`
