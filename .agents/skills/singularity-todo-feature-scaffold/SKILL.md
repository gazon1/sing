---
name: singularity-todo-feature-scaffold
description: Feature scaffold pattern for the Singularity Todo KMP app. Use when adding a new CRUD feature (tasks, notes, projects, tags, reminders, etc.). Documents the 7-file template (Ids.kt, *Domain.kt, *Repository.kt, *UseCase.kt, *ViewModel.kt, *Screen.kt, Ids.kt), DI registration in Modules.kt, navigation in Navigation.kt, and co-located Fake + ViewModel test. Follows the canonical flow: pure domain validation → repository (Result<T>) → use case (only real logic) → ViewModel (StateFlow + sealed Intent) → Screen.
---

# Feature Scaffold — Adding a New CRUD Feature

## Canonical 7-file pattern

For a feature named `<Feature>` (e.g., `Task`, `Note`, `Project`):

```
feature/<feature>/
├── Ids.kt                  — @JvmInline value class IDs
├── <Feature>Domain.kt      — pure domain logic, validation, domain model
├── <Feature>Repository.kt  — interface: suspend CRUD + Flow reads
├── <Feature>RepositoryImpl.kt — Room implementation
├── <Feature>UseCase.kt     — ONLY real logic (validation, clock.now(), build)
├── <Feature>ViewModel.kt   — StateFlow<SealedUiState>, sealed Intent
└── <Feature>Screen.kt     — Compose UI
```

> **Pass-through use cases are anti-pattern.** `Get<Feature>UseCase`, `Delete<Feature>UseCase`, `ToggleCompleteUseCase` that just delegate to `repo.X()` are boilerplate. Inject the repository directly into the ViewModel.

## 1. Ids.kt — Typed ID wrappers

```kotlin
package com.singularity.todo.feature.<feature>

import com.singularity.todo.core.error.AppError

@JvmInline
value class <Feature>Id private constructor(val value: String) {
    companion object {
        fun fromString(s: String): <Feature>Id {
            require(s.isNotBlank()) { AppError.Validation("Invalid ID") }
            return <Feature>Id(s)
        }
        fun generate(): <Feature>Id = <Feature>Id(ulid())
    }
}

fun String.to<Feature>Id(): <Feature>Id = <Feature>Id.fromString(this)
```

## 2. *Domain.kt — Pure domain logic

```kotlin
package com.singularity.todo.feature.<feature>

data class <Feature>(
    val id: <Feature>Id,
    val name: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val userId: UserId,
    // ... feature-specific fields
)

// Input DTO (for creation)
data class Create<Feature>Input(
    val name: String,
    val userId: UserId,
    // ... feature-specific fields
)

// Pure validation (no side effects, no database)
fun Create<Feature>Input.validate(): List<AppError.Validation> {
    val errors = mutableListOf<AppError.Validation>()
    if (name.isBlank()) errors.add(AppError.Validation("Name cannot be blank"))
    if (name.length > 100) errors.add(AppError.Validation("Name too long"))
    return errors
}
```

## 3. *Repository.kt — Interface

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

**Rules**:
- All mutators return `Result<Unit>` / `Result<T>`.
- All mutators are `suspend`.
- Reads return `Flow<T>` (never `List<T>` — so the UI updates reactively).
- No `*Blocking()` methods.

## 4. *RepositoryImpl.kt — Room implementation

```kotlin
class <Feature>RepositoryImpl(
    private val dao: <Feature>Dao,
    private val clock: Clock,
) : <Feature>Repository {

    override fun watchAll(userId: UserId): Flow<List<<Feature>>> =
        dao.watchAll(userId.value).map { list -> list.map { it.to<Feature>() } }

    override suspend fun create(<Feature>: <Feature>): Result<Unit> = runCatching {
        dao.upsert(<Feature>.toEntity())
    }

    // ... use Mappers.kt helpers for entity ↔ domain conversion
}
```

## 5. *UseCase.kt — ONLY real logic

**Keep only these use cases** (if they add real value beyond delegation):

```kotlin
// VALID: has domain logic + clock
class Create<Feature>UseCase(private val repo: <Feature>Repository, private val clock: Clock) {
    suspend operator fun invoke(input: Create<Feature>Input): Result<<Feature>Id> = runCatchingResult {
        input.validate().firstOrNull()?.let { throw it }
        val now = clock.now()
        val <feature> = <Feature>(
            id = <Feature>Id.generate(),
            name = input.name.trim(),
            createdAt = now,
            updatedAt = now,
            userId = input.userId,
        )
        repo.create(<feature>).getOrThrow()
        <feature>.id
    }
}
```

**DELETE these** (they are pure pass-through, not use cases):
- `Get<Feature>UseCase` → VM injects `Repo` directly
- `Delete<Feature>UseCase` → VM calls `repo.delete(id).getOrThrow()`
- `Update<Feature>UseCase` → VM calls `repo.update(entity).getOrThrow()`

## 6. *ViewModel.kt — State + Intent

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
    private val repo: <Feature>Repository,    // direct repo injection (not pass-through UC)
    private val create<Feature>: Create<Feature>UseCase,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val userId: Flow<UserId> = settings.userId.map { UserId.fromString(it) }

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

## 7. *Screen.kt — Compose UI

```kotlin
@Composable
fun <Feature>Screen(
    viewModel: <Feature>ViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        is <Feature>UiState.Loading -> CircularProgressIndicator()
        is <Feature>UiState.Content -> <Feature>List(items = s.items)
        is <Feature>UiState.Error -> ErrorMessage(s.message)
    }
}
```

## DI Registration (Modules.kt)

```kotlin
// Delete the pass-through use case factories:
// factory { Get<Feature>UseCase(get()) }       ← DELETE
// factory { Delete<Feature>UseCase(get()) }     ← DELETE

// Keep only real use cases:
factory { Create<Feature>UseCase(get(), get()) }

// Register VM — viewModelOf auto-resolves all constructor dependencies
viewModelOf(::FeatureViewModel)
```

If the VM has runtime parameters (e.g. `initialDueDate`), use `viewModel { }`:
```kotlin
viewModel { (initialDueDate: LocalDate?) ->
    TaskEditorViewModel(
        deps = TaskEditorDeps(...),
        initialDueDate = initialDueDate,
    )
}
```

## Navigation (Navigation.kt)

Add to the bottom nav items in `HomeTab()`:

```kotlin
sealed class BottomNavItem(val route: String, val title: String, val icon: String) {
    // ...
    data object <Feature> : BottomNavItem("feature", "<Feature>", Icons.Default.Folder)
}

// In the NavHost / when-based routing:
is BottomNavItem.<Feature> -> <Feature>Screen()
```

## Co-located Tests

```kotlin
// shared/src/jvmTest/kotlin/com/singularity/todo/feature/<feature>/<Feature>ViewModelTest.kt
class <Feature>ViewModelTest {
    private fun createVm(
        repo: <Feature>Repository = Fake<Feature>Repository(),
        createUseCase: Create<Feature>UseCase = Create<Feature>UseCase(repo, FixedClock()),
        settings: SettingsRepository = FakeSettingsRepository("user-1"),
    ) = <Feature>ViewModel(repo, createUseCase, settings)

    @Test fun `deletes item`() = runTest {
        val vm = createVm()
        vm.processIntent(<Feature>Intent.Delete(id))
        // assert...
    }
}
```

## Anti-patterns to avoid

1. **`runBlocking` in VM constructor** — use `userIdFlow: Flow<UserId>` + `combine(filter, userId) { ... }`
2. **Pass-through use cases** — `GetX`, `DeleteX`, `ToggleX` that do `repo.X().getOrThrow()`
3. **`require { throw ... }` inside the `require` lambda** — `require` itself throws; the lambda body never runs
4. **`java.io.File` directly** — use the `FileSystem` port
5. **MockK / Mockito** — use `Fake<Feature>Repository()`
