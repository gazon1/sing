---
name: singularity-todo-sheet-extraction
description: Sheet extraction workflow for Singularity Todo. Use when an inline ModalBottomSheet / AlertDialog inside a Screen.kt needs to be extracted into a separate file, or when adding a new picker/dialog sheet to any feature. Covers the 4 sheet-state ownership patterns, routing intents for sheet-initiated navigation, callback bundle design for sheet hosts, and the common bugs found during the TaskEditor+ProjectDetail refactor (dead callbacks, unused parameters, double-brace lambdas).
---

# Sheet Extraction — From Inline to Separate File

## When to Extract

Extract an inline sheet when ANY of these is true:
- The sheet has ≥2 parameters
- The sheet appears in ≥1 other screen (shared sheet)
- The sheet has internal state (selection, query, etc.)
- The sheet has ≥20 lines of body
- You want to add `@Preview` for the sheet

If the sheet is a simple `AlertDialog` with one action and no state, inline is fine.

## Step-by-Step Extraction Workflow

### Step 1 — Identify the Sheet Type

Add a sealed variant to the screen's `ActiveSheet` sealed interface (or create one if it doesn't exist):

```kotlin
// feature/<feature>/presentation/components/<Feature>Sheets.kt
sealed interface <Feature>Sheet {
    data object PickColor : <Feature>Sheet
    data class PickItem(val currentId: ItemId?) : <Feature>Sheet
    data object ConfirmDelete : <Feature>Sheet
    data object ShowChildren : <Feature>Sheet
}
```

### Step 2 — Choose Sheet State Ownership Pattern

There are **4 patterns**, picked based on WHO needs to know when the sheet opens:

**Pattern A — Screen owns state (most common)**
```
Screen: val sheets = rememberDialogState<ActiveSheet>()
         sheets.show(ActiveSheet.PickColor)
         BottomSheetHost { <Feature>SheetsHost(sheets.active, currentContent, ...) }
```
Use when: only the screen reacts to the sheet result (typical for pickers).

**Pattern B — Content owns state (TaskEditor pattern)**
```
Screen: TaskEditorContent(model, callbacks)   ← no sheet state here
TaskEditorContent: var activeSheet by remember { mutableStateOf<Sheet?>(null) }
                   TaskEditorBody(model, callbacks, onShowSheet = { activeSheet = it })
                   TaskEditorSheetsHost(model, callbacks, activeSheet, ...)
```
Use when: the sheet state is purely an implementation detail of the content composable, and the screen doesn't need to know. `showSheet = {}` is passed to the screen and ignored.

**Pattern C — Content receives sheet state via parameter**
```
Content(model, callbacks, activeSheet, onShowSheet)
Screen: Content(model, callbacks, sheets.active, { sheets.show(it) })
```
Use when: the screen drives sheet state but the content needs to pass it deeper (e.g. into nested sections).

**Pattern D — Navigator in scope (navigation from sheet)**
```
Sheet: onNavigate: (ProjectId) -> Unit
Host: ChildProjectsSheet(children, onShowChildren = { nav.openDetail(it.id) }, onDismiss = ...)
```
Use when: the sheet initiates navigation. Pass the navigation lambda through the callback chain, not the navigator.

### Step 3 — Create the Sheet File

```kotlin
// feature/<feature>/presentation/components/<SheetName>Sheet.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <SheetName>Sheet(
    currentValue: CurrentValue?,
    onPick: (SelectedValue) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text("Title", style = MaterialTheme.typography.titleMedium)
            // ... sheet content
            Button(onClick = { onPick(selectedValue); onDismiss() }) {
                Text("Confirm")
            }
        }
    }
}
```

**Rules:**
- `onDismiss` is always a parameter — always called when the sheet is dismissed
- `onPick` is called WITH the selected value, then `onDismiss()` — don't call `onDismiss()` from inside `onPick`
- If the sheet doesn't pick anything (just shows info), omit `onPick` and use only `onDismiss`
- Add `@OptIn(ExperimentalMaterial3Api::class)` — all picker sheets use `ModalBottomSheet`

### Step 4 — Create the SheetsHost

```kotlin
// feature/<feature>/presentation/components/<Feature>SheetsHost.kt
@Composable
fun <Feature>SheetsHost(
    activeSheet: <Feature>Sheet?,
    currentContent: <Feature>Content?,
    parentOptions: List<ParentOption>,  // for pickers that need options
    onSheetDismiss: () -> Unit,
) {
    when (activeSheet) {
        null -> { /* no sheet */ }

        is <Feature>Sheet.PickColor -> ColorPickerSheet(
            currentColor = currentContent?.color ?: DefaultColor,
            onPick = { color -> currentContent?.onUpdateColor?.invoke(color); onSheetDismiss() },
            onDismiss = onSheetDismiss,
        )

        is <Feature>Sheet.PickItem -> ItemPickerSheet(
            currentId = currentContent?.itemId,
            options = parentOptions,
            onPick = { id -> currentContent?.onUpdateItem?.invoke(id); onSheetDismiss() },
            onDismiss = onSheetDismiss,
        )

        is <Feature>Sheet.ConfirmDelete -> ConfirmDeleteSheet(
            itemName = currentContent?.name ?: "",
            onConfirm = { currentContent?.onDelete?.invoke(); onSheetDismiss() },
            onDismiss = onSheetDismiss,
        )

        is <Feature>Sheet.ShowChildren -> ChildProjectsSheet(
            children = currentContent?.childProjects ?: emptyList(),
            onShowChildren = { child -> currentContent?.onNavigateToChild?.invoke(child.id) },
            onDismiss = onSheetDismiss,
        )
    }
}
```

### Step 5 — Wire Routing Intents for Navigation from Sheets

If a sheet triggers navigation (e.g. tapping a child project chip), use a **routing intent**:

**1. Add to `Routing` sealed interface:**
```kotlin
// <Feature>Intent.kt
sealed interface Routing : <Feature>Intent {
    data object OpenColorSheet : Routing
    data class NavigateToChild(val projectId: ProjectId) : Routing  // ← new
}
```

**2. Add action to the `@JvmInline value class` actions bundle:**
```kotlin
// <Feature>Actions.kt
@JvmInline
value class <Feature>Actions(private val block: (Routing) -> Unit) {
    fun onOpenColorSheet() = block(Routing.OpenColorSheet)
    fun onNavigateToChild(projectId: ProjectId) = block(Routing.NavigateToChild(projectId))  // ← new
}
```

**3. Handle in screen's action dispatcher:**
```kotlin
// <Feature>Content.kt
val actions = remember {
    <Feature>Actions { intent ->
        when (intent) {
            is Routing.OpenColorSheet -> sheets.show(<Feature>Sheet.PickColor)
            is Routing.NavigateToChild -> nav.openDetail(intent.projectId)  // ← navigator in screen, not sheet
            is Domain -> viewModel.onIntent(intent)
        }
    }
}
```

**4. Wire in `CurrentProjectContent` / content data class:**
```kotlin
CurrentProjectContent(
    ...
    onNavigateToChild = { id -> actions.onNavigateToChild(id) },  // ← nullable; sheet calls only if non-null
)
```

### Step 6 — The `CurrentContent` Data Class

For sheets that need both current values AND callbacks, bundle them in a content data class to avoid prop-drilling:

```kotlin
// Inside <Feature>SheetsHost.kt or its own file
data class Current<Feature>Content(
    val name: String,
    val color: Int,
    val itemId: ItemId?,
    val childProjects: List<Project>,
    // ── Callbacks (nullable = optional feature) ──
    val onUpdateColor: ((Int) -> Unit)?,
    val onUpdateItem: ((ItemId?) -> Unit)?,
    val onDelete: (() -> Unit)?,
    val onNavigateToChild: ((ProjectId) -> Unit)?,  // ← nullable: sheet checks before calling
    val onSetReminder: ((ReminderOffset) -> Unit)?, // ← nullable: unimplemented feature
)
```

**Rule: nullable callback fields** — if a sheet can function without a callback (e.g. informational only), make the callback nullable and check `?.invoke()` before calling.

## Common Bugs and How to Avoid Them

### Bug 1: `onClick` lambda in `RowCallbacks` is dead code

**Symptom:** `RowCallbacks(onChange = { ... }, onClick = { ... })` — `onClick` is never called because the body composable calls `onChange` instead.

**Fix:** Remove `onClick` from `RowCallbacks`. If you need a picker sheet, use the `onChange` callback to navigate to the picker, not to handle the row click.

```kotlin
// ❌ WRONG
data class RowCallbacks<T>(val onChange: (T) -> Unit, val onClick: () -> Unit = {})

// ✅ CORRECT — no onClick
data class RowCallbacks<T>(val onChange: (T) -> Unit, val onClear: (() -> Unit)? = null)
```

### Bug 2: Double-brace lambda

**Symptom:** `onDescriptionChange = { { vm.onIntent(...) } }` — the inner lambda is returned as a value but never called.

**Fix:** Single brace only.

```kotlin
// ❌ WRONG
onDescriptionChange = { { vm.onIntent(TaskDetailIntent.Domain.DescriptionChanged(it)) } }

// ✅ CORRECT
onDescriptionChange = { vm.onIntent(TaskDetailIntent.Domain.DescriptionChanged(it)) }
```

### Bug 3: Unused parameter in extracted sheet

**Symptom:** Sheet extracted with `onPick: (Project) -> Unit` but original inline sheet just called `onDismiss()`.

**Fix:** Match the extracted sheet's behavior to the original. If the original didn't navigate, the extracted one shouldn't either. If navigation IS needed, wire a routing intent (see Step 5 above).

### Bug 4: `showSheet` in callbacks is dead code after content takes ownership

**Symptom:** `TaskEditorCallbacks` had `showSheet: (TaskEditorSheet) -> Unit` but `TaskEditorContent` owns `activeSheet` internally and ignores it. All callers pass `showSheet = {}`.

**Fix:** Remove `showSheet` from callbacks. If external control is needed, pass `activeSheet` and `onShowSheet` directly to the content composable.

### Bug 5: Navigator not available in sheet host

**Symptom:** `ChildProjectsSheet` needed to navigate but `LocalProjectsNavigator` is only in scope inside `ProjectDetailContent`, not in `ProjectDetailSheetsHost`.

**Fix:** Pass a callback through `CurrentProjectContent.onNavigateToChild` → routing intent → screen's dispatcher → `nav.openDetail()`. Never pass the navigator to the sheet host.

### Bug 6: `availableTasks` passed to sheets host but never used

**Symptom:** `ProjectDetailSheetsHost` receives `availableTasks` parameter but the `QuickAddSheet.Picker` that needs it lives in `ProjectDetailQuickAddInput`, not in the sheets host.

**Fix:** Only pass parameters that the sheets host actually uses. If a sub-composable needs `availableTasks`, pass it directly to that composable, not through the sheets host.

### Bug 7: `TaskEditorSheet` import from wrong package

**Symptom:** Unresolved reference `TaskEditorSheet` because import path was `feature.tasks.presentation.components.detail.TaskEditorSheetHost` instead of `feature.tasks.presentation.components.TaskEditorSheetHost`.

**Fix:** Verify the correct import path — sheet hosts are in `feature/<feature>/presentation/components/`, not in the `detail/` subfolder.

## Sheet vs Dialog vs AlertDialog

| UI type | When to use | Example |
|---|---|---|
| `ModalBottomSheet` | Picker with scrollable content, large selection | Color, Icon, Tag, Project pickers |
| `AlertDialog` | Confirmation with 1-2 actions | Confirm delete, confirm archive |
| `BasicAlertDialog` | Custom content inside dialog | Date range picker |
| `BottomSheetHost` | Wraps sheets with standard chrome | All feature sheets |

## Preview Pattern for Sheets

```kotlin
@Preview
@Composable
private fun ColorPickerSheet_Preview() {
    MaterialTheme {
        ColorPickerSheet(
            currentColor = Color(0xFF4CAF50),
            onPick = { },
            onDismiss = { },
        )
    }
}
```

Previews live in the same file as the sheet (or in a `*Previews.kt` companion file if the sheet is large). They use `Fake*` repositories for domain types when needed.

## Relationship to Other Skills

| Skill | What it contributes |
|---|---|
| `singularity-todo-shared-ui-components` | `BottomSheetHost`, `OverlayState`, when to add to `core/ui/components/` |
| `singularity-todo-ui-event-vs-state` | Continuous state vs one-shot events — relevant for sheet result callbacks |
| `singularity-todo-top-bar-entry` | Adding IconButton that opens a sheet via routing intent |
| `singularity-todo-feature-scaffold` | Where sheets live in the layered layout (`presentation/components/`) |
| `singularity-todo-test-helpers` | `FakeRepositories` for sheet previews |
