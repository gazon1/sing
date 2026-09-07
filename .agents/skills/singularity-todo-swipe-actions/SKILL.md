---
name: singularity-todo-swipe-actions
description: Swipe-to-dismiss pattern for Compose Multiplatform using Material3 SwipeToDismissBox. Use when implementing swipe actions on list items (pin/unpin, archive, delete) in NotesScreen or TasksScreen. Covers SwipeToDismissBox API, directional swipe (left=delete, right=pin), background color reveal, iOS Mail-style rubber-band animation, and disabling swipe in selection mode.
---

# Swipe Actions — Material3 SwipeToDismissBox

## When to Use This Skill

Use when implementing **swipe gestures on list items** in `NotesScreen`, `TasksScreen`, or any `LazyColumn`-based list. This skill covers:
- Swipe-to-pin and swipe-to-delete (Apple Notes / TickTick pattern)
- Directional swipe: left = destructive, right = positive action
- Background color reveal with icon
- iOS Mail-style rubber-band overshoot animation
- Disabling swipe in multi-select mode

## API Overview

`SwipeToDismissBox` (Material3 1.4+) replaces the deprecated `SwipeToDismiss`:

```kotlin
@Composable
fun SwipeToDismissBox(
    state: SwipeToDismissBoxState,
    modifier: Modifier = Modifier,
    enableDismissFromStartToEnd: Boolean = true,   // right→left (positive action)
    enableDismissFromEndToStart: Boolean = true,    // left→right (destructive)
    backgroundContent: @Composable RowScope.(SwipeToDismissBoxValue) -> Unit,
    content: @Composable () -> Unit,
)
```

## Notes Use Case (Phase 2)

**Actions:**
- **Swipe right → Pin/Unpin** (gold/amber background, `PushPin` icon)
- **Swipe left → Delete** (red background, `Delete` icon)

## Implementation Pattern

### 1. SwipeToDismissBoxState

```kotlin
// In NotesScreen.kt or per-card in NoteCard.kt
val dismissState = rememberSwipeToDismissBoxState(
    confirmValueChange = { dismissValue ->
        when (dismissValue) {
            SwipeToDismissBoxValue.StartToEnd -> {
                // Swiped right → toggle pin
                onTogglePin(note.id)
                false  // don't dismiss, just trigger action
            }
            SwipeToDismissBoxValue.EndToStart -> {
                // Swiped left → delete
                onDelete(note.id)
                false  // don't dismiss, let VM handle soft-delete animation
            }
            SwipeToDismissBoxValue.Settled -> false
        }
    }
)
```

### 2. Background Content (color reveal)

```kotlin
SwipeToDismissBox(
    state = dismissState,
    enableDismissFromStartToEnd = !isSelectionMode,  // disable in selection mode
    enableDismissFromEndToStart = !isSelectionMode,
    backgroundContent = { dismissValue ->
        val direction = dismissValue
        val color by animateColorAsState(
            targetValue = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Color(0xFFFFB300) // amber/gold for pin
                SwipeToDismissBoxValue.EndToStart -> Color(0xFFE53935)  // red for delete
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            },
            label = "swipe_bg_color"
        )
        val alignment = when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
            SwipeToDismissBoxValue.EndToStart -> Alignment.CenterEnd
            SwipeToDismissBoxValue.Settled -> Alignment.Center
        }
        val icon = when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> Icons.Filled.PushPin
            SwipeToDismissBoxValue.EndToStart -> Icons.Filled.Delete
            SwipeToDismissBoxValue.Settled -> null
        }

        Box(
            Modifier
                .fillMaxSize()
                .background(color)
                .padding(horizontal = 20.dp),
            contentAlignment = alignment
        ) {
            icon?.let {
                Icon(
                    imageVector = it,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    },
    content = {
        // The card itself
        NoteCard(
            note = note,
            onClick = { if (isSelectionMode) onToggleSelect(note.id) else onNavigateToNote(note.id) },
            onLongClick = { onEnterSelection(note.id) },
            isSelected = note.id in selectedIds,
        )
    }
)
```

### 3. iOS Mail-Style Rubber-Band (Spring Animation)

`SwipeToDismissBox` handles the rubber-band effect internally via `SpringAnimation`. To customize:
```kotlin
val dismissState = rememberSwipeToDismissBoxState(
    confirmValueChange = { ... },
    // Default positional thresholds:
    // - StartToEnd: 0.5f (50% of width triggers action)
    // - EndToStart: 0.5f
    // To make it easier to trigger: use 0.25f
    positionalThresholds = { totalDistance -> totalDistance * 0.25f }
)
```

### 4. Disable Swipe in Selection Mode

```kotlin
val isSelectionMode = selectedIds.isNotEmpty()

SwipeToDismissBox(
    enableDismissFromStartToEnd = !isSelectionMode,
    enableDismissFromEndToStart = !isSelectionMode,
    // When disabled, swipes still show background but don't trigger actions
    // Consider setting alpha on the card: Modifier.graphicsLayer { alpha = if (isSelectionMode) 0.6f else 1f }
) { ... }
```

## Complete NoteCard with Swipe

```kotlin
@Composable
fun SwipeableNoteCard(
    note: Note,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit,
    onTogglePin: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onTogglePin(); false }
                SwipeToDismissBoxValue.EndToStart -> { onDelete(); false }
                SwipeToDismissBoxValue.Settled -> false
            }
        },
        positionalThresholds = { it * 0.4f },
    )

    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        backgroundContent = { bgColorFor(dismissState.currentValue) },
        modifier = modifier,
    ) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
            selected = isSelected,
            color = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer
            else
                MaterialTheme.colorScheme.surface,
        ) {
            NoteCardContent(note)
        }
    }
}

@Composable
private fun bgColorFor(value: SwipeToDismissBoxValue): Color = when (value) {
    SwipeToDismissBoxValue.StartToEnd -> Color(0xFFFFB300)
    SwipeToDismissBoxValue.EndToStart -> Color(0xFFE53935)
    SwipeToDismissBoxValue.Settled -> Color.Transparent
}
```

## Alternative: SwipeToDismiss from Start (Archive pattern)

For archive action (swipe right to archive, similar to Gmail):
```kotlin
SwipeToDismissBox(
    state = dismissState,
    enableDismissFromStartToEnd = true,  // right swipe
    enableDismissFromEndToStart = false, // disable left swipe
    backgroundContent = { direction ->
        val bgColor = when (direction) {
            SwipeToDismissBoxValue.StartToEnd -> Color(0xFF43A047) // green for archive
            else -> Color.Transparent
        }
        Box(
            Modifier.fillMaxSize().background(bgColor),
            contentAlignment = Alignment.CenterStart
        ) {
            Icon(Icons.Default.Archive, "Archive", tint = Color.White)
        }
    },
) { cardContent }
```

## Testing

```kotlin
@Test
fun `swipe right triggers togglePin`() = runTest {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            assertEquals(SwipeToDismissBoxValue.StartToEnd, value)
            true
        }
    )
    // In real Compose test: performSwipe gesture and verify onTogglePin called
}
```

## Anti-patterns

- **Do NOT use `SwipeToDismiss`** (Material2) — it is deprecated. Use `SwipeToDismissBox` (Material3).
- **Do NOT call `onDelete()` inside the `LaunchedEffect` over dismiss state** — use `confirmValueChange` callback instead to keep the action atomic.
- **Do NOT use the same action for both directions** — users expect left=destructive, right=positive (Apple HIG convention).
- **Do NOT dismiss the item immediately** — keep it visible until the action is confirmed by the VM, then animate out via `animateItemDismiss()` if needed.

## Material3 Dependency

```kotlin
// libs.versions.toml
material3 = "1.4.0"  // minimum for SwipeToDismissBox

// shared/build.gradle.kts
commonMain.dependencies {
    implementation(libs.androidx.compose.material3)
}
```
