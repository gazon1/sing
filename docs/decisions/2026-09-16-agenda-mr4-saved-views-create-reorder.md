---
description: MR4 — Saved Views: Create flow, Section reorder, Deep-link polish
tags: [agenda, saved-views, di, navigation3]
title: "ADR: AgendaEngine MR4 — Saved Views: Create + Reorder + Polish"
status: accepted
date: 2026-09-16
---

# ADR: AgendaEngine MR4 — Saved Views: Create + Reorder + Polish

## Context

The MR3 Saved Views UI introduced read-only display of saved agenda presets and a list/edit screen.
MR4 extends this with three capabilities:

1. **Create new saved view** — FAB on list, BookmarkAdd in top bar
2. **Section reorder** — drag-and-drop (stub, deferred)
3. **Deep-link polish** — ConfirmDelete, ConfirmDiscard, auto-pop NotFound

---

## Decision

### Rule 1: Single VM with `SavedAgendaScreenMode` sealed arg

The saved view editor runs on a single `SavedAgendaViewModel` parameterized by `SavedAgendaScreenMode`:

```kotlin
sealed interface SavedAgendaScreenMode {
    data class Edit(val viewId: SavedAgendaViewId) : SavedAgendaScreenMode
    data class Create(val seed: AgendaDefinition) : SavedAgendaScreenMode
}
```

The mode is a **runtime DI parameter** (`viewModel { (mode: SavedAgendaScreenMode) -> ... }`), not a constructor overload.
Routing (which screen to show) stays in the nav graph; the VM handles edit vs. create behavior.

**Rationale**: A single VM class avoids code duplication between edit and create paths. The sealed mode replaces the placeholder pattern (`flowOf(placeholder view)`) that was identified as fragile in the plan review.

### Rule 2: `Draft` data class with `initialized` + `isDirty` derivation

```kotlin
data class Draft(
    val name: String,
    val sections: List<Section>,
    val originalName: String,
    val originalSections: List<Section>,
    val initialized: Boolean = false,
) {
    val isDirty: Boolean
        get() = name != originalName || sections != originalSections
}
```

`isDirty` is derived from equality of current vs. original values — no separate mutable flag.
`initialized` gates seed-overwrite: subsequent repo emissions don't re-seed once the user has interacted.

**Rationale**: Prevents `_draft.value = seed` inside `combine` from clobbering user edits on the first repo emission.

### Rule 3: `SeedStore` singleton for Create mode

`SavedAgendaCreate` is a `data object` route — it cannot carry parameters.
The current `AgendaDefinition` is written to `SavedAgendaSeedStore` (a Koin `single`) before navigation:

```kotlin
// AgendaScreen
onSaveCurrentClick = {
    seedStore.setSeed(definition)
    navigator.openSavedAgendaCreate(definition)
}

// SavedAgendaViewModel (Create mode init)
val seed = seedStore.consumeSeed() ?: mode.seed
```

The store is cleared after first consumption. Navigation entry creates the VM with `mode.seed` as fallback (process death → fresh start).

**Rationale**: Data object routes are the canonical Navigation3 pattern for routes without payload. Side-channel store is a pragmatic workaround.

### Rule 4: LazyColumn key = `"${section.name}#${section.order}"`

When the stub `ReorderableSectionList` is replaced with drag-and-drop, section rows use a composite key:

```kotlin
itemsIndexed(sections, key = { _, s -> "${s.name}#${s.order}" })
```

After reorder, `order` values are updated and keys change → Compose treats as new items → animation plays correctly.

**Note**: `sh.calvin.reorderable` v3.x has **no KMP multiplatform artifact** (only `reorderable-jvm`, `reorderable-android`, etc.). Drag-and-drop deferred to future MR.

### Rule 5: Scaffold + explicit TopAppBar for back interception

`BackTopAppBar(onBack = { navigator.back() })` cannot be intercepted — the callback fires **after** back completes.
Replaced with explicit `Scaffold` + `TopAppBar` + `IconButton`:

```kotlin
IconButton(onClick = {
    if (editing?.draft?.isDirty == true) activeDialog = ActiveDialog.ConfirmDiscard
    else navigator.back()
})
```

**Rationale**: Allows the `isDirty` check before navigation, enabling ConfirmDiscard.

---

## Consequences

- `SavedAgendaSeedStore` is a global singleton — concurrent Create operations would race. Acceptable for current single-user model.
- `sh.calvin.reorderable` dependency deferred; `ReorderableSectionList` is a `LazyColumn` stub.
- Process death during Create: seed lost, returns to list. Acceptable — Create is not critical path.
- `SavedAgendaEditViewModel` → `SavedAgendaViewModel` rename propagates to all callers.

---

## Links

- MR3 ADR: `2026-09-17-agenda-mr3-saved-views-ui.md`
- DI pattern: `singularity-todo-koin-di`
- Navigation3: `singularity-todo-nav3-nested-graphs`, `singularity-todo-nav3-savedstate`
- VM pattern: `singularity-todo-vm-koin-scoping`, `singularity-todo-ui-event-vs-state`
