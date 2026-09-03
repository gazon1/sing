# Architecture — Singularity Todo KMP

> Полная архитектура приложения. Краткий cheatsheet — `AGENTS.md`.

---

## 1. Карта пакетов

### `core/` — инфраструктура (ports & adapters)

| Пакет | Назначение |
|---|---|
| `core/auth/` | Supabase session, `SessionStore`, `AuthRepository` |
| `core/attachments/` | Attachment entity, DAO, repository, storage/upload ports |
| `core/backup/` | BackupCodec, BackupExporter/Importer, migration chain, DSL builders |
| `core/database/` | Room entities, DAOs, migrations, **Mappers.kt** (R5) |
| `core/di/` | `Modules.kt` (domainModule), PlatformModule (expect/actual) |
| `core/error/` | `AppError` sealed, `runCatchingResult` |
| `core/files/` | `FileSystem` port, `MimeTypes`, `FileChecksum` |
| `core/network/` | Ktor client config (CIO/OkHttp) |
| `core/notifications/` | `NotificationPort` (expect/actual) |
| `core/platform/` | `Clock`, `PlatformContext` (expect/actual) |
| `core/security/` | `SecureStoragePort` (expect/actual: secret-tool/AES-GCM + EncryptedSharedPreferences) |
| `core/settings/` | `SettingsRepository` (DataStore) |
| `core/sync/` | HLC, SyncEngine, ConflictResolver, SyncOutbox, SupabaseSyncApiClient |
| `core/ui/` | Theme, shared components |

### `feature/` — UI-фичи (вертикали)

| Пакет | ViewModel | Repository | Screen |
|---|---|---|---|
| `tasks/` | `TasksViewModel` | `TaskRepository` | `TasksScreen` |
| `notes/` | `NotesViewModel` | `NotesRepository` + `NotesStore` | `NotesScreen` + `NoteEditorScreen` |
| `projects/` | `ProjectsViewModel` | `ProjectsRepository` | `ProjectsScreen` |
| `tags/` | `TagsViewModel` | `TagsRepository` | `TagsScreen` |
| `search/` | — (composable) | — | `SearchScreen` |
| `ai/` | — | — | `ChatScreen` (Koog agent) |
| `auth/` | `AuthViewModel` | `AuthRepository` | `LoginScreen` |
| `settings/` | `SettingsViewModel` | `SettingsRepository` | `SettingsScreen` + 5 sub-screens |
| `backup/` | `BackupViewModel` | `BackupRepository` | `BackupScreen` |
| `attachments/` | `AttachmentsViewModel` | `AttachmentRepository` | `AttachmentButton/Sheet/Tile/Thumbnail` |
| `reminders/` | — | `ReminderRepository` | `ReminderPicker`, `ReminderTile` |
| `nav/` | — | — | `Navigation.kt` (`HomeTab`) |

---

## 2. Поток данных (Screen → VM → UseCase → Repo → DAO)

```
Compose Screen (Screen.kt)
    ↓ user intent
ViewModel (*ViewModel.kt) — StateFlow<SealedUiState>, sealed Intent
    ↓
UseCase (*UseCase.kt) — только реальная логика (валидация, clock.now(), build)
    ↓
Repository (*Repository.kt) — интерфейс: suspend CRUD + Flow reads
    ↓
Room DAO (*Dao) + SQLite (jvmMain: sqlite-jdbc, androidMain: sqlite-bundled)
```

---

## 3. expect/actual таблица

| Порт (commonMain) | jvmMain | androidMain |
|---|---|---|
| `SecureStoragePort` | `JvmSecureStorage` (secret-tool + AES-GCM) | `AndroidSecureStorage` (EncryptedSharedPreferences) |
| `NotificationPort` | `JvmNotificationPort` (notify-send + at) | `AndroidNotificationPort` (AlarmManager + NotificationManager) |
| `FileSystem` | `JvmFileSystem` | `AndroidFileSystem` |
| `BackupCodec` | `JvmBackupCodec` (java.util.zip) | `AndroidBackupCodec` (java.util.zip) |
| `MarkdownHtmlPort` | `RichEditorMarkdownHtmlPort` | — (shared) |
| `createKoogPromptExecutor()` | `JvmKoogFactory` (MultiLLMPromptExecutor + OpenAILLMClient) | `AndroidKoogFactory` (error stub) |
| `Clock` | — (kotlinx-datetime same) | — |
| `PlatformContext` | `JvmPlatformContext` | `AndroidPlatformContext` |

---

## 4. Sync Pipeline

```
SyncableEntity.mutate() → HLC timestamp → SyncOutbox.enqueue()
                                                    ↓
                            SyncEngine.poll() (60s interval)
                                                    ↓
                            SyncApi.push() → Supabase PostgREST
                                                    ↓
                            SyncApi.pull() ← Supabase PostgREST
                                                    ↓
                            ConflictResolver.merge() → Room upsert
```

**HLC** (`Hlc.kt`): Hybrid Logical Clock — физическое время + логический счётчик. Гарантирует каузальный порядок без синхронизации часов.

**ConflictResolver**: Last-Writer-Wins по HLC, tiebreaker — `serverVersion`.

**SyncOutbox**: Room-очередь pending мутаций. Операции: `CREATE`, `UPDATE`, `DELETE`.

**SupabaseSyncApiClient**: REST push/pull (PostgREST, не realtime).

---

## 5. Backup Pipeline

```
BackupExporter: DAOs → DTOs → JSON payload → manifest → BackupCodec.export() → ZIP file
BackupImporter: ZIP file → BackupCodec.import() → validate → migrate (Map chain) → DAOs upsert
```

**Dual versioning**: `FORMAT_VERSION` (ZIP container) + `SCHEMA_VERSION` (payload schema).

**Migration chain**: `Map<Int, (JsonObject) → JsonObject>` — каждая версия применяется последовательно через `fold()`.

**DSL builders**: `@DslMarker` + `ExportOptionsBuilder` / `ImportOptionsBuilder`.

---

## 6. AI Pipeline (Koog 1.1.1)

```
ChatScreen → KoogAgentService → AIAgent.builder()
    + ToolRegistry(16 SimpleTool<T>)
    + PromptExecutor (JVM only)
    + system prompt (Prompts.kt)
    + user message
→ LLM response → tools → JSON output → UseCase.decode → Result<String>
```

**16 tools** (registered via `@IntoSet` → `Set<Tool<*, *>>`):
Read-only: `GetNoteTool`, `GetProjectTool`, `GetTaskTool`, `ListTasksTool`, `ListLinkedTasksTool`, `SearchTasksTool`.
LLM-powered: `RefineTaskTool`, `SmartRewriteTool`, `GenerateDescriptionTool`, `DecomposeTaskTool`, `GenerateChecklistTool`, `PickTimeTool`, `ClusterTasksTool`, `ClusterNotesTool`, `ProjectReviewTool`, `WeeklyPlanTool`.

**Koog PromptExecutor** — `expect/actual`; JVM реализация использует `MultiLLMPromptExecutor` + `OpenAILLMClient` + OkHttp. Android бросает `error("...")`.

---

## 7. Тестовая стратегия

### Source sets

| Source set | Расположение | Запуск |
|---|---|---|
| `commonTest` | `shared/src/commonTest/` | `./gradlew :shared:commonTest` |
| `jvmTest` | `shared/src/jvmTest/` | `./gradlew :shared:jvmTest` |
| `androidHostTest` | `shared/src/androidHostTest/` | `./gradlew :shared:testAndroidHostTest` |

### Принципы

1. **Fake вместо MockK/Mockito** — `FakeTaskRepository`, `FakeNotesRepository`, `FakeSettingsRepository`, `FakeSecureStorage`, `FakeNotificationPort`, `FakeTextGen`. Все в `test/fakes/FakeRepositories.kt`.

2. **`runTest` + `UnconfinedTestDispatcher`** — для testing coroutines без virtual time. Для VM с `viewModelScope` — `StandardTestDispatcher` + `advanceUntilIdle()`.

3. **Constructor injection** — все зависимости VM передаются через конструктор. Это позволяет создать VM с fake-реализациями без Koin.

4. **`FakeSettingsRepository`** — позволяет настроить `userId` и другие settings перед тестом:
   ```kotlin
   private val fakeSettings = FakeSettingsRepository { "user-1" }
   private val vm = NotesViewModel(FakeNotesStore(), FakeHtmlPort(), fakeSettings)
   ```

5. **Turbine** — для assertions на `Flow<T>`:
   ```kotlin
   vm.state.test {
       assertEquals(NotesUiState.Loading, awaitItem())
       assertIs<NotesUiState.Content>(awaitItem())
   }
   ```

6. **Clock injection** — вместо `Clock.System`:
   ```kotlin
   val fixedClock = Clock.fixed(Instant.parse("2026-09-03T12:00:00Z"), TimeZone.UTC)
   val repo = TaskRepositoryImpl(dao, fixedClock)
   ```

---

## 8. Gradle-команды для верификации

```bash
# Все тесты
./gradlew :shared:jvmTest :shared:commonTest :shared:testAndroidHostTest

# Android
./gradlew :androidApp:assembleDebug

# Desktop
./gradlew :desktopApp:run           # headed
./gradlew :desktopApp:jvmTest        # JVM UI test (без окна)

# Локальный check (lint + тесты)
./check.sh

# Конкретный тест
./gradlew :shared:jvmTest --tests "com.singularity.todo.feature.tasks.*"
```

---

## 9. Gradle-специфика

### `resolutionStrategy` в двух местах

**Root `build.gradle.kts`** — глобальные pins:
```kotlin
if (requested.group == "org.jetbrains.kotlin") useVersion("2.4.10")
if (requested.name == "kotlinx-serialization-json") useVersion("1.11.0")
```

**`shared/build.gradle.kts`** — локальные pins:
```kotlin
if (requested.name == "atomicfu") useVersion("0.23.1")
if (requested.name == "kotlinx-collections-immutable") useVersion("0.3.7")
```

### Room schema location

Схема экспортируется в `shared/schemas/` — включать в VCS.

---

## 10. Kotlin Best Practices в этом проекте

### 10.1. Sealed interface для UiState

```kotlin
// Правильно (exhaustive, без else)
sealed interface TasksUiState {
    data object Loading : TasksUiState
    data class Content(val tasks: List<Task>) : TasksUiState
    data class Error(val message: String) : TasksUiState
}

// Не правильно (data class + else branch)
sealed class TasksUiState
class Loading : TasksUiState()
```

### 10.2. Result<T> + runCatchingResult

```kotlin
// Правильно
suspend fun create(input: CreateTaskInput): Result<TaskId> = runCatchingResult {
    TasksDomain.validateTitle(input.title)
    val task = TasksDomain.buildTask(input, clock.now())
    repo.create(task).getOrThrow()
    task.id
}

// Не правильно: try-catch вручную
try {
    repo.create(task)
    Result.success(task.id)
} catch (e: Exception) {
    Result.failure(e)
}
```

### 10.3. Value class для ID

```kotlin
@JvmInline
value class TaskId(val value: String) {
    companion object {
        fun fromString(s: String): TaskId = TaskId(s)
        fun generate(): TaskId = TaskId(ulid())
    }
}
```

### 10.4. require без throw в лямбде

```kotlin
// Баг (тело лямбды никогда не выполняется):
require(name.isNotBlank()) { throw AppError.Validation("blank") }

// Правильно:
require(name.isNotBlank()) { AppError.Validation("blank") }
```

### 10.5. Flow vs List для reads

```kotlin
// Правильно (Flow — реактивное обновление UI)
interface TaskRepository {
    fun watchTasks(userId: UserId, filter: TaskFilter): Flow<List<Task>>
}

// Не правильно (List — нет реактивности)
fun getTasks(): List<Task>
```

### 10.6. Clock injection

```kotlin
// Не правильно (hard-coded):
val now = Clock.System.now()

// Правильно (injected):
class CreateTaskUseCase(private val repo: TaskRepository, private val clock: Clock) {
    suspend operator fun invoke(input: CreateTaskInput): Result<TaskId> = runCatchingResult {
        val now = clock.now()  // подменяется в тестах на Clock.fixed()
    }
}
```

### 10.7. reified для type-safe JSON

```kotlin
// Правильно
inline fun <reified T> Json.decode(value: String): T =
    decodeFromString(serializer<T>(), value)

// Не правильно (erased type):
fun <T> decode(value: String, cl: Class<T>): T
```

### 10.8. Sealed interface для domain errors

```kotlin
// Правильно (compile-time exhaustiveness)
sealed interface AppError {
    data class Validation(val message: String) : AppError
    data object NotFound : AppError
    data class Network(val cause: Throwable) : AppError
}

// use case:
when (val result = repo.create(task)) {
    is Result.success -> ...
    is Result.failure -> when (val err = result.exceptionOrNull()) {
        is AppError.Validation -> showValidation(err.message)
        is AppError.NotFound -> showNotFound()
    }
}
```

---

## 11. Рефакторинг-плейбук (R1–R20)

### ✅ Уже сделано в этом PR

| # | Рефакторинг | Файлы |
|---|---|---|
| R1 | Убран `runBlocking { userId.first() }` из 4 ViewModels | TasksViewModel, NotesViewModel, ProjectsViewModel, TagsViewModel |
| R5 | Извлечён `Mappers.kt` для Entity↔Domain | Mappers.kt + все repository impl |
| R6 | `@Embedded SyncColumns` в 4 entities | Entities.kt |
| R10 | Удалены 16 pass-through use cases | TasksUseCase, NotesUseCase, ProjectsUseCase, TagsUseCase, Modules.kt |
| R11 | Исправлен `require { throw }` баг | CreateProjectUseCase |

### 📋 Предстоит (следующие PR)

| # | Рефакторинг | Суть |
|---|---|---|
| R2 | Удалить `NotesStore` | Дублирует `NotesRepository`; NotesViewModel перейти на NotesRepository |
| R3 | Collapse UiState variants | Loading не наблюдаем; Empty payload не используется → data class |
| R4 | Generic `ListViewModel<T,F>` | 3 почти идентичных VM (Tasks/Projects/Tags) → один базовый класс |
| R7 | Koin Annotations | 295-строчный Modules.kt → @Module/@ComponentScan |
| R8 | `@IntoSet` для 16 tools | ручной listOf(...) → Koin-агрегация |
| R9 | Удалить typealias TestFakes.kt | 2 файла-пустышки |
| R12 | `SettingsSnapshot<T>` | 19 DataStore-полей → typed data class |
| R13 | TestScopeProvider | все VM с инжектируемым scope |
| R14 | Удалить пустые Migration-объекты | Migration1To2, Migration2To3 — no-op |
| R15 | Удалить skeleton smoke tests | SharedLogic*Test с одним assert(1==1) |
| R16 | Централизовать все fakes | FakeReminderRepository из jvmTest → commonMain/test/fakes/ |
| R17 | Fix `_filter.value` race | TasksViewModel: `_filter.value` внутри `map { }` → передавать через lambda param |
| R18 | Reusable `taskWith()` factory | fixture в commonTest |
| R19 | Merge EditorState.Empty+Saving | NoteEditorScreen рендерит один spinner для обоих |
| R20 | `toggleComplete` через getById | `watchById().first()` → dedicated `suspend fun getById(id)` |

---

## 12. Дерево зависимостей (核心)

```
App() (shared/App.kt)
    ↓ startKoin(modules(domainModule(), platformModule()))
platformModule() → DAOs, SecureStoragePort, NotificationPort, FileSystem, BackupCodec, KoogPromptExecutor
domainModule()
    ├── SettingsRepository → DataStoreSettingsRepository
    ├── AuthRepository → SupabaseAuthRepository
    ├── TaskRepository → TaskRepositoryImpl (watchTasks / changes SharedFlow)
    ├── NotesRepository → RoomNotesRepository
    ├── ProjectsRepository → ProjectsRepositoryImpl
    ├── TagsRepository → TagsRepositoryImpl
    ├── AttachmentRepository → AttachmentRepositoryImpl
    ├── ReminderRepository → RoomReminderRepository
    ├── HlcFactory + SyncEngine + SyncOutbox + SupabaseSyncApiClient
    ├── KoogAgentService (TextGenPort) + 16 SimpleTools
    └── 4 ViewModels (Tasks, Notes, Projects, Tags) + SettingsViewModel + BackupViewModel + AuthViewModel + AttachmentsViewModel
```
