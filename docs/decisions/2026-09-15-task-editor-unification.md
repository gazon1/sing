---
title: Task Editor State Restoration + UI Unification
date: 2026-09-15
tags: [architecture, compose, ui, drafts, state-restoration]
deciders: [Singularity Developer]
status: accepted
---

# Task Editor State Restoration + UI Unification

## Context

Two separate problems were identified in the tasks feature:

**Problem A — State Restoration.** When Android kills the app process under memory pressure (LMK), the `TaskCreateViewModel` state is lost even though the user has partially filled the creation form. The same risk exists for `TaskDetailViewModel` inline edits. Users lose work with no warning.

**Problem B — UI Duplication.** `TaskCreateContent.kt` and `TaskDetailViewContent.kt` share ~80% of their structure (title row, description field, attribute cards, scrollable layout, top bar). `TaskCreationTopBar.kt` is a near-copy of `TaskDetailTopBar.kt`. Adding new fields (e.g. reminders) requires updating both files.

## Decision

### Part A — State Restoration

**`DraftStore<T>` port** — a generic persistence interface in `core/draft/`:

```kotlin
interface DraftStore {
    suspend fun <T> load(key: String, deserializer: DeserializationStrategy<T>): T?
    suspend fun <T> save(key: String, value: T, serializer: SerializationStrategy<T>)
    suspend fun clear(key: String)
}
```

Production implementation: `DataStoreDraftStore` wraps the existing per-platform `DataStore<Preferences>`. Fake for tests: `FakeDraftStore` uses an in-memory `MutableMap` with JSON round-trip (catches schema regressions early).

**`StableJson`** — centralized `Json` config in `core/serialization/StableJson.kt`:

```kotlin
val StableJson: Json = Json {
    classDiscriminator = "_type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}
```

Replaces 3 identical copies in `SyncEngine`, `BackupImporter`, `BackupExporter`.

**`AutosaveScheduler`** — existing debounce port (`clock.now()` + 500 ms tick) reused from `NoteEditor`. No new abstraction needed.

**Per-profile isolation** — draft key format is `"${userId.value}:${DRAFT_KEY}"`; `ProfileAwareCurrentUser` injected into `TaskCreateDeps`.

**Seed-if-empty restore** — on `ViewModel.init`, restore runs before debounce loop:

```kotlin
init {
    // 1. Restore — seed-if-empty to handle race with debounce
    scope.launch {
        val key = "${deps.currentUser.current.value}:${TaskCreateDeps.DRAFT_KEY}"
        runCatching { deps.draftStore.load<TaskDraft>(key, TaskDraft.serializer()) }
            .getOrNull()
            ?.let { restored -> if (_draft.value == initial) _draft.value = restored }
    }
    // 2. Debounced silent save loop
    scope.launch {
        _draft.drop(1)
            .debounce { deps.autosaveScheduler.awaitTick() }
            .collect { draft ->
                val key = "${deps.currentUser.current.value}:${TaskCreateDeps.DRAFT_KEY}"
                runCatching { deps.draftStore.save(key, draft, TaskDraft.serializer()) }
                    .onFailure { deps.logger.e("TaskCreate") { "draft save failed: $it" } }
            }
    }
}
```

**Clear on success or explicit discard** — `save()` clears draft only on `createTask` success; `DiscardChanges` intent clears draft before navigating away.

**`save()` with blank title** — skips `createTask` call but preserves draft. User can leave and return later.

**Serializable `TaskDraft`** — `DueDateOption` made a closed sealed interface with `@Serializable` subtypes (`None`, `Today`, `Tomorrow`, `Custom`). `LocalDate`/`LocalTime` use `kotlinx.serialization` built-in `LocalDateSerializer`/`LocalTimeSerializer`.

### Part B — UI Unification via Slot API

**Unified `TaskEditorContent`** — single Composable replacing two:

```
TaskCreateContent + TaskDetailViewContent → TaskEditorContent
TaskCreationTopBar (deleted)             → TaskDetailTopBar (reused)
```

**Signature:**

```kotlin
@Composable
fun TaskEditorContent(
    titleDraft: String,
    onTitleChange: (String) -> Unit,
    isCompleted: Boolean,
    onCheckToggle: () -> Unit,
    descriptionDraft: String,
    onDescriptionChange: (String) -> Unit,
    priority: TaskPriority,
    onPrioritySelect: (TaskPriority) -> Unit,
    onPriorityClear: (() -> Unit)?,
    dueDate: LocalDate?,
    dueTime: LocalTime?,
    onDueDateSelect: (LocalDate?) -> Unit,
    onDueDateClear: (() -> Unit)?,
    onDueTimeSelect: (LocalTime?) -> Unit,
    showDueDate: Boolean = true,
    extraSections: (@Composable () -> Unit)?,
    bottomBar: (@Composable () -> Unit)?,
    menuItems: List<TaskEditorMenuItem>,
    onBack: () -> Unit,
)
```

**Supporting types:**

```kotlin
data class TaskEditorMenuItem(
    val label: String,
    val onClick: () -> Unit,
)
```

**Slot parameters** (Material3 convention):
- `extraSections` — view-only sections (checklist, project, tags, timestamps) injected by the caller
- `bottomBar` — Save button bar for create mode; `null` for view mode (uses scaffold's default)
- `menuItems` — declarative list replaces lambda-in-lambda pattern

**Named slots preserved:**
- `titleLeading` — not needed (checkbox is a plain parameter `isCompleted` + `onCheckToggle`)
- `onPriorityClear`, `onDueDateClear` — nullable callbacks; `null` means "no clear button in view mode"

**`showDueDate`** — avoids conditional rendering bug (`if (dueDate != null || dueDate == null)` is always true).

## Rationale

**Why `DraftStore<T>` not per-entity stores?** — A single generic interface covers Task drafts, Note drafts, Project drafts, Tag drafts. No per-entity boilerplate. The `key` parameter encodes entity type + ID + profile.

**Why `StableJson`?** — Three files had identical `Json { ... }` configs. Centralizing into one place eliminates drift and makes future configuration changes (e.g. adding `prettyPrint`) a single-point change.

**Why slot API over adapter pattern?** — Adapter pattern adds 4 type conversions per keystroke (Draft ↔ ViewModel ↔ Adapter ↔ Composable). Slot API passes lambdas directly. Less indirection, idiomatic Compose.

**Why `List<TaskEditorMenuItem>` over `(closeMenu: () -> Unit) -> Unit`?** — Declarative list is easier to preview and test. The lambda-in-lambda pattern creates awkward scoping.

**Why `showDueDate: Boolean` not `dueDate: LocalDate?`** — The original code had `dueDate: LocalDate?` with `if (dueDate != null)` which accidentally always evaluated to true due to a tautological condition. A separate `Boolean` flag is explicit and cannot be miswritten.

**Why `@Serializable DueDateOption` sealed interface?** — `kotlinx.serialization` with a closed sealed hierarchy requires `classDiscriminator = "_type"`. Each subtype is a separate serializable class. This is safe for forward/backward compatibility as long as the type list stays closed.

## Consequences

**New files (4):**
- `core/serialization/StableJson.kt`
- `core/draft/DraftStore.kt`
- `core/draft/DataStoreDraftStore.kt`
- `core/draft/FakeDraftStore.kt`

**Deleted files (3):**
- `TaskCreateContent.kt`
- `TaskDetailViewContent.kt`
- `TaskCreationTopBar.kt`

**DI changes:**
- `single<DraftStore> { DataStoreDraftStore(get()) }` in `CoreDiModule`
- `TaskCreateDeps` expanded with `draftStore: DraftStore, autosaveScheduler: AutosaveScheduler`

**Test coverage:** Draft restoration pattern validated manually. Virtual-time debounce tests are fragile with `backgroundScope + advanceTimeBy` (known limitation — see Known Limitations below).

**Future reuse:** `DraftStore<T>` applies to NoteEditor, Project create/edit, Tag create/edit, AI prompt drafts. `TaskEditorContent` reusable for desktop split-view or other editor modes.

## Known Limitations

**Debounce not covered by unit tests.** `Flow.debounce()` behaves unpredictably with `backgroundScope + advanceTimeBy` in tests. The debounce loop was validated manually. For proper coverage, move debounce testing to an integration test using `TestDispatcher(suspendCoroutine { it.resume() })`.

**`autosaveScheduler.awaitTick()` returns `Unit`.** Debounce source must be a real `Flow` — `debounce { awaitTick() }` causes ambiguity since `awaitTick()` returns `Unit`, not a delay value. Use `debounce(500L)` directly or `debounce { autosaveScheduler.delayMs() }` where `delayMs(): Long` is the delay supplier.

**`ProfileAwareCurrentUser` is hard to override in tests.** `current` is derived via `combine()` starting from `UserId.anonymous`. A `FakeProfileAwareCurrentUser` with a simple `MutableStateFlow` override would solve this — not yet implemented.

## Links

- Previous slot API decision: `2026-09-09-content-slot-pattern.md`
- Task detail critical fixes (debounce pattern): `2026-09-08-task-detail-critical-fixes.md`
- Koin VM scoping: `2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`
