---
name: singularity-todo-top-bar-entry
description: 'Use when adding an IconButton to the TopAppBar that opens a nested screen (Saved Views, Search, Filters). Documents the slot-API pattern: IconButton lives in the Koin wrapper (has navigator), content stays VM-as-parameter (preview-friendly). Covers both top-bar entry and bottom-nav tab entry, with Nav3 entry registration.'
---

# Top-Bar IconButton Entry Point — Architecture

When a feature needs an entry point in the TopAppBar (like a "Saved Views" bookmark icon), there are two places it can live:

1. **In the Koin wrapper** (`AgendaScreen`) — the wrapper has `LocalAgendaNavigator`, so it can call `navigator.openSavedAgendaList()`
2. **In the content** (`AgendaContent`) — content has no navigator access, only receives callbacks

**Rule**: Navigator-dependent wiring → Koin wrapper. Content stays preview-friendly (VM-as-parameter).

## The Pattern

```
AgendaScreen.kt (Koin wrapper)          AgendaContent.kt (content)
┌──────────────────────────────┐      ┌──────────────────────────────┐
│ val navigator =               │      │ @Composable fun AgendaContent(
│   LocalAgendaNavigator.current│      │     state: AgendaUiState,
│                              │      │     onIntent: (AgendaIntent) -> Unit,
│ AgendaContent(               │  →   │     onSavedViewsClick: (() -> Unit)? = null,
│   state = state,             │      │     ...
│   onIntent = vm::onIntent,   │      │ ) {
│   onSavedViewsClick = {      │      │   IconButton(
│     navigator.openSavedAgendaList() │      │     onClick = { onSavedViewsClick?.invoke() },
│   },                        │      │   ) { Icon(Icons.Default.Bookmark) }
│ )                           │      │ }
└──────────────────────────────┘      └──────────────────────────────┘
```

**Why this way**: `AgendaContent` can be previewed without a navigator — it receives `onSavedViewsClick` as a nullable `() -> Unit` parameter, defaulting to `null` (button hidden). The VM-as-parameter pattern works for previews.

## Implementation Steps

### 1. Add callback parameter to content composable

```kotlin
// AgendaContent.kt
@Composable
fun AgendaContent(
    state: AgendaUiState,
    title: String,
    onIntent: (AgendaIntent) -> Unit,
    onSavedViewsClick: (() -> Unit)? = null,  // ← nullable, default null
    desktopContextMenuHost: @Composable ... = {},
    modifier: Modifier = Modifier,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = {
                    if (onSavedViewsClick != null) {
                        IconButton(onClick = onSavedViewsClick) {
                            Icon(Icons.Default.Bookmark, contentDescription = "Saved views")
                        }
                    }
                },
            )
        },
        ...
    ) { paddingValues ->
        // ...
    }
}
```

**Key**: `onSavedViewsClick: (() -> Unit)? = null` — nullable so the button only appears when the caller provides a handler. If it's null, the button is simply not rendered.

### 2. Pass callback from Koin wrapper

```kotlin
// AgendaScreen.kt — the Koin wrapper
@Composable
fun AgendaScreen(definition: AgendaDefinition, modifier: Modifier = Modifier) {
    val navigator = LocalAgendaNavigator.current  // ← navigator available here
    val viewModel: AgendaViewModel = koinViewModel { parametersOf(definition) }
    val state by viewModel.state.collectAsStateWithLifecycle()

    AgendaContent(
        state = state,
        title = vm.title,
        onIntent = vm::onIntent,
        onSavedViewsClick = { navigator.openSavedAgendaList() },  // ← pass navigator action
        modifier = modifier,
    )
}
```

### 3. Register entries in NavGraph

Both Android and JVM `AgendaNavGraph.*.kt` must register the new screens:

```kotlin
// In entryProvider { } block — both Android and JVM
entry<AgendaStartRoute.SavedAgendaList> { SavedAgendaListScreen() }
entry<AgendaStartRoute.SavedAgendaEdit> { route ->
    SavedAgendaEditScreen(viewId = SavedAgendaViewId.fromString(route.viewId))
}
```

For **Android only**: add serializers to `navSavedStateConfig`:

```kotlin
navSavedStateConfig(
    // ... existing serializers
    AgendaStartRoute.SavedAgendaList.serializer(),   // ← ADD
    AgendaStartRoute.SavedAgendaEdit.serializer(),   // ← ADD
)
```

### 4. Add Navigator methods

```kotlin
// AgendaNavigator.kt
open fun openSavedAgendaList() {
    backStack.add(AgendaStartRoute.SavedAgendaList)
}

open fun openSavedAgendaEdit(viewId: SavedAgendaViewId) {
    backStack.add(AgendaStartRoute.SavedAgendaEdit(viewId.raw))
}
```

## Bottom-Nav Tab Entry (vs Top-Bar Entry)

Use a **bottom-nav tab** when the destination is a primary top-level tab (Inbox, Today, Upcoming are already bottom tabs).

Use a **top-bar IconButton** when the destination is a secondary screen accessed from within a tab (Saved Views, Search, Filters).

| Entry type | Pattern | Example |
|---|---|---|
| Primary tab | `AppDestination.Inbox` → `AgendaNavGraph(start = Inbox)` | Bottom nav |
| Secondary screen | IconButton in TopAppBar → `SavedAgendaListScreen` | Top bar |
| Full-screen overlay | `ModalBottomSheet` or `Navigator.NavigateTo` | Filters |

## What NOT to Do

**Don't put the IconButton in the content composable** if it needs navigator access — that would require passing `LocalAgendaNavigator` into the content, breaking preview-ability.

**Don't make `onSavedViewsClick` non-nullable** — that forces previews to pass `{}` and the button appears in previews when it shouldn't.

**Don't forget `navSavedStateConfig` serializers on Android** — the app will crash on process death if the new route isn't registered.

## Reference

- `AgendaScreen.kt` — Koin wrapper with `onSavedViewsClick = { navigator.openSavedAgendaList() }`
- `AgendaContent.kt` — `onSavedViewsClick: (() -> Unit)? = null`
- `AgendaNavGraph.android.kt` / `AgendaNavGraph.jvm.kt` — entry registration
- `docs/decisions/2026-09-16-agenda-mr3-saved-views-ui.md` — decision record

## See Also

- `singularity-todo-nav3-nested-graphs` — NavGraph entry registration, serializer rules
- `singularity-todo-preview-with-koin` — VM-as-parameter pattern for previews
- `singularity-todo-vm-intent-pattern` — routing via callbacks vs routing intents
- `singularity-todo-sheet-extraction` — routing intents for navigation from sheets (e.g. `NavigateToChild`) |
