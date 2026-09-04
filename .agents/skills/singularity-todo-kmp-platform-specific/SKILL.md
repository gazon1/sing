---
name: singularity-todo-kmp-platform-specific
description: KMP expect/actual patterns for AI features and platform-only dependencies. Use when adding Koog/JVM-only AI tools, expect/actual factories, platformModule() actuals, or when a JVM-only dependency (OkHttp, Koog, java.security libs) leaks into commonMain. Covers the project's convention of aiToolsModule() expect/actual splitting, nullable AI deps in ViewModels, and isolating JVM-specific casts behind platformModule.
---

# Singularity TODO — KMP Platform-Specific Patterns

**Problem this prevents:** JVM-only types (Koog PromptExecutor, JvmPromptExecutorPort, OkHttp Koog factory) leaking into commonMain via `as X` casts, breaking Android compilation. Or AI dependencies forcing every Android-only module to depend on Koog.

## Pattern 1: AI features are JVM-only → expect/actual

Koog AI stack depends on `MultiLLMPromptExecutor` which is JVM-only. Android cannot import it.

### `shared/src/commonMain/.../core/di/Modules.kt`

```kotlin
/**
 * Core domain: repositories, use cases, ViewModels, settings, sync.
 * Does NOT include AI tools (those require JVM-only Koog).
 */
fun coreDomainModule(): Module = module { ... }

// Convenience entry point — both platforms
fun domainModule(): Module = module {
    includes(coreDomainModule(), aiToolsModule())
}

// Expect/actual split for the AI module
expect fun aiToolsModule(): Module
```

### `shared/src/jvmMain/.../core/di/AiToolsModule.jvm.kt`

Real Koog + 16 AI tools + GenUI + KoogAgentService + all the LLM use cases:

```kotlin
actual fun aiToolsModule() = module {
    single<LLModel> { OpenAIModels.Chat.GPT4oMini }
    factory { RefineTaskTool(get(), get()) }
    factory { SmartRewriteTool(get(), get()) }
    // ... 16 tools ...
    single<List<Tool<*, *>>> { listOf(/* all 16 */) }
    single<TextGenPort> { KoogAgentService(get(), get(), get(), get(), get()) }
    single<ai.koog.prompt.executor.model.PromptExecutor> {
        (get<PromptExecutorPort>() as JvmPromptExecutorPort).executor
    }
    // GenUI, AI ViewModels, etc.
}
```

### `shared/src/androidMain/.../core/di/AiToolsModule.android.kt`

**Stub — no real AI on Android:**

```kotlin
actual fun aiToolsModule() = module {
    // ChatScreen still works — it gets a FakeTextGen that returns
    // "(AI not available)" instead of crashing.
    single<TextGenPort> { FakeTextGen() }

    // ViewModels that depend on AI still need to be registered, but
    // with null AI dependencies — see Pattern 2 below.
    factory {
        TasksViewModel(
            taskRepo = get(),
            createTask = get(),
            updateTask = get(),
            settingsRepository = get(),
            refineTask = null,
            generateDescription = null,
            generateChecklist = null,
            decomposeTask = null,
            pickTime = null,
        )
    }
    factory {
        ProjectsViewModel(
            projectRepo = get(),
            createProject = get(),
            settingsRepository = get(),
            taskRepository = get(),
            projectReview = null,
        )
    }
}
```

**Why two `ViewModel` registrations?** `TasksViewModel` and `ProjectsViewModel` reference AI use cases in their constructor. If you don't register them on Android, the Compose `koinInject<TasksViewModel>()` crashes. Passing `null` for AI use cases is safe because they're typed as nullable.

## Pattern 2: Nullable AI deps in ViewModels

```kotlin
class TasksViewModel(
    private val taskRepo: TaskRepository,
    private val createTask: CreateTaskUseCase,
    private val updateTask: UpdateTaskUseCase,
    private val settingsRepository: SettingsRepository,
    // AI use cases are optional — Android doesn't ship with Koog/JVM AI stack,
    // so VMs work with null AI dependencies (AI buttons become no-ops on Android)
    private val refineTask: RefineTaskUseCase? = null,
    private val generateDescription: GenerateDescriptionUseCase? = null,
    private val generateChecklist: GenerateChecklistUseCase? = null,
    private val decomposeTask: DecomposeTaskUseCase? = null,
    private val pickTime: PickTimeUseCase? = null,
) : ViewModel() {

    fun refineTaskTitle(task: Task) = viewModelScope.launch {
        refineTask?.invoke(task.title, task.description)
            ?.onSuccess { _aiResult.emit(AiActionResult.RefineTitle(it)) }
            ?.onFailure { _aiResult.emit(AiActionResult.Error(it.message ?: "Failed")) }
            ?: _aiResult.emit(AiActionResult.Error("AI not available"))
    }
}
```

Use `?:` to handle null with a user-friendly fallback. Don't use `?.` chains because that returns null silently — the user has no idea why their button doesn't work.

## Pattern 3: Inject the port, not the concrete class

When a UI screen calls `koinInject<X>()`, always prefer the abstraction. Concrete classes tie you to one implementation; ports let the platform module decide.

**Wrong** (broke when AI tools moved):

```kotlin
// ChatScreen.kt
val service: KoogAgentService = koinInject()  // crashes on Android — KoogAgentService isn't registered
```

**Right** (works on both platforms):

```kotlin
// ChatScreen.kt
val service: TextGenPort = koinInject()  // FakeTextGen on Android, KoogAgentService on JVM
```

Add `streamChat()` to `TextGenPort` if you need it — don't add a `KoogAgentService`-specific method to the port.

```kotlin
interface TextGenPort {
    suspend fun generate(prompt: String, systemPrompt: String? = null, model: String? = null): Result<String>
    fun streamChat(message: String): Flow<String>  // shared method, both impls override
}
```

## Pattern 4: `JvmPromptExecutorPort` exposes `val` not private

`ai.koog.prompt.executor.model.PromptExecutor` (Koog) is JVM-only. Android only sees `PromptExecutorPort`. To give JVM AI tools access to the underlying executor without casting in commonMain:

```kotlin
// shared/src/jvmMain/.../core/di/JvmPromptExecutorPort.kt
class JvmPromptExecutorPort(
    val executor: ai.koog.prompt.executor.model.PromptExecutor,  // <-- public val, not private
) : PromptExecutorPort {
    override suspend fun execute(...) = executor.execute(...)
    override fun executeStreaming(...) = executor.executeStreaming(...)
}
```

```kotlin
// shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt
single<ai.koog.prompt.executor.model.PromptExecutor> {
    (get<PromptExecutorPort>() as JvmPromptExecutorPort).executor
}
```

This cast lives in `jvmMain` — not `commonMain` — so it doesn't break Android compilation.

## Pattern 5: `platformModule()` actuals

`expect fun platformModule(): Module` in commonMain, separate actuals per platform. Split **early** into clear sections:

```kotlin
// shared/src/androidMain/.../core/di/PlatformModule.android.kt
actual fun platformModule(): Module = module {
    // ─── Room Database ───
    single<AppDatabase> { ... }
    single { get<AppDatabase>().taskDao() }
    // ...

    // ─── DataStore ───
    single<DataStore<Preferences>> { ... }

    // ─── Platform Ports ───
    single<SecureStoragePort> { AndroidSecureStorage(get()) }
    single<NotificationPort> { AndroidNotificationPort(get()) }
    single<FileSystem> { AndroidFileSystem(get()) }
    single<BackupCodec> { AndroidBackupCodec() }
}
```

```kotlin
// shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt
actual fun platformModule(): Module = module {
    // ─── Database (uses JdbcNotesStore, not Room) ───
    single<JvmDatabase> { JvmDatabase.create(dbPath) }
    single<NotesStore> { JdbcNotesStore() }
    // ...

    // ─── DataStore (in-memory stub) ───
    single<DataStore<Preferences>> { object : DataStore<Preferences> { ... } }

    // ─── Platform Ports ───
    single<SecureStoragePort> { JvmSecureStorage() }
    single<NotificationPort> { JvmNotificationPort() }
    single<FileSystem> { JvmFileSystem() }
    single<BackupCodec> { JvmBackupCodec() }
}
```

## Anti-patterns to avoid

### ❌ Don't cast JVM-only types in commonMain

```kotlin
// commonMain/.../core/di/Modules.kt
single<ai.koog.prompt.executor.model.PromptExecutor> {
    (get<PromptExecutorPort>() as JvmPromptExecutorPort).executor
}
```

This breaks Android compilation: `JvmPromptExecutorPort` doesn't exist in androidMain source set.

### ❌ Don't register JVM-only tools unconditionally

```kotlin
// commonMain/.../core/di/Modules.kt — NEVER DO THIS
single<TextGenPort> { KoogAgentService(get(), get(), get(), get(), get()) }
// Koog imports break Android
```

### ❌ Don't make Android-stub `aiToolsModule()` truly empty

```kotlin
// androidMain/.../core/di/AiToolsModule.android.kt — DON'T
actual fun aiToolsModule() = module { /* empty */ }
```

`TasksScreen` / `ProjectsScreen` / `ChatScreen` all inject types. The empty module = `NoDefinitionFoundException` on first navigation. Always register at least the VMs and a `FakeTextGen`.

## Files

| File | Role |
|---|---|
| `shared/src/commonMain/.../core/di/Modules.kt` | `coreDomainModule()`, `expect fun aiToolsModule()` |
| `shared/src/jvmMain/.../core/di/PlatformModule.jvm.kt` + `AiToolsModule.jvm.kt` | JVM platform |
| `shared/src/androidMain/.../core/di/PlatformModule.android.kt` + `AiToolsModule.android.kt` | Android platform + AI stubs |
| `shared/src/commonMain/.../feature/ai/TextGenPort.kt` | The port — both impls override |
| `shared/src/commonMain/.../feature/tasks/TasksViewModel.kt` | Nullable AI deps example |
| `shared/src/commonMain/.../feature/projects/ProjectsViewModel.kt` | Nullable AI deps example |
| `shared/src/jvmMain/.../core/di/JvmPromptExecutorPort.kt` | `val executor` exposed for JVM AI tools |
