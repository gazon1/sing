# Platform reference

Reference tables for the KMP seam: which actual implements which port, which
factory functions exist, and how time is obtained. Moved out of `AGENTS.md` on
2026-10-05, which had reached 250/250 lines with zero headroom.

It lives here because none of it is a decision. The *rule* — a port is an
interface in `commonMain` with two actuals and a `single<Port>` in both
`PlatformModule.*.kt`, and `platformModule()` is the only expect/actual seam —
stays in `AGENTS.md` and in `openspec/config.yaml`, because that is the part an
agent must not get wrong. The inventory below is what to read when *adding* a
port, which is not every task.

Nothing here is generated, but the ports table **is** checked:
`scripts/check-platform-reference-ports.py` reads the table below and compares it
with the code — a port the platform modules bind and the table does not name
fails the gate, a row that names a port the code does not bind fails it, and an
implementation a row attributes to a platform has to exist in that platform's
source set. The table says which of its rows the gate checks: the fourth column
is `checked` or `prose`.

The rest of this document — the time helpers, the factory functions, the
navigation seams — is prose. No gate checks it, and the sections say so.

## Ports

`commonMain` declares the interface unless noted; implementations live in
`jvmMain` / `androidMain`. A port is an interface in a `commonMain` source set
that a platform module binds; a binding that is not a behavioural seam (the
database, the preferences store, the scopes) is infrastructure and is not a
port. The authority on which binding is which is the seam registry
(`shared/src/jvmTest/resources/platform-seams.tsv`), and
`PlatformSeamGuardTest` holds that registry against the code.

| Port | jvmMain | androidMain | Checked |
|---|---|---|---|
| `BackgroundWorkScheduler` | `JvmBackgroundWorkScheduler` | `AndroidBackgroundWorkScheduler` | checked |
| `BackupCodec` | `JvmBackupCodec` | `AndroidBackupCodec` | checked |
| `CalendarAppQueries` | `JvmCalendarAppQueries` | `AndroidCalendarAppQueries` | checked |
| `CalendarProviderPort` | `NoopCalendarProvider` | `AndroidCalendarProvider` | checked |
| `CalendarSyncRepository` | `NoopCalendarSyncRepositoryImpl` | `CalendarSyncSettingsRepositoryImpl` | checked |
| `CalendarSyncWorkScheduler` | `NoopCalendarSyncWorkScheduler` | `AndroidCalendarSyncWorkScheduler` | checked |
| `CrashReportingPort` | `JvmCrashReportingPort` | `FileCrashReportingPort` | checked |
| `FileOpener` | `JvmFileOpener` | `AndroidFileOpener` | checked |
| `FileRevealer` | `JvmFileRevealer` | `AndroidFileRevealer` | checked |
| `FileSharePort` | `JvmFileSharePort` | `AndroidFileSharePort` | checked |
| `FileSourceFactory` | `JvmFileSourceFactory` | `AndroidFileSourceFactory` | checked |
| `FileSystem` | `JvmFileSystem` | `AndroidFileSystem` | checked |
| `GoogleAccessTokenProvider` | `LocalGoogleAccessTokenProvider` | `SupabaseGoogleAccessTokenProvider` | checked |
| `GoogleGrantRedeemer` | `LocalGoogleGrantRedeemer` | `SupabaseGoogleGrantRedeemer` | checked |
| `GoogleGrantRevoker` | `LocalGoogleGrantRevoker` | `SupabaseGoogleGrantRevoker` | checked |
| `GoogleSyncPeriodicTrigger` | `DelayLoopGoogleSyncPeriodicTrigger` | `AndroidGoogleSyncPeriodicTrigger` | checked |
| `Haptic` | `createHaptic` | `AndroidHaptic` | checked |
| `HostEnvironmentPort` | `JvmHostEnvironment` | `AndroidHostEnvironment` | checked |
| `Notifier` | `JvmNotifier` | `AndroidNotifier` | checked |
| `OAuthBrowser` | `JvmOAuthBrowser` | - | checked |
| `OAuthRedirectSocket` | `JvmOAuthRedirectSocket` | - | checked |
| `PomodoroScheduler` | - | `PomodoroAlarmScheduler` | checked |
| `PomodoroTaskListProvider` | `JvmPomodoroTaskListProvider` | `AndroidPomodoroTaskListProvider` | checked |
| `PomodoroTimer` | `JvmPomodoroTimer` | `AndroidPomodoroTimer` | checked |
| `ReminderScheduler` | `JvmReminderScheduler` | `AlarmManagerReminderScheduler` | checked |
| `SecureStoragePort` | `JvmSecureStorage` | `AndroidSecureStorage` | checked |
| `SharePort` | `JvmSharePort` | `AndroidSharePort` | checked |
| `SyncPeriodicTrigger` | `DelayLoopSyncPeriodicTrigger` | `AndroidSyncPeriodicTrigger` | checked |
| `SyncWorkScheduler` | `JvmSyncWorkScheduler` | `AndroidSyncWorkScheduler` | checked |
| `ToolCallLog` | `JvmFileToolCallLog` | `AndroidFileToolCallLog` | checked |
| `AttachmentStorage` | - | - | prose |

`AttachmentStorage` is a **class** in `commonMain`, not an interface, so it is
not a port — the row says `prose` and the gate leaves it alone.

The mechanisms behind two of the rows: the JVM secure storage is `secret-tool`
with AES-GCM, the Android one is `EncryptedSharedPreferences`; the JVM file
opener is `java.awt.Desktop`, the Android one is `ACTION_VIEW` with a
`FileProvider`. Those are prose — no gate checks them.

`NotificationPort` is gone: the JVM half used `at(1)` and cancelled every `at`
job on the host, jobs the user had queued outside the app. `Notifier` replaced
it, and it can only display something — it has no way to enumerate or cancel
system units. `TimeZoneProvider` is gone with it; the zone comes from
`kotlin.time.Clock` and `java.time`.

`FileOpener` returns `OpenOutcome`, a sealed interface with `Opened` and `NoHandler`.
It is deliberately not a `Boolean`: a boolean cannot be rendered, so a caller holding
one has to invent a third answer for "it did nothing" — which is the silent return.
Making `NoHandler` a value the caller must handle is what forces the explanation and
the Share action to exist. Both implementations rethrow `CancellationException`.
Registered in four places: `PlatformModule.android.kt`, `PlatformModule.jvm.kt`,
`DesktopPlatformGraph.kt` and `TestPlatformModule.kt`.

## Time

Prose — no gate checks this section.

`core.platform.Clock` as an object is gone (ADR
`2026-09-27-remove-platform-clock-object.md`). Use `kotlin.time.Clock.System.now()`
and inject `Clock` as a parameter for tests. For `LocalDate`:
`core.platform.todayFlow()`, `todayAt(zone)`, `todayInSystemZone()`, and
`delayUntilNextMidnight()`.

## Factory functions

Prose — no gate checks this section.

`createSqlDriver()`, `createHttpClient()`, `createBackgroundScope()`
(`Dispatchers.Default`), `initLogging()`, `platformModule()`, `aiToolsModule()`
(32 Koog tools), `createKoogPromptExecutor()`, `onSecondaryClick()`.

`isDesktop` was removed — determine the platform through a concrete actual, not a
flag.

## Navigation

Prose — no gate checks this section.

The seven nested NavGraphs — `TasksNavGraph`, `ProjectsNavGraph`, `NotesNavGraph`,
`SearchNavGraph`, `SettingsNavGraph`, `CalendarNavGraph`, `AgendaNavGraph` — are
**common**, one implementation each, in
`feature/<name>/presentation/nav/<Name>NavGraph.kt`. The platform differences are
three seams:

| Seam | Android | Desktop |
|---|---|---|
| `rememberAppNavBackStack` | saved-state backed | in memory |
| `SystemBackHandler` | the system back gesture | a no-op |
| `rememberAppEntryDecorators` | the ViewModel-store decorator | the same — both platforms scope the store per entry |

plus the agenda's `contextMenuHost`: a gesture, so a platform's business (a bottom
sheet on Android, a popup on Desktop).

`AppNavKey` is the single sealed root and must not grow parallel hierarchies (ADR
`2026-09-29-single-sealed-navkey-root`). New screens are leaves of `AppDestination` —
for example `AppDestination.AttachmentViewer(attachmentId)` — and are registered in
the **contributor of their own feature** under `feature/nav/entries/`, not in both
`AndroidNavEntries.kt` and `JvmNavEntries.kt`. Those two files are assemblers: they
collect the contributors, supply the platform's context menu, and hold the platform's
own bits (the hoisted stacks on Desktop, the tasks graph entry).

A new screen *inside* a nested graph goes into that graph's `entryProvider` — one
file, not two. `AppEntryParityTest` holds the set of registered destinations.

## Platform-specific patterns

Merged in from the skill `singularity-todo-kmp-platform-specific` on 2026-10-15. They
belong with the port inventory above rather than in a skill: both answer "what is the
KMP seam today", and one document named after the seam is discoverable in a way a
second file about the same subject is not. See
`docs/decisions/2026-10-15-reference-skills-become-documents.md`.

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

## Pattern 6: `savedstate-compose-desktop` is always a no-op

The `savedstate-compose-desktop` artifact (Kotlin/JVM wrappers) provides **empty stub implementations** for all `saveable` APIs. Specifically:

- `LocalSaveableStateRegistry` → `staticCompositionLocalOf<SaveableStateRegistry?> { null }`
- `rememberSaveable { }` → behaves like plain `remember { }`

This is **by design** (JetBrains/kotlin-wrappers#3057) — it lets Android Compose code compile on Desktop without modification, but `SaveableStateRegistry` never actually saves or restores anything on JVM.

**Consequences for Nav3:**
- `rememberNavBackStack(SavedStateConfiguration, start)` on Desktop — the configuration is read but `LocalSaveableStateRegistry` is always `null`, so writes go nowhere and reads always return `null`. The `SavedStateConfiguration` is dead weight.
- On Desktop, always use `rememberInMemoryNavBackStack(start)` from `com.singularity.todo.feature.nav.rememberInMemoryNavBackStack` — it creates an in-memory `NavBackStack` without any serialization overhead.
- `navSavedStateConfig(...)` from `com.singularity.todo.feature.nav.navSavedStateConfig` is **Android only** — it generates a `SavedStateConfiguration` with `SerializersModule` for process-death persistence that Desktop never uses.

**See:** `docs/decisions/2026-09-16-nav3-desktop-in-memory-no-savedstate.md`

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

## Pattern 7: The external agent and the tool server are JVM-only

Three surfaces live in `shared/src/jvmMain/` and reach no other platform. None of them
is a port, so the ports table above does not carry them — they are `jvmMain`-only code
that the Android build never sees.

| Surface | Where | Why JVM-only |
|---|---|---|
| The ACP client | `shared/src/jvmMain/.../feature/ai/acp/` | The SDK needs JDK 21; the Android target stays at JVM_11 bytecode |
| The external-agent screen | `shared/src/jvmMain/.../feature/ai/external/` | It is the screen that drives the ACP client |
| The MCP tool server | `shared/src/jvmMain/.../mcp/` | It is a stdio server; a mobile process has no use for one |

### The dependency stays in `jvmMain.dependencies`

```kotlin
// shared/build.gradle.kts
jvmMain.dependencies {
    // Agent Client Protocol — the client half, used by the external-agent
    // screen. JVM-only on purpose: the SDK needs JDK 21, and this project
    // keeps the Android target at JVM_11 bytecode. Nothing in commonMain or
    // androidMain may depend on it — the mobile build must not acquire this.
    implementation(libs.acp)
    implementation(libs.acp.model)
}
```

The SDK is pinned exactly (`com.agentclientprotocol:acp:0.34.0`,
`acp-model:0.34.0`) — it is a 0.x release, so a range would let a minor bump change
the wire format. Putting the dependency in `commonMain` or `androidMain` breaks the
Android build on bytecode level, not on a missing symbol.

### The screen is registered in the JVM nav entries only

`AppDestination.ExternalAgent` is declared in **common** code
(`shared/src/commonMain/.../feature/nav/AppDestination.kt`), but its *entry* is
registered in `shared/src/jvmMain/.../feature/nav/JvmNavEntries.kt` and nowhere else.
The assembler says why:

> The external-agent screen is registered here and nowhere else: the agent is a
> process, and processes are a desktop thing. A destination registered on Android
> would put a route on the mobile back stack that nothing renders.

It is therefore **not** in the common entry contributors under
`feature/nav/entries/` — the destination is common, the route is not.

Its Koin bindings are likewise desktop-only, in
`shared/src/jvmMain/.../feature/ai/acp/di/AcpDiModule.jvm.kt`, composed into the
desktop module list (`desktopApp/src/main/kotlin/com/singularity/todo/main.kt`). The
mobile build never acquires a binding it cannot use.

### The tool server lives in the shared runtime, and `:mcp-server` is a launcher

`shared/src/jvmMain/.../mcp/` holds the server implementation; the `:mcp-server`
module is one line:

```kotlin
// mcp-server/src/main/kotlin/com/singularity/todo/mcp/Main.kt
fun main(args: Array<String>): Unit = runToolServer(args)
```

The reason is the delivery mechanism: the desktop application hands an external agent
its tool access by running **the same server** in the application's own
`--mcp-server` mode, so there is one server and one toolset. Two servers would be two
toolsets that drift, and every guarantee the application makes about the user's data
would be a guarantee about one place.

The desktop application dispatches on the flag in
`desktopApp/src/main/kotlin/com/singularity/todo/main.kt`:

```kotlin
if (args.any { it == McpServerCommandProvider.TOOL_SERVER_FLAG }) {
    prepareDataDirectory()
    runToolServer(args)
    return
}
```

### The process layer is JVM-only too

`shared/src/jvmMain/.../core/process/` (`Subprocess`, `ProcessTree`) exists because an
external agent is a process, and processes are a desktop thing.

See `docs/architecture/acp-client.md` for the client role and the trust boundary, and
`docs/architecture/mcp-server.md` for the server itself.

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
| `shared/src/jvmMain/.../feature/ai/acp/` | The ACP client — JVM-only, exact SDK pin |
| `shared/src/jvmMain/.../feature/ai/external/` | The external-agent screen and its ViewModel |
| `shared/src/jvmMain/.../feature/ai/acp/di/AcpDiModule.jvm.kt` | The desktop-only Koin bindings |
| `shared/src/jvmMain/.../mcp/` | The MCP tool server implementation |
| `shared/src/jvmMain/.../core/mcp/McpServerCommandProvider.kt` | The command that starts the server for an agent |
| `shared/src/jvmMain/.../core/process/` | Spawning an agent process and ending its tree |
