# DropdownMenu vs ModalBottomSheet vs AlertDialog

Three dismissal surfaces, one rule: **pick based on consequence severity, not screen real estate**.

| Surface | When to use | Example |
|---|---|---|
| **`DropdownMenu`** | ≤3 options, no complex content, anchored to a button | MoreVert overflow: Archive / Delete |
| **`ModalBottomSheet`** | >3 options, scrollable content, pickers (color, icon, date, parent) | Color picker, parent project picker, date picker |
| **`AlertDialog`** | Binary confirm/dismiss with irreversible consequence | Delete confirmation, archive confirmation |

### Decision tree

```
Does the action have irreversible consequences (delete, archive, discard)?
  → YES → AlertDialog (confirmButton is the dangerous action, dismissButton is safe)
  → NO  → How many distinct options/content blocks?
      → ≤3 options, simple labels → DropdownMenu (anchored to the triggering button)
      → >3 options OR scrollable content OR multi-step → ModalBottomSheet
```

### DropdownMenu — always anchored

```kotlin
Box {
    IconButton(onClick = { menuOpen = true }) {
        Icon(Icons.Filled.MoreVert, "More")
    }
    DropdownMenu(
        expanded = menuOpen,
        onDismissRequest = { menuOpen = false },
    ) {
        DropdownMenuItem(
            text = { Text(if (isArchived) "Unarchive" else "Archive") },
            onClick = { menuOpen = false; sheetState = ActiveSheet.ConfirmArchive },
        )
        DropdownMenuItem(
            text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
            onClick = { menuOpen = false; sheetState = ActiveSheet.ConfirmDelete },
        )
    }
}
```

**Rule:** `DropdownMenu` MUST be inside a `Box` with the `IconButton` as the anchor. Never use `Box` alone — `IconButton` provides correct touch target semantics.

### AlertDialog — for destructive actions

```kotlin
AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Delete project?") },
    text = { Text("\"$projectName\" will be deleted. This cannot be undone.") },
    confirmButton = {
        TextButton(onClick = onConfirm) {
            Text("Delete", color = MaterialTheme.colorScheme.error)
        }
    },
    dismissButton = {
        TextButton(onClick = onDismiss) { Text("Cancel") }
    },
)
```

**Rule:** `confirmButton` carries the dangerous action. `dismissButton` is always safe. Title says what will happen, not what button to press.

### ModalBottomSheet — for pickers and multi-option flows

```kotlin
ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.padding(24.dp)) {
        Text("Parent project", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = { onPick(null) }) { Text("None (root)") }
        LazyColumn {
            items(options) { opt ->
                FilterChip(
                    selected = opt.isCurrent,
                    onClick = { onPick(opt.id) },
                    label = { Text(opt.name) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
```

**Rule:** Use `LazyColumn` for lists >5 items. Use `TextButton` for "None (root)" option — it's not a chip, it's a separate action. Always add `Spacer(Modifier.height(24.dp))` at the bottom for keyboard clearance.

### Anti-patterns

- **DropdownMenu for >3 options** — creates an uncomfortably long list; use `ModalBottomSheet`
- **AlertDialog for color picker** — color grid needs scroll and clear/cancel; use `ModalBottomSheet`
- **ModalBottomSheet for delete confirmation** — overkill for binary choice; use `AlertDialog`
- **No `onDismissRequest`** on `ModalBottomSheet` — always provide a way to dismiss without choosing

---