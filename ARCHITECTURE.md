# Architecture — Singularity Todo KMP

> Полная архитектура приложения. Краткий cheatsheet — `AGENTS.md`.

---

## 1. Карта пакетов

> Автогенерируется: `just docs-audit` → `scripts/print-source-tree.sh`.
> Для сверки с кодом: `python3 scripts/print-source-tree.py`.

### `core/` — инфраструктура (ports & adapters)

| Пакет | Назначение |
|---|---|
| `core/auth/` | Supabase session, `SessionStore`, `AuthRepository` |
| `core/attachments/` | Attachment entity, DAO, repository, storage (класс, не порт) |
| `core/backup/` | BackupCodec, BackupExporter/Importer, migration chain, DSL builders |
| `core/clock/` | `Clock` expect object, `todayFlow`, `AutosaveScheduler` |
| `core/coroutines/` | `createBackgroundScope()` expect/actual — mandatory `CoroutineScope` для VM |
| `core/database/` | Room entities, DAOs, migrations, **Mappers.kt** |
| `core/di/` | `Modules.kt` (domainModule, 13 модулей), Koin Bridge, Platform/AI factories |
| `core/draft/` | `DraftStore<T>` — DataStore-based, debounce 500ms, seed-if-empty |
| `core/error/` | `AppError` sealed, `runCatchingResult` |
| `core/files/` | `FileSystem` порт (интерфейс), `MimeTypes`, `FileChecksum` |
| `core/ids/` | Value class IDs: `TaskId`, `NoteId`, `ProjectId`, `TagId`, `UserId` |
| `core/llm/` | `OpenAiConfig`, `AiTestResult`, `KnownModels`, `LlmProvider`, `TextGenPort` |
| `core/log/` | `initLogging()` expect/actual — Kermit + platform-specific backend |
| `core/network/` | Ktor/OkHttp client config |
| `core/notifications/` | `NotificationPort` (expect/actual) |
| `core/observability/` | `UsageRecorder`, `RoomUsageRecorder` — LLM token tracking |
| `core/platform/` | `Clock`, `TimeZoneProvider`, `isDesktop` expect/actual |
| `core/reminders/` | `ReminderRepository`, reminder scheduling |
| `core/security/` | `SecureStoragePort` (expect/actual: secret-tool/AES-GCM + EncryptedSharedPreferences), `ProfileAwareSecureStorage` |
| `core/serialization/` | `StableJson` — centralized JSON с `encodeDefaults` + `ignoreUnknownKeys` |
| `core/settings/` | `SettingsRepository` (DataStore), `SettingsContributor` |
| `core/sync/` | HLC, `SyncEngine`, `ConflictResolver`, `SyncOutbox`, Supabase API |
| `core/tree/` | `Cascade.kt`, `TreeVisitor.kt` — pure infra для org-mode tree processing |
| `core/ui/` | Theme, shared composable library (`components/`), `UiEvent` contract |
| `core/validation/` | Domain validation helpers |
| `core/backup/` | BackupCodec, BackupExporter/Importer, migration chain, DSL builders |

### `feature/` — UI-фичи (вертикали)

| Пакет | Screen | ViewModel | Repository |
|---|---|---|---|
| `tasks/` | `TaskDetailViewScreen` | `TaskCreateViewModel` | `TaskRepository` |
| `notes/` | `NotesListScreen` | `NotesListViewModel`, `NoteEditor`, `NotePreview` | `NotesRepository` |
| `projects/` | `ProjectsScreen` | `ProjectsViewModel`, `ProjectDetail`, `ProjectEditorViewModel` | `ProjectsRepository` |
| `tags/` | — | `TagsViewModel` | `TagsRepository` |
| `search/` | — | — | — |
| `ai/` | `ChatScreen` | `ChatViewModel`, `GenUi*ViewModel` | — |
| `auth/` | `LoginScreen` | `AuthViewModel` | `AuthRepository` |
| `settings/` | `SettingsScreen` + sub-screens | `SettingsViewModel`, `AccountSettingsViewModel`, `TagPickerViewModel` | `SettingsRepository` |
| `backup/` | `BackupScreen` | `BackupViewModel` | `BackupRepository` |
| `attachments/` | — | `AttachmentsViewModel` | `AttachmentRepository` |
| `reminders/` | — | — | `ReminderRepository` |
| `agenda/` | `AgendaScreen` | `AgendaViewModel`, `SavedAgendaListViewModel`, `SavedAgendaViewModel` | `SavedAgendaViewsRepository` |
| `archive/` | — | `ArchiveViewModel` | — |
| `calendar/` | `CalendarScreen` | `CalendarViewModel` | — |
| `checklist/` | — | `ChecklistViewModel` | `ChecklistRepository` |
| `genui/` | — | `GenUi*ViewModel` | — |
| `pomodoro/` | — | `PomodoroViewModel` | — |
| `profile/` | — | `ProfileViewModel` | `ProfileRepository` |
| `statistics/` | — | `StatisticsViewModel` | — |
| `nav/` | — | — | — |

---

## 1.1. Shared UI library (`core/ui/components/`)

Every screen consumes widgets from this library. **No feature-local copy of any of these is allowed.**

| Widget | Replaces |
|---|---|
| `UiEvent` (sealed ShowDialog / ShowError / NavigateBack) | `var dialogText by remember { mutableStateOf<String?>(null) }` |
| `CollectEvents(flow, onEvent)` | `LaunchedEffect(Unit) { flow.collectLatest { … } }` boilerplate |
| `ResultDialog(title, text, onDismiss)` | Inline `AlertDialog { title = "AI Result"; text = …; confirmButton = { TextButton("OK") } }` (4 copies) |
| `LoadingIndicator(modifier)` | `Box(fillMaxSize, Center) { CircularProgressIndicator() }` |
| `EmptyState(title, subtitle?, modifier)` | `Box(fillMaxSize, Center) { Text("No X yet") }` |
| `MessageBubble(role, content, modifier)` | Two parallel chat-bubble implementations in old `ChatScreen.kt` |
| `ChatInputBar(...)` | Inline `Row { OutlinedTextField + IconButton }` in AI Chat |
| `AiActionButton(onClick, modifier)` | `IconButton { Icon(AutoAwesome, primary tint) }` in TaskCard / ProjectCard / NoteEditor toolbar |
| `DeleteActionButton(onClick, modifier)` | `IconButton { Icon(Delete, error tint) }` in every card |
| `ButtonSpinner(modifier)` | 24 dp inline `CircularProgressIndicator` in Login/Backup buttons |
| `SettingsSection(title) { content }` | `Card { Column { Text(titleSmall) ... } }` in every settings sub-screen |
| `SettingsSwitchRow(title, subtitle?, checked, onCheckedChange)` | `Row { Column { Text; Text } Switch }` for every toggle setting |
| `Formatters.kt` (`priorityColorByIndex`, `hexColor`) | `when (priority) { … Color(0xFF…) … }` duplicated across screens |

### UiEvent contract — State vs Event

```
ViewModel:                          Screen:
─────────                          ──────
StateFlow<UiState>      ─────►     collectAsStateWithLifecycle()
StateFlow (continuous)              (renders list / loading / form)

MutableSharedFlow<UiEvent> ─────►  CollectEvents(vm.events) { … }
SharedFlow (one-shot)               (renders ResultDialog / snackbar / navigates)
```

Always:

```kotlin
private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
val events: SharedFlow<UiEvent> = _events.asSharedFlow()
```

`extraBufferCapacity = 4` is required — without it, `emit` from a finished coroutine silently drops. With it, fast screen rotations do not drop events.

### Per-feature components

Feature-specific widgets (TaskCard, ProjectCard, EditorBody, TaskAiBottomSheet) live in `feature/<feature>/components/`. They are owned by the feature, can reference its domain types, but consume shared primitives (`AiActionButton`, `DeleteActionButton`, `LoadingIndicator`, …) instead of inline Material widgets.

### Decomposition rules — when to extract

Extract a sub-composable when ANY of:
- Function body > 60 lines
- Same `Box(fillMaxSize) { Text(...) }` repeated ≥ 2× → use `EmptyState`
- Same `AlertDialog(...)` block repeated ≥ 2× → use `ResultDialog`
- Card has 4+ separate callback params → pack into `@JvmInline value class XxxCardActions`
- `when (state)` branch > 30 lines → extract `XxxContent(state, ...)` private composable
- Toolbar / list / sheet has its own internal state → extract to `feature/<feature>/components/`

**Card callback grouping pattern** (e.g. `TaskCardActions`, `ProjectCardActions`):

```kotlin
@JvmInline
value class TaskCardActions(val block: (Action) -> Unit) {
    enum class Action { Toggle, Delete, Ai }
    fun onToggle() = block(Action.Toggle)
    fun onDelete() = block(Action.Delete)
    fun onAiClick() = block(Action.Ai)
    companion object { val Empty = TaskCardActions {} }
}
```

Adding a new action (e.g. `Pin`) does not break any call site. Read-only callers (e.g. `SearchScreen`) pass `TaskCardActions.Empty`.

---

## 2. Поток данных (Screen → VM → UseCase → Repo → DAO)

```
Compose Screen (Screen.kt)
    ↓ user intent
ViewModel (*ViewModel.kt) — StateFlow<SealedUiState>, sealed Intent
    ↓
UseCase (*UseCase.kt) — только реальная логика (валидация, clock.now(), build). Enforced by `PassThroughUseCase` detekt rule (`:detekt-rules`). Pass-through methods (single-expression delegation to `*Repository`) are flagged automatically.
    ↓
Repository (*Repository.kt) — интерфейс: suspend CRUD + Flow reads
    ↓
Room DAO (*Dao) + SQLite (jvmMain: sqlite-jdbc, androidMain: sqlite-bundled)
```

---

## 3. expect/actual таблица

**Интерфейсы/классы** (platform boundaries):

| Порт | commonMain | jvmMain | androidMain |
|---|---|---|---|
| `SecureStoragePort` | интерфейс | secret-tool + AES-GCM | EncryptedSharedPreferences |
| `NotificationPort` | интерфейс | notify-send + at | AlarmManager + NotificationManager |
| `FileSystem` | интерфейс | JvmFileSystem | AndroidFileSystem |
| `BackupCodec` | интерфейс | — (zip via stdlib) | — |
| `MarkdownHtmlPort` | интерфейс | RichEditorMarkdownHtmlPort | — (shared) |
| `Clock` | expect object | — (kotlinx-datetime) | — (kotlinx-datetime) |
| `AttachmentStorage` | **класс** (не интерфейс) | — | — |

**Фабричные функции**:

| Функция | jvmMain | androidMain |
|---|---|---|
| `createSqlDriver()` | SQLite JDBC driver | sqlite-bundled |
| `createHttpClient()` | OkHttp | OkHttp |
| `createBackgroundScope()` | `CoroutineScope(Dispatchers.Default)` | `CoroutineScope(Dispatchers.Default)` |
| `initLogging()` | Kermit + Logback | Kermit + Logcat |
| `createKoogPromptExecutor()` | MultiLLMPromptExecutor + OkHttp | AndroidKoogFactory (error stub) |
| `platformModule()` | все platform bindings | все platform bindings |
| `aiToolsModule()` | 32 Koog tools | 32 Koog tools |
| `onSecondaryClick()` | AWT secondary click | desktop only |
| `systemTimeZone` | expect val | expect val |

**Навигация** (NavGraphs): `TasksNavGraph` + `tasksEntryProvider`, `ProjectsNavGraph` + `projectsEntryProvider`, `NotesNavGraph`, `SearchNavGraph`, `SettingsNavGraph`, `CalendarNavGraph` + `calendarEntryProvider`, `AgendaNavGraph` + `agendaEntryProvider`.

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

**32 tools** (registered via `single<List<Tool<*, *>>>` в `AiToolsDiModule.kt`, НЕ через `@IntoSet`):

Write: `CreateTaskTool`, `UpdateTaskTool`, `DeleteTaskTool`, `CreateNoteTool`, `UpdateNoteTool`, `DeleteNoteTool`, `CreateProjectTool`, `UpdateProjectTool`, `DeleteProjectTool`, `CreateTagTool`, `DeleteTagTool`, `DecomposeAndCreateTool`, `WriteAdrTool`.

Read/List: `GetTaskTool`, `GetNoteTool`, `GetProjectTool`, `ListTasksTool`, `ListLinkedTasksTool`, `SearchTasksTool`, `ListProjectsTool`, `ListAdrsTool`, `ReadAdrTool`.

AI Gen: `RefineTaskTool`, `SmartRewriteTool`, `GenerateDescriptionTool`, `DecomposeTaskTool`, `GenerateChecklistTool`, `PickTimeTool`, `ClusterTasksTool`, `ClusterNotesTool`, `ProjectReviewTool`, `WeeklyPlanTool`, `ImproveNoteTool`.

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
if (requested.group == "org.jetbrains.kotlin") useVersion("2.3.21")
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

## 11. Рефакторинг-плейбук (R1–R30)

> R-номера с 1 по 20 из оригинального документа; новые — с 21.

### ✅ Уже сделано

| # | Рефакторинг | Суть |
|---|---|---|
| R1 | Убран `runBlocking { userId.first() }` | 4 ViewModels |
| R2 | NotesStore удалён | NotesRepository — единственный источник |
| R5 | `Mappers.kt` извлечён | Entity↔Domain |
| R6 | `@Embedded SyncColumns` | 4 entities |
| R10 | Pass-through use cases удалены | CRUD-UseCase'ы не создаются |
| R11 | `require { throw }` bug исправлен | CreateProjectUseCase |
| R12 | Custom detekt rule `PassThroughUseCase` | `ChecklistUseCase.kt` |
| R16 | FakeReminderRepository централизован | `commonMain/test/fakes/FakeRepositories.kt` |

### ❌ Отменено

| # | Рефакторинг | Причина |
|---|---|---|
| R7 | Koin Annotations | `koin-annotations 4.x` несовместим с Koin 4.x |
| R8 | `@IntoSet` для tools | `single<List<Tool>>` с `listOf(...)` — штатный Koin 4.x паттерн |

### 📋 Предстоит (актуальный backlog)

| # | Рефакторинг | Суть |
|---|---|---|
| R21 | Notes Clean Architecture | domain/data/presentation split для `feature/notes` |
| R22 | Agenda Clean Architecture | domain/data/presentation split для `feature/agenda` |
| R23 | GenUI subsystem ADR | `feature/genui/` — catalog, parser, render, schema |
| R24 | Profile subsystem ADR | `ProfileAwareCurrentUser` refactor (блокирует VM testability) |
| R25 | Nav3 type asymmetry | `rememberInMemoryNavBackStack` returns `NavBackStack<T>` — open ADR |
| R26 | Instant migration | `kotlin.time.Instant` → `kotlinx.datetime.Instant` — deferred |
| R27 | Collapsed UiState variants | Loading/Empty payload → data class |
| R28 | Generic `ListViewModel<T,F>` | 3 похожих VM → базовый класс |
| R29 | SettingsSnapshot | 19 DataStore-полей → typed data class |
| R30 | Snapshot testing deferred | Roborazzi отложен, ADR зафиксирован |

---

## 12. Дерево зависимостей (核心)

```
App() (shared/App.kt)
    ↓ startKoin(modules(platformModule(), aiToolsModule(), domainModule()))
platformModule() → DAOs, SecureStoragePort, NotificationPort, FileSystem, BackupCodec,
                   createBackgroundScope, createSqlDriver, initLogging, createHttpClient
aiToolsModule() → 32 Koog SimpleTools (single<List<Tool>>)
domainModule()
    ├── SettingsRepository → DataStoreSettingsRepository
    ├── AuthRepository → SupabaseAuthRepository
    ├── TaskRepository → TaskRepositoryImpl
    ├── NotesRepository → RoomNotesRepository
    ├── ProjectsRepository → ProjectsRepositoryImpl
    ├── TagsRepository → TagsRepositoryImpl
    ├── AttachmentRepository → AttachmentRepositoryImpl
    ├── ReminderRepository → RoomReminderRepository
    ├── SavedAgendaViewsRepository
    ├── ProfileRepository
    ├── HlcFactory + SyncEngine + SyncOutbox + SupabaseSyncApiClient
    └── 20+ ViewModels (Tasks, Projects, Notes, Settings, AI, Agenda, Calendar, etc.)
```

DI модули разнесены по 13 файлам в `core/di/`:
`CoreDiModule.kt`, `TasksDiModule.kt`, `NotesDiModule.kt`, `ProjectsDiModule.kt`,
`TagsDiModule.kt`, `CalendarDiModule.kt`, `AiToolsDiModule.kt`, `PlatformModule.kt`,
`Modules.kt` (оркестратор), `KoinBridge.kt`, `KoogPromptExecutorFactory.kt`,
`KoogPromptExecutorPort.kt`, `PromptExecutorPort.kt`.
