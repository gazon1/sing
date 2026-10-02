---
name: singularity-todo-document-style-detail
description: Generic document-style UX pattern for any read-only detail screen (Task, Project, Note, etc.). Covers the 4-section anatomy (Hero + MetaChips + Body + BottomBar), the 4-section decomposition rule, ActiveSheet sealed routing, inline-edit via debounced MutableStateFlow, and the critical lesson that debounced edits must NOT emit Saved events (Saved-spam regression).
---

# Document-Style Detail Screen — Generic Pattern

This skill generalises the TickTick/Todoist document-style UX from `singularity-todo-task-detail-ux` to **any entity detail screen** (Project, Note, Tag, etc.). Use this when building or rewriting a detail screen for any entity that has metadata fields, inline-editable content, and action buttons.

See `singularity-todo-task-detail-ux` for the Task-specific version and the full TickTick reference screenshots.

## The 4-Section Anatomy

Every document-style detail screen follows this vertical structure:

```
┌────────────────────────────────────────┐
│  ←  Entity name (inline-edit)     ⋮    │  ← BackTopAppBar (no title in bar)
├────────────────────────────────────────┤
│  HERO BLOCK                            │  ← Section 1: color/icon + name + desc + progress
│  [🟦]  Entity name (inline-edit)       │
│        Description (inline-edit)        │
│        ● 3/12 done  ▓▓▓░░░░░░░░ 25%  │
├────────────────────────────────────────┤
│  META CHIPS ROW                        │  ← Section 2: date / priority / status chips
│  [📅 Due] [🚩 High] [📁 Project]       │
├────────────────────────────────────────┤
│  BODY SECTION                          │  ← Section 3: entity-specific content
│  Tasks / Checklist / Attachments / ...  │
│  [Sub-list with items]                 │
├────────────────────────────────────────┤
│  BOTTOM ACTION BAR                     │  ← Section 4: 4 icon buttons with badge counts
│  [🔔 1] [📎 2] [✏️] [🗑]           │
└────────────────────────────────────────┘
```

**Naming convention for sub-composables:**
- `XxxHeroSection` — Section 1
- `XxxMetaChipsRow` — Section 2
- `XxxBodySection` — Section 3 (or more specific: `XxxTasksList`, `XxxChecklistSection`, etc.)
- `XxxBottomActionBar` — Section 4

## The 4-Section Decomposition Rule

**Maximum 4 sub-components per detail screen.** If you find yourself writing more, consolidate:
- Icon + color → `XxxAppearanceSection`
- Parent + due date → `XxxOrganizationSection`
- See `singularity-todo-shared-ui-components` for the full rule.

**Anti-pattern:** splitting a detail screen into 6+ sub-composables. Only do this when each section genuinely has 3+ distinct data types / picker integrations. For most entities (Project, Tag), 4 is sufficient.

## ActiveSheet Sealed Interface

Bottom sheets and dialogs are routed using a single `ActiveSheet` (or equivalent) **sealed interface
as a type tag**, paired with `rememberOverlayState<ActiveSheet>()` on the screen.

**Routing state lives on the screen, not in the VM.** Use `rememberOverlayState<T>()` to hold
the current sheet — this replaces the pattern of one `MutableStateFlow<Sheet?>` per sheet in
the ViewModel, which is a well-known source of stale-cast bugs.

```kotlin
// Screen — owns the routing state
val sheetState = rememberOverlayState<ProjectDetailSheet>()

// Actions helper routes sheet open/close entirely on screen
val actions = remember {
    ProjectDetailActions { intent ->
        when (intent) {
            is ProjectDetailIntent.Routing.OpenColorSheet -> sheetState.show(ProjectDetailSheet.PickColor)
            is ProjectDetailIntent.Routing.OpenDeleteSheet -> sheetState.show(ProjectDetailSheet.ConfirmDelete)
            is ProjectDetailIntent.Routing.CloseSheet -> sheetState.hide()
            is ProjectDetailIntent.Domain -> viewModel.onIntent(intent)
        }
    }
}
```

The VM has **no** `MutableStateFlow<Sheet?>` and **no** `SharedFlow` routing events.
Sheet composables receive callbacks for domain actions (e.g. `onColorSelected: (Color) -> Unit`),
not routing commands.

See `routing-state-on-screen.md` (ADR 2026-10-02) for the full rationale and the
contradiction this resolves.

## Inline Edit Pattern (Debounced)

### The correct pattern

```kotlin
// ViewModel
private val _titleDraft = MutableStateFlow<String?>(null)
val titleDraft: StateFlow<String?> = _titleDraft.asStateFlow()

// In init block or onIntent:
viewModelScope.launch {
    _titleDraft
        .filterNotNull()
        .debounce(300)
        .collect { newTitle ->
            val current = state.value.contentOrNull()?.entity ?: return@collect
            updateEntity(current.copy(title = newTitle))
            // DO NOT emit Saved event here — silent save
        }
}
```

### The critical anti-pattern: Saved-spam (two flavours)

**NEVER emit `Saved` events from a debounced collector, and NEVER emit them from chip-setters either.**

**Flavour 1 — debounced collector** (the `TaskDetailViewModel` Regression 5 bug at `TaskDetailViewModel.kt:100`):

```kotlin
// ❌ WRONG — debounced collector emitting Saved on every keystroke
.onSuccess { _events.emit(TaskDetailUiEvent.Saved("Title updated")) } // SPAMS USER

// ✅ CORRECT — silent update, continuous state only
updateEntity(current.copy(title = newTitle))
_lastEditedAt.value = clock.now() // continuous state, formatted by UI
```

**Flavour 2 — chip-setters (setPriority, setProject, setTags, etc.):**

`TaskDetailViewModel` had 9 setter methods (`setTitle`, `setDescription`, `setDueDate`, `setDueTime`, `setPriority`, `setProject`, `setPinned`, `setTags`, `removeTag`) that ALL emitted `Saved` on every chip tap — not just on debounced keystrokes. This means every time a user tapped the Priority chip to change it, they got a "Saved" pulse.

The correct pattern for ALL setters in a detail ViewModel:

```kotlin
// ❌ WRONG — chip-setter emitting Saved on every tap
fun setPriority(current: Task, priority: TaskPriority) = scope.launch {
    updateTask(current.copy(priority = priority))
        .onSuccess { _events.emit(TaskDetailUiEvent.Saved("Priority updated")) } // SPAM!
        .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
}

// ✅ CORRECT — chip-setters are silent, like debounced edits
fun setPriority(current: Task, priority: TaskPriority) = scope.launch {
    updateTask(current.copy(priority = priority))
        .onSuccess { _lastEditedAt.value = kotlin.time.Clock.System.now() }
        .onFailure { _events.emit(TaskDetailUiEvent.Error(it.message ?: "Save failed")) }
}
```

`Saved` events should only be emitted for: explicit user confirmations (complete toggle, delete, archive), AI operations, and bulk actions.

### Silent save + relative-time feedback

Use a continuous `StateFlow<Instant?>` for last-edit timestamp, formatted by a pure helper:

```kotlin
// ViewModel
private val _lastEditedAt = MutableStateFlow<Instant?>(null)
val lastEditedAt: StateFlow<Instant?> = _lastEditedAt.asStateFlow()

private fun updateEntity(transform: (Entity) -> Entity) {
    viewModelScope.launch {
        runCatching { updateUseCase(current.id, transform) }
            .onSuccess { _lastEditedAt.value = clock.now() }
            .onFailure { _events.emit(UiEvent.Error(it.message)) }
    }
}
```

```kotlin
// Pure formatter (no Compose dependency)
fun formatSavedRelative(now: Instant, lastEditedAt: Instant?): String = when {
    lastEditedAt == null -> ""
    Duration.diff(now, lastEditedAt).inWholeSeconds < 5 -> "Saved just now"
    Duration.diff(now, lastEditedAt).inWholeMinutes < 1 -> "Saved ${Duration.diff(now, lastEditedAt).inWholeSeconds}s ago"
    Duration.diff(now, lastEditedAt).inWholeMinutes < 60 -> "Saved ${Duration.diff(now, lastEditedAt).inWholeMinutes}m ago"
    else -> ""
}
```

### When to emit Saved events

Emit `Saved` events **only** for:
- Explicit user action (Save button click, Delete confirmation, Archive toggle)
- Non-debounced operations

Never emit for:
- Debounced inline edits (title, description)
- Auto-save operations

## Cross-Feature Navigation

When a detail screen has a reference to another entity (e.g., a Task's project chip → opens ProjectDetail), see `singularity-todo-cross-feature-navigation` for the correct UX pattern. TL;DR: use an explicit `IconButton(Icons.AutoMirrored.Filled.ChevronRight, "Open $entity")` adjacent to the chip, not `combinedClickable` on the chip itself.

## Worked Examples

- **Task:** `feature/tasks/TaskDetailScreen.kt` + `TaskDetailViewModel.kt` (reference implementation)
- **Project:** `feature/projects/presentation/screen/ProjectDetailScreen.kt` (target after rework)

## Relationship to Other Skills

| Skill | What it contributes |
|---|---|
| `singularity-todo-task-detail-ux` | Task-specific worked example with full TickTick screenshots |
| `singularity-todo-shared-ui-components` | 4-section decomposition rule, shared widget catalogue |
| `singularity-todo-ui-event-vs-state` | Continuous vs one-shot event semantics |
| `ui-event-vs-state/routing-state` | Routing state on screen (not VM) — see ADR 2026-10-02 |
| `singularity-todo-inline-edit-saved-feedback` | Debounced edit + Saved-spam prevention (deep dive) |
| `singularity-todo-cross-feature-navigation` | Chip → detail navigation UX |
| `singularity-todo-sheet-extraction` | `ActiveSheet` sealed interface, `*SheetsHost` pattern, callback bundle design, routing intents for navigation-from-sheet |
| `docs/decisions/2026-10-02-routing-state-on-screen.md` | ADR resolving the routing-state location conflict |

## Anti-Patterns

1. **`combinedClickable` on a chip for navigation** — breaks the generic-widget contract; use explicit `IconButton` adjacent to chip.
2. **Saved events from debounced collectors** — spams users; use silent `_lastEditedAt` continuous state.
3. **Saved events from chip-setters** — spams users on every priority/project/tag chip tap; chip-setters must be silent too.
4. **>4 sub-composables** — consolidate into 4 sections; each sub-component should be ≥30 lines to justify a file.
5. **Emoji icons instead of Material Icons** — `Icons.Filled.*` only in production UI.
6. **Mixing unrelated refactors in one PR** — e.g. combining sheet API migration with Instant type migration. Keep PRs focused: one concern per PR. A type-system migration (Instant) mixed with a UI migration (sheets) creates massive scope and makes review impossible.
7. **Using a `ContentParams` data class instead of extending `XxxActions`** — creates 4-level nested hierarchy with no cohesion. Use `@JvmInline value class XxxActions` with sealed `Action` hierarchy instead. See `singularity-todo-task-callback-groups`.
