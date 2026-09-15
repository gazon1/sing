---
name: singularity-todo-draft-restoration
description: How to implement State Restoration for any form or editor screen in Singularity Todo. Use when adding a creation form, inline editor, or any screen where the user can lose work if the process is killed (LMK on Android). Documents the DraftStore port, the seed-if-empty restore pattern, debounce timing, clear-on-success/discard lifecycle, and per-profile isolation. Covers TaskCreateViewModel as the canonical reference.
---

# State Restoration — DraftStore + Debounce + Seed-if-Empty

When a user fills a long form and Android kills the app (LMK), the ViewModel state is lost. This skill covers how to persist form drafts so users never lose work.

## The pattern at a glance

```
User types → StateFlow → debounce(500ms) → DraftStore.save()
App launches → DraftStore.load() → if initial → restore
Save succeeds → DraftStore.clear()
User discards → DraftStore.clear()
```

## When to use

Add State Restoration when:
- A screen has editable fields (title, description, priority, etc.)
- The user can navigate away and return (or the process dies)
- Losing the user's input would be a poor experience

**Already covered:** `TaskCreateViewModel` has full State Restoration. Reuse the pattern for `NoteEditor`, `ProjectCreateViewModel`, `TagCreateViewModel`.

---

## 1. DraftStore — the persistence port

**Location:** `shared/src/commonMain/kotlin/com/singularity/todo/core/draft/`

### Interface

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/draft/DraftStore.kt
interface DraftStore {
    suspend fun <T> load(key: String, deserializer: DeserializationStrategy<T>): T?
    suspend fun <T> save(key: String, value: T, serializer: SerializationStrategy<T>)
    suspend fun clear(key: String)
}
```

### Production implementation

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/draft/DataStoreDraftStore.kt
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.singularity.todo.core.serialization.StableJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy

class DataStoreDraftStore(
    private val dataStore: DataStore<Preferences>,
) : DraftStore {
    override suspend fun <T> load(key: String, deserializer: DeserializationStrategy<T>): T? {
        val json = dataStore.data.first()[stringPreferencesKey(key)] ?: return null
        return runCatching {
            StableJson.decodeFromString(deserializer, json)
        }.getOrNull()
    }

    override suspend fun <T> save(key: String, value: T, serializer: SerializationStrategy<T>) {
        val json = StableJson.encodeToString(serializer, value)
        dataStore.edit { prefs -> prefs[stringPreferencesKey(key)] = json }
    }

    override suspend fun clear(key: String) {
        dataStore.edit { prefs -> prefs.remove(stringPreferencesKey(key)) }
    }
}
```

### Fake for tests

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/draft/FakeDraftStore.kt
class FakeDraftStore : DraftStore {
    private val map = mutableMapOf<String, String>()
    private val mutex = Mutex()

    override suspend fun <T> load(key: String, deserializer: DeserializationStrategy<T>): T? =
        mutex.withLock {
            map[key]?.let { json ->
                runCatching { StableJson.decodeFromString(deserializer, json) }.getOrNull()
            }
        }

    override suspend fun <T> save(key: String, value: T, serializer: SerializationStrategy<T>) =
        mutex.withLock {
            map[key] = StableJson.encodeToString(serializer, value)
        }

    override suspend fun clear(key: String) {
        mutex.withLock { map.remove(key) }
    }
}
```

---

## 2. StableJson — centralized serialization config

**Location:** `shared/src/commonMain/kotlin/com/singularity/todo/core/serialization/StableJson.kt`

All JSON encoding/decoding goes through this single config:

```kotlin
val StableJson: Json = Json {
    classDiscriminator = "_type"
    encodeDefaults = true
    ignoreUnknownKeys = true
}
```

Used by: `DraftStore`, `SyncEngine`, `BackupExporter`, `BackupImporter`.

---

## 3. DI registration

### CoreDiModule.kt — register DraftStore

```kotlin
// shared/src/commonMain/kotlin/com/singularity/todo/core/di/CoreDiModule.kt
single<DraftStore> { DataStoreDraftStore(get()) }
```

### Feature module — inject into ViewModel deps

```kotlin
// TasksDiModule.kt
viewModel { (initialDueDate: LocalDate?) ->
    TaskCreateViewModel(
        deps = TaskCreateDeps(
            createTask = get(),
            currentUser = get(),
            logger = get(),
            draftStore = get(),         // ← new
            autosaveScheduler = get(), // ← new
        ),
        initialDueDate = initialDueDate,
    )
}
```

---

## 4. The canonical reference — TaskCreateViewModel

### State class with @Serializable

```kotlin
@Serializable
@Immutable
data class TaskDraft(
    val title: String = "",
    val description: String = "",
    val priority: TaskPriority = TaskPriority.None,
    val dueDate: DueDateOption = DueDateOption.None,
    val dueTime: LocalTime? = null,
    val projectId: String? = null,
    val tagIds: List<String> = emptyList(),
)

@Serializable
sealed interface DueDateOption {
    @Serializable data object None : DueDateOption
    @Serializable data object Today : DueDateOption
    @Serializable data object Tomorrow : DueDateOption
    @Serializable data class Custom(val date: LocalDate, val label: String) : DueDateOption
}
```

### ViewModel init — restore + debounce

```kotlin
class TaskCreateViewModel(
    private val deps: TaskCreateDeps,
    initialDueDate: LocalDate?,
) : ViewModel() {

    private val initial: TaskDraft = TaskDraft(
        dueDate = initialDueDate?.let { DueDateOption.Custom(it, it.toString()) } ?: DueDateOption.None
    )

    private val _draft = MutableStateFlow(initial)
    private val _isSaving = MutableStateFlow(false)

    init {
        // 1. Restore — seed-if-empty pattern
        scope.launch {
            val key = "${deps.currentUser.current.value}:${TaskCreateDeps.DRAFT_KEY}"
            runCatching { deps.draftStore.load<TaskDraft>(key, TaskDraft.serializer()) }
                .getOrNull()
                ?.let { restored ->
                    if (_draft.value == initial) _draft.value = restored
                }
        }

        // 2. Debounced silent save loop
        scope.launch {
            _draft.drop(1)
                .debounce(500L)
                .collect { draft ->
                    val key = "${deps.currentUser.current.value}:${TaskCreateDeps.DRAFT_KEY}"
                    runCatching {
                        deps.draftStore.save(key, draft, TaskDraft.serializer())
                    }.onFailure { deps.logger.e("TaskCreate") { "draft save failed: $it" } }
                }
        }
    }
}
```

### Save — clear on success, skip on blank title

```kotlin
private suspend fun save() {
    val current = _draft.value
    if (current.title.isBlank()) {
        deps.logger.d("TaskCreate") { "save skipped: title is blank, draft preserved" }
        return
    }
    _isSaving.value = true
    try {
        val userId = deps.currentUser.current
        val input = toInput(current, userId)
        when (input) {
            is Either.Left -> deps.logger.e("TaskCreateViewModel") { "validation failed: ${input.error}" }
            is Either.Right -> {
                deps.createTask(input.value)
                    .onSuccess {
                        _saved.trySend(Unit)
                        scope.launch {
                            val key = "${userId.value}:${TaskCreateDeps.DRAFT_KEY}"
                            deps.draftStore.clear(key) // ← clear on success
                        }
                    }
                    .onFailure { e -> deps.logger.e("TaskCreateViewModel") { "save failed: $e" } }
            }
        }
    } finally {
        _isSaving.value = false
    }
}
```

### Discard — clear and reset

```kotlin
TaskCreateIntent.DiscardChanges -> {
    _draft.value = initial
    _isSaving.value = false
    scope.launch {
        val key = "${deps.currentUser.current.value}:${TaskCreateDeps.DRAFT_KEY}"
        deps.draftStore.clear(key)
    }
}
```

---

## 5. Key decisions

### Why debounce(500L) not AutosaveScheduler?

`AutosaveScheduler.awaitTick()` is a suspend function — it returns `Unit` after a real delay. It cannot be used with `Flow.debounce(suspend () -> Long)`. Use `debounce(500L)` directly for the Form draft.

For `NoteEditor` where you need testable delays, use `FakeAutosaveScheduler` with `trigger()`. For `TaskCreate` where the draft is not critical-path, `debounce(500L)` is simpler and sufficient.

### Why seed-if-empty?

```
_init_ launches two coroutines:
  A) Restore from DataStore (async, may complete later)
  B) Debounce loop (immediately subscribes to _draft)

If B processes _draft before A completes → B sees initial state and starts debouncing.
When A finally completes → A overwrites _draft with restored value.

seed-if-empty: "only restore if current value is still initial"
  → If user already typed (B processed first), A's restore is skipped.
  → If user hasn't typed yet (initial state), A's restore wins.
```

```kotlin
runCatching { deps.draftStore.load<TaskDraft>(key, TaskDraft.serializer()) }
    .getOrNull()
    ?.let { restored ->
        if (_draft.value == initial) _draft.value = restored  // ← seed-if-empty
    }
```

### Per-profile isolation

Draft key includes the user ID:

```kotlin
val key = "${deps.currentUser.current.value}:${DRAFT_KEY}"
// → "test-user:task_create_draft"
// → "ai-agent:task_create_draft"
```

`ProfileAwareCurrentUser.current` gives the profile-scoped user ID, so switching profiles automatically uses a different draft key.

---

## 6. Applying to another form

### Step 1 — Make the draft @Serializable

```kotlin
@Serializable
data class NoteDraft(
    val title: String = "",
    val body: String = "",
    // ...
)
```

### Step 2 — Add DraftStore + debounce to ViewModel init

```kotlin
private val _draft = MutableStateFlow(NoteDraft())
private val _isSaving = MutableStateFlow(false)

init {
    scope.launch {
        val key = "${deps.currentUser.current.value}:${NOTE_DRAFT_KEY}"
        runCatching { deps.draftStore.load<NoteDraft>(key, NoteDraft.serializer()) }
            .getOrNull()
            ?.let { if (_draft.value == NoteDraft()) _draft.value = it }
    }
    scope.launch {
        _draft.drop(1)
            .debounce(500L)
            .collect { draft ->
                val key = "${deps.currentUser.current.value}:${NOTE_DRAFT_KEY}"
                deps.draftStore.save(key, draft, NoteDraft.serializer())
            }
    }
}
```

### Step 3 — Clear on success/discard

```kotlin
// On success:
deps.draftStore.clear(key)
// On discard:
_draft.value = NoteDraft()
deps.draftStore.clear(key)
```

### Step 4 — DI: add DraftStore to feature module

```kotlin
// NoteDiModule.kt
factory { NoteEditorViewModel(get(), get(), get(), get(), get(), get()) }
```

### Step 5 — @Serializable for all nested types

Make sure all nested data classes and enums in the draft are `@Serializable`:

```kotlin
@Serializable
data class NoteDraft(
    val title: String = "",
    val color: NoteColor? = null,  // NoteColor must be @Serializable
    val tags: List<TagId> = emptyList(),  // TagId is @JvmInline value class — already serializable
)
```

---

## 7. Anti-patterns

### ❌ Don't use debounce with a supplier function

```kotlin
// WRONG — awaitTick() returns Unit, not Long
.debounce { deps.autosaveScheduler.awaitTick() }

// CORRECT — use a direct duration
.debounce(500L)
```

### ❌ Don't clear draft before checking blank title

```kotlin
// WRONG — draft cleared even when save is skipped
private suspend fun save() {
    deps.draftStore.clear(key)  // ← clears before checking title!
    if (current.title.isBlank()) return
    deps.createTask(...)
}

// CORRECT — clear only after confirmed success
.onSuccess { deps.draftStore.clear(key) }
```

### ❌ Don't restore without seed-if-empty

```kotlin
// WRONG — unconditionally overwrites user's typing with stale draft
_draft.value = restored  // ← overwrites even if user already typed

// CORRECT — only restore if user hasn't changed anything
if (_draft.value == initial) _draft.value = restored
```

### ❌ Don't use different keys for save and restore

```kotlin
// WRONG
val saveKey = "${userId}:draft"      // save uses this
val restoreKey = "${userId}:dra"     // restore uses different key → never finds saved draft

// CORRECT — same key everywhere
val key = "${userId}:draft"
```
