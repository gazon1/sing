# Document-style decomposition (4-section rule)

Any detail screen (`XxxDetailScreen`) that follows the TickTick/Todoist document-style pattern must be decomposed into **exactly 4 sections**, never more. This limit is cognitive-load discipline: 4 sections fit comfortably in a senior engineer's mental model and are trivially testable.

### The 4 sections

| # | Section | Content | Example |
|---|---|---|---|
| 1 | **Hero** | Entity title (inline-editable), description placeholder, key identifier (checkbox, icon) | `ProjectHeroSection`, `TaskHeroSection` |
| 2 | **Meta chips** | Date, priority, status, tags — all as `FilterChip` in `FlowRow` | `ProjectMetaChipsRow`, `TaskMetaChipsRow` |
| 3 | **Body** | The entity's own content — tasks list, notes content, checklist | `ProjectTasksList`, `NoteBodySection` |
| 4 | **Bottom action bar** | Icon buttons for cross-cutting ops (Remind, Attach, Delete) + overflow menu | `ProjectBottomActionBar`, `TaskBottomActionBar` |

### What is NOT a section

Do **not** count as separate sections:
- `ActiveSheet` / bottom sheet routing (dialog overlay, not a screen section)
- `BottomAppBar` — the 4th section is the bottom bar itself; sub-components within it don't add to the count
- A "See all" link navigating to another screen — a navigation affordance within section 3, not a 5th section
- A `QuickAddInput` inline at the top of section 3 — still within section 3

### Rule: 4 sub-components maximum

A screen file should have no more than **4 private sub-composable functions** responsible for each of the 4 sections. If you write a 5th (e.g. separate "Tags row" extracted from "Meta chips"), consolidate: tags row + meta chips row = one `FlowRow` composable.

### Routing: `ActiveSheet` sealed interface

All pickers and confirmations are routed through a single `ActiveSheet` sealed interface on the screen — **not** a 5th section:

```kotlin
private sealed interface ActiveSheet {
    data object ConfirmDelete : ActiveSheet
    data class PickColor(val current: Int) : ActiveSheet
    data class PickIcon(val current: String?) : ActiveSheet
}

when (val sheet = sheetState.value) {
    is ActiveSheet.ConfirmDelete -> ConfirmDeleteSheet(onConfirm, onDismiss)
    is ActiveSheet.PickColor -> ColorPickerSheet(current = sheet.current, onPick, onDismiss)
    // ...
}
```

### Cross-feature navigation from detail screens

When a detail screen links to another entity (e.g. task → project), the navigation affordance must be **adjacent to the chip**, not on the chip:

```kotlin
// ✅ CORRECT — separate IconButton next to the chip
Row(verticalAlignment = CenterVertically) {
    FilterChip(project.name, ...)
    IconButton(Icons.AutoMirrored.Filled.ChevronRight, "Open project") {
        onNavigateToProject(project.id)
    }
}

// ❌ WRONG — combinedClickable on the chip
FilterChip(
    project.name,
    modifier = Modifier.combinedClickable(onClick = ..., onLongClick = ...)
)
```

`combinedClickable` conflicts with multi-select gestures (long-press enters selection mode). The chevron is a clear affordance that doesn't interfere. See `singularity-todo-cross-feature-navigation` skill.