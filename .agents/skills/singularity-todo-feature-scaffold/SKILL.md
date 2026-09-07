---
name: singularity-todo-feature-scaffold
description: Feature scaffold pattern for the Singularity Todo KMP app. Use when adding a new CRUD feature (tasks, notes, projects, tags, reminders, etc.) or when extending an existing feature with a sub-repository (e.g. NoteTagRepository). Documents the 7-file template (Ids.kt, *Domain.kt, *Repository.kt, *UseCase.kt, *ViewModel.kt, *Screen.kt), DI registration in Modules.kt, navigation in AppDestination, and co-located Fake + ViewModel test. Follows the canonical flow: pure domain validation → repository (Result<T>) → use case (only real logic) → ViewModel (StateFlow + sealed Intent) → Screen. Also covers the Subinterface pattern for feature extensions.
---

# Feature Scaffold — Adding a New CRUD Feature

## Canonical 7-file pattern

For a feature named `<Feature>` (e.g., `Task`, `Note`, `Project`):

```
feature/<feature>/
├── Ids.kt                  — @JvmInline value class IDs + domain model
├── <Feature>Domain.kt      — pure domain logic, validation (optional)
├── <Feature>Repository.kt  — interface: suspend CRUD + Flow reads
├── <Feature>RepositoryImpl.kt — Room implementation
├── <Feature>UseCase.kt     — ONLY real logic (validation, clock.now(), build)
├── <Feature>ViewModel.kt   — StateFlow<SealedUiState>, sealed Intent
└── <Feature>Screen.kt     — Compose UI
```

> **Pass-through use cases are anti-pattern.** `Get<Feature>UseCase`, `Delete<Feature>UseCase`, `ToggleCompleteUseCase` that just delegate to `repo.X()` are boilerplate. Inject the repository directly into the ViewModel.

## 1. Ids.kt — Typed ID wrappers + domain model

```kotlin
package com.singularity.todo.feature.<feature>

@JvmInline
value class <Feature>Id private constructor(val value: String) {
    companion object {
        fun fromString(s: String): <Feature>Id = <Feature>Id(s)
        fun generate(): <Feature>Id = <Feature>Id(nextId())
    }
}

data class <Feature>(
    val id: <Feature>Id,
    val userId: UserId,
    val createdAt: Instant,
    val updatedAt: Instant,
    // ... feature-specific fields
)
```

## 2. *Repository.kt — Interface

```kotlin
package com.singularity.todo.feature.<feature>

interface <Feature>Repository {
    fun watchAll(userId: UserId): Flow<List<<Feature>>>
    fun watch(id: <Feature>Id): Flow<<Feature>?>
    suspend fun create(<Feature>: <Feature>): Result<Unit>
    suspend fun update(<Feature>: <Feature>): Result<Unit>
    suspend fun delete(id: <Feature>Id): Result<Unit>
}
```

**Rules:**
- All mutators return `Result<Unit>` / `Result<T>`.
- All mutators are `suspend`.
- Reads return `Flow<T>` (never `List<T>` — so the UI updates reactively).
- No `*Blocking()` methods.

## 3. *UseCase.kt — ONLY real logic

**Keep only these use cases** (if they add real value beyond delegation):

```kotlin
class Create<Feature>UseCase(private val repo: <Feature>Repository, private val clock: Clock) {
    suspend operator fun invoke(input: Create<Feature>Input): Result<<Feature>Id> = runCatchingResult {
        val now = clock.now()
        val <feature> = <Feature>(id = <Feature>Id.generate(), ..., createdAt = now, updatedAt = now)
        repo.create(<feature>).getOrThrow()
        <feature>.id
    }
}
```

**DELETE these** (pure pass-through, not use cases):
- `Get<Feature>UseCase` → VM injects `Repo` directly
- `Delete<Feature>UseCase` → VM calls `repo.delete(id).getOrThrow()`
- `Update<Feature>UseCase` → VM calls `repo.update(entity).getOrThrow()`

## 4. *ViewModel.kt — State + Intent

```kotlin
sealed interface <Feature>UiState {
    data object Loading : <Feature>UiState
    data class Content(val items: List<<Feature>>) : <Feature>UiState
    data class Error(val message: String) : <Feature>UiState
}

sealed interface <Feature>Intent {
    data class Delete(val id: <Feature>Id) : <Feature>Intent
    data class Update(val <feature>: <Feature>) : <Feature>Intent
}

class <Feature>ViewModel(
    private val repo: <Feature>Repository,
    private val create<Feature>: Create<Feature>UseCase,
    settings: SettingsRepository,  // for userId
) : ViewModel() {

    private val userId = settings.userId.map { UserId.fromString(it) }

    val state: StateFlow<<Feature>UiState> = userId
        .flatMapLatest { repo.watchAll(it) }
        .map { <Feature>UiState.Content(it) }
        .catch { emit(<Feature>UiState.Error(it.message ?: "Error")) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), <Feature>UiState.Loading)

    fun processIntent(intent: <Feature>Intent) = viewModelScope.launch {
        when (intent) {
            is <Feature>Intent.Delete -> repo.delete(intent.id).getOrThrow()
            is <Feature>Intent.Update -> repo.update(intent.<feature>).getOrThrow()
        }
    }
}
```

## 5. *Screen.kt — Compose UI

```kotlin
@Composable
fun <Feature>Screen(
    viewModel: <Feature>ViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is <Feature>UiState.Loading -> LoadingIndicator()
        is <Feature>UiState.Content -> <Feature>List(items = s.items)
        is <Feature>UiState.Error -> ErrorMessage(s.message)
    }
}
```

## DI Registration (Modules.kt)

```kotlin
// Modules.kt
factory { Create<Feature>UseCase(get(), get()) }
viewModelOf(::FeatureViewModel)
```

## Navigation (AppDestination.kt + AppNavHost.kt)

```kotlin
// AppDestination.kt
sealed interface AppDestination {
    // ...
    data object <Feature> : AppDestination
}

// AppNavHost.kt
composable<AppDestination.<Feature>> {
    <Feature>Screen(
        onNavigateTo<Feature> = { id -> navigator.navigate(AppDestination.<Feature>Detail(id)) }
    )
}
```

## Subinterface Pattern — when to split a Repository

When a feature grows to encompass multiple bounded contexts, split the repository into sub-interfaces registered separately in DI.

**Example: NoteTagRepository** (Phase 3)

Notes and tags have a many-to-many relationship. Instead of bloating `NotesRepository` with tag-specific methods, create a dedicated sub-interface:

```kotlin
// NoteTagRepository is a SEPARATE interface from NotesRepository
interface NoteTagRepository {
    fun watchTags(noteId: NoteId): Flow<List<Tag>>
    fun watchNotesForTag(tagId: TagId): Flow<List<Note>>
    suspend fun setTags(noteId: NoteId, tagIds: Set<TagId>): Result<Unit>
}

// NotesRepository stays focused on note CRUD:
interface NotesRepository {
    fun watchAll(userId: UserId): Flow<List<Note>>
    fun watchNote(id: NoteId): Flow<Note?>
    suspend fun createWithContent(...): Result<NoteId>
    suspend fun updateContent(...): Result<Unit>
    suspend fun softDelete(id: NoteId): Result<Unit>
    // Note: tag operations are NOT here — they live in NoteTagRepository
}
```

**Why separate?**
- `NoteTagRepository` has a different data access pattern (many-to-many cross-ref)
- Adding tag methods to `NotesRepository` would bloat it with cross-cutting concerns
- `FakeNoteTagRepository` can be tested independently
- DI bindings are cleaner: `single<NoteTagRepository> { RoomNoteTagRepository(get(), get()) }`

**DI registration:**
```kotlin
// NotesTagDiModule.kt (separate module for clarity)
fun notesTagModule(): Module = module {
    single<NoteTagRepository> { RoomNoteTagRepository(get(), get()) }
}
```

**Using both in a ViewModel:**
```kotlin
class NotesViewModel(
    private val notesRepo: NotesRepository,
    private val tagRepo: NoteTagRepository,
    // ...
) : ViewModel() {
    // tag operations via tagRepo, note operations via notesRepo
}
```

## When to use Subinterface vs extending the main Repository

| Situation | Pattern |
|---|---|
| Many-to-many relationship (notes ↔ tags) | Subinterface (`NoteTagRepository`) |
| Plugin-like extensibility (AI tools) | `@IntoSet` on individual tool classes |
| Read vs write concerns (watchAll vs mutate) | Keep in same repo, different method families |
| Different storage backends | Subinterface with different implementations |

## Co-located Tests

```kotlin
// shared/src/jvmTest/kotlin/com/singularity/todo/feature/<feature>/<Feature>ViewModelTest.kt
class <Feature>ViewModelTest {
    private fun createVm(
        repo: <Feature>Repository = Fake<Feature>Repository(),
        createUseCase: Create<Feature>UseCase = Create<Feature>UseCase(repo, FixedClock()),
    ) = <Feature>ViewModel(repo, createUseCase, FakeSettingsRepository("user-1"))

    @Test fun `deletes item`() = runTest {
        val vm = createVm()
        vm.processIntent(<Feature>Intent.Delete(id))
        // assert...
    }
}
```

## Anti-patterns to Avoid

1. **`runBlocking` in VM constructor** — use `userIdFlow: Flow<UserId>` + `combine(filter, userId) { ... }`
2. **Pass-through use cases** — `GetX`, `DeleteX`, `ToggleX` that do `repo.X().getOrThrow()`
3. **`require { throw ... }` inside the `require` lambda** — `require` itself throws; the lambda body never runs
4. **`java.io.File` directly** — use the `FileSystem` port
5. **MockK / Mockito** — use `Fake<Feature>Repository()`
6. **Adding tag/attachment operations to the main repository** — use subinterface instead
