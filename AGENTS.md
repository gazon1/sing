# Singularity Todo — Agent Cheatsheet

> Загружается автоматически в каждый чат с агентом. Полная документация: `ARCHITECTURE.md`.
> Перед нетривиальной задачей прочитай `docs/decisions/DIGEST.md`.

## Структура

`androidApp/` — Android shell. `desktopApp/` — Desktop Compose entry.
`shared/src/commonMain/kotlin/com/singularity/todo/` — KMP library (commonMain + androidMain
+ jvmMain + tests):

- `core/` — auth, backup, coroutines, database, di, draft, error, files, ids, llm, log,
  notifications, observability, platform, reminders, security, serialization, settings,
  sync, tree, ui
- `feature/` — agenda, ai, archive, attachments, auth, backup, calendar, checklist, genui,
  notes, pomodoro, profile, projects, reminders, search, settings, statistics, tags, tasks
- `test/fakes/` — Fake-реализации для тестов (без моков)

**iOS нет.** Только Android + JVM Desktop.

## Канонический CRUD-паттерн (новой фичи)

`Ids.kt` (@JvmInline value class) → `*Domain.kt` (валидация, бизнес-правила) →
`*Repository.kt` (интерфейс: `Result<T>`, suspend, Flow) → `*RepositoryImpl.kt` (Room) →
`*UseCase.kt` (**только реальная логика**, не pass-through — enforced by `PassThroughUseCase`
detekt rule) → `*ViewModel.kt` (`StateFlow<SealedUiState>`, sealed Intent, инъектированный
scope) → `*Screen.kt` (Compose).

## DI: Koin 4.x (pure DSL)

**DI-валидация:** `koin-compiler-plugin 1.2.1` (build-logic/Plugins.kt) обеспечивает
compile-time проверку графа — все `get<T>()` валидируются на этапе сборки. Аннотации
(`@Single`, `@Factory`) не используются; плагин работает с classic DSL.
**Никогда** не используй `*domainModule().toTypedArray()` в `modules()` — это
даёт KOIN-W003 (graph unverifiable). Используй list composition:
`modules(listOf(...) + domainModule() + listOf(...))`.

| DSL | Когда использовать |
|---|---|
| `single { Repo(get()) }` | singleton |
| `viewModelOf(::Vm)` | все VM без runtime-параметров (**prefer**) |
| `viewModel { (p: Param) -> Vm(get(), p) }` | VM с runtime-параметрами |
| `koinViewModel()` / `koinViewModel { parametersOf(p) }` | инъекция VM в Composable (не `koinInject()`) |
| `factory { Vm(...) }` | **Never** для ViewModel — memory leak |
| `koinInject()` | репозитории и сервисы (не VM) |

**Где лежат биндинги.** `core/di/Modules.kt` — **фасад-агрегатор** (`coreLoggingModule()` +
`domainModule(): List<Module>`), а не источник истины. Реальные биндинги живут в
per-domain `*DiModule.kt` (`core/di/{Core,Calendar,Notes,Projects,Tags,Tasks}DiModule.kt`)
и `feature/*/*DiModule.kt` — каждая отдаёт свою `*Module()` функцию. Platform bindings —
в `core/di/PlatformModule.{jvm,android}.kt`.

> Почему список, а не `includes()`: `includes()` создаёт child scope, и биндинги из него
> не видны соседним модулям на parent level (Koin 4 scope isolation).
> ADR: `docs/decisions/2026-09-27-di-module-aggregator-narrative.md`

Подробности: `singularity-todo-koin-di` skill, `docs/decisions/2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`.

## Тесты

`commonTest` (pure Kotlin) выполняется внутри `./gradlew :shared:jvmTest` — отдельного таска нет.
`jvmTest` — Room + SQLite + Konsist arch tests. **Fake вместо моков** — все двойники в
`test/fakes/FakeRepositories.kt` (`FakeTaskRepository`, `FakeNotesRepository`,
`FakeProjectsRepository`, `FakeTagsRepository`, `FakeSettingsRepository`, `FakeSecureStorage`,
`FakeNotificationPort`, `FakeTextGen`).

```kotlin
// Три формы теста (singularity-todo-test-helpers skill):
testVm({ vm: MyVm -> vm.state }) { MyVm(deps, scope = this) }
ctx.act { it.onIntent(Intent.Load) }
ctx.assertIs<UiState.Content>()
awaitState { vm.state.value is UiState.Content }   // виртуальное время, не delay()
```

### BAN list

| Запрещено | Почему | Альтернатива |
|---|---|---|
| `stateIn` в ViewModel | держит upstream active 5s, ломает тесты без subscriber | `MutableStateFlow + scope.launch { }.collect {}` |
| `viewModelScope` в production | tight coupling, нетестируемо | инъектированный `AutoCloseableCoroutineScope` |
| `runBlocking` в production | blocking поток, deadlock risk | `scope.launch { }` |
| `delay(N)` в тестах | реальное время, не virtual time | `advanceUntilIdle()` + debounce mocking |
| `vm.state.launchIn(scope)` | костыль вокруг `stateIn` | canonical VM pattern |

### Canonical VM pattern

```kotlin
class MyViewModel(
    private val deps: MyDeps,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init { addCloseable(scope) }
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    init { scope.launch { repo.observe().collect { _state.value = it } } }
}
```

Подробности: `singularity-todo-testable-vm`, `singularity-todo-vm-migration-playbook`.

### Testing notes

**`kotlin.test.assertTrue` does NOT accept a lambda as message.**
Use `assertTrue(condition, "description")` — the lambda form is a JUnit/kotest idiom,
not kotlin.test. The lambda will not compile or will silently ignore the message.

**`import kotlin.io.path.*` bypasses detekt's `NoWildcardImports` rule.**
Detekt's rule resolves `kotlin.io.path` as a package wrapping stdlib extensions, not a
wildcard import. Use explicit imports: `kotlin.io.path.exists`, `kotlin.io.path.readText`,
`kotlin.io.path.isRegularFile`, `kotlin.io.path.extension`. The wildcard form will pass
detekt silently.

**Tests relying on `systemProperty` need `--rerun-tasks` after edits.**
`--rerun-tasks` forces Gradle to re-execute task up-to-date checks, bypassing the
task history cache. This is separate from the configuration cache (`org.gradle.configuration-cache`):
if a test reads `System.getProperty(...)` and the config-cache entry was built before your edit,
the stale compiled test class may still be used. Run `./gradlew :shared:compileTestKotlinJvm --rerun-tasks`
and retry. The configuration cache itself is invalidated by any change to tracked inputs
(`inputs.property` declarations in build scripts).

## expect/actual порты

| Порт | commonMain | jvmMain | androidMain |
|---|---|---|---|
| `SecureStoragePort` | интерфейс | secret-tool + AES-GCM | EncryptedSharedPreferences |
| `NotificationPort` | интерфейс | notify-send + at | AlarmManager + NotificationManager |
| `FileSystem` | интерфейс | JvmFileSystem | AndroidFileSystem |
| `BackupCodec` | интерфейс | JvmBackupCodec (java.util.zip) | AndroidBackupCodec |
| `MarkdownHtmlPort` | интерфейс | RichEditorMarkdownHtmlPort | — (shared) |
| `AttachmentStorage` | **класс** (не интерфейс) | — | — |
| `TimeZoneProvider` | expect val | actual | actual |

**Время.** Проектного `core.platform.Clock` object больше нет
(ADR `2026-09-27-remove-platform-clock-object.md`). Используй `kotlin.time.Clock.System.now()`
(внедряй `Clock` параметром для тестов), `core.platform.todayFlow()` / `todayAt(zone)` /
`todayInSystemZone()` для `LocalDate`, `delayUntilNextMidnight()` для половиночного сброса.

**Фабричные функции (platform factories):** `createSqlDriver()` (SQLite JDBC / sqlite-bundled),
`createHttpClient()` (OkHttp / OkHttp), `createBackgroundScope()` (`Dispatchers.Default`),
`initLogging()` (Kermit+Logback / Kermit+Logcat), `platformModule()` (все bindings),
`aiToolsModule()` (32 Koog tools), `createKoogPromptExecutor()` (MultiLLMPromptExecutor+OkHttp /
AndroidKoogFactory error stub), `onSecondaryClick()` (AWT / secondary pointer).

`isDesktop` удалён — определяй платформу через конкретный actual, а не флаг.

**Навигация** (expect/actual NavGraphs): `TasksNavGraph`, `ProjectsNavGraph`, `NotesNavGraph`,
`SearchNavGraph`, `SettingsNavGraph`, `CalendarNavGraph`, `AgendaNavGraph` + парные
`*EntryProvider` (`tasksEntryProvider`, `calendarEntryProvider`, `agendaEntryProvider`, ...).

## Сборка

```bash
./check.sh                              # тесты + Android сборка (локальный check)
./gradlew :shared:jvmTest               # быстрая проверка
./gradlew :androidApp:assembleDebug     # полная Android сборка
./gradlew :desktopApp:run               # Desktop (headless: xvfb-run -a)
./gradlew :desktopApp:test              # Desktop Compose UI tests (задача `test`, не `jvmTest`)

just lint              # detekt (shared + desktopApp), enforcing
just detekt-fix        # auto-fix detekt + ktlint in-place
just detekt-baseline   # пересоздать baseline
just coverage          # kover XML → shared/build/reports/kover/
just tcheck            # tests + assembleDebug + lint (полный pipeline)
just tcheck-evals      # workflow evals (agent behaviour regression)
just docs-audit        # нормализация frontmatter + DIGEST + stale-ref/size/supersede чеки
```

## 🤖 Dogfooding: MCP Server

Агенты подключаются к `:mcp-server` через stdio и правят задачи/проекты/заметки/теги/ADR.
Полный список tools (22 write/read + 13 AI gen) — `singularity-todo-mcp-server` skill.

```bash
./gradlew :mcp-server:build
java -jar mcp-server/build/libs/mcp-server-jvm-*.jar --profile=ai-agent
```

MCP tools меняют state только через репозитории; `write_adr` пишет в `docs/decisions/`;
timestamps — `kotlin.time.Instant`; business errors → `isError:true`, internal → `-32603`.
Каждый AI-вызов пишет в `llm_usage` (tokens, `cost_usd_micros`, `duration_ms`, `profile_id`).
Профили изолируют данные: `ai-agent` (🤖) для dogfooding, `personal` (🏠) для своих задач.

## 🔧 Run-loop (UI-верификация)

**Android:** `mcp__android_emulator__android_{preflight,build_and_run,ui_status,screenshot,
ui_describe,ui_resolve,ui_tap,ui_type_text,logs}` — screenshot до и после действия,
`android_logs` для крейшей. **Desktop:** `./gradlew :desktopApp:test` (быстро) или
`xvfb-run -a ./gradlew :desktopApp:run`.

**DB inspect:** `adb shell run-as com.singularity.todo cp databases/singularity.db /sdcard/`
+ `adb pull` → `sqlite3 /tmp/singularity.db ".schema"` (android);
`sqlite3 ~/.local/share/singularity/databases/singularity.db ".schema"` (desktop).

> **Эмулятор**: `./scripts/ensure-emulator.sh` печатает готовый serial (или
> поднимает AVD и ждёт загрузки); `scripts/run-maestro.sh` сам перезапустит
> эмулятор, если устройство пропало. Не подкручивайте `-gpu`: единственный
> рабочий режим — дефолтный host GPU, а он периодически падает в gfxstream
> (баг хоста, лечения нет). Подробности — ADR
> `2026-09-29-emulator-crash-recovery-runner.md` и скилл
> `singularity-todo-emulator-launch`.

> **Если эмулятор падает на старте** (`amdgpu: The CS has been rejected (-22)` +
> `IOT instruction (core dumped)`) — это регрессия связки Mesa + ядро на AMD
> Renoir, лечится обновлением системы, а не флагами `-gpu`/`-accel`. ADR:
> `docs/decisions/2026-09-28-emulator-mesa-radeon-cs-rejected.md`.

## ❌ Что НЕ делать

1. **`runBlocking` в ViewModel init** — вместо этого `combine(...)` + `flatMapLatest`
2. **`*Blocking()` методы в репозиториях** — только suspend + Result<T>
3. **MockK / Mockito** — fakes для state-тестов; MockK только для проверки исходящих
   вызовов (DB writes, analytics, network). Рационал: `2026-09-25-test-suite-tag-defaults.md`
4. **Pass-through CRUD use cases** — VMs инжектят репозиторий напрямую. AI-специфичные
   use cases допустимы. Enforced by `PassThroughUseCase` detekt rule
5. **`java.io.File` напрямую** — только через `FileSystem` порт
6. **`require { throw ... }` внутри лямбды** — `require` сам бросает
7. **Импортировать Koog-типы вне `feature/ai` и `core/di`**
8. **Грубые изменения Room schema** — `SCHEMA_VERSION` + `AutoMigration`

## 🧠 Decision log

Перед нетривиальной задачей прочитай `docs/decisions/DIGEST.md`. Если задача меняет
архитектуру, контракт или обнаруживает неочевидный workaround — создай запись в
`docs/decisions/YYYY-MM-DD-<slug>.md` и пересобери digest (`./scripts/refresh-decisions-digest.sh`).

**Писать ADR:** выбор между несколькими разумными вариантами; неочевидный workaround;
изменение контракта между слоями; пользователь попросил зафиксировать рассуждение.
**Не писать:** опечатки, форматирование, новый use case по существующему паттерну.
Формат: frontmatter + `Context / Idea / Decision / Rationale / Consequences / Links`.
Policy: `docs/doc-maintenance.md`. Процесс: `singularity-todo-decisions-workflow` skill.

## 🗂 Skills

Полный каталог с описаниями — `docs/SKILLS-CATALOG.md` (auto-generated). Правила написания
скиллов — `writing-for-agents` skill. Навигация по темам — `find-skills` / `wayfinder`.

Ключевые: `singularity-todo-testable-vm` (canonical VM) · `vm-migration-playbook` ·
`feature-scaffold` · `test-helpers` · `nav3-nested-graphs` · `nav3-savedstate` · `koin-di` ·
`ai-tool` · `mcp-server` · `sync` · `room-migration` · `quality-tools` (detekt/ktlint/kover) ·
`clean-architecture-audit` · `worktree-isolation` · `code-review-pr-workflow` ·
`decisions-workflow` · `maestro-flows` · `emulator-launch` · `unwired-surface-audit`.

> **Фича «готова», но ничего не делает** — самый частый дефект проекта: код
> компилируется, покрыт тестами и **не вызывается никем**. Проверка:
> `scripts/find-unwired-surfaces.py`. Подробности — скилл
> `singularity-todo-unwired-surface-audit`.

**Удалённые skill-ы** (информация в `docs/decisions/`): ~~`koin-suspend-bridge~~
`2026-09-05-koin-suspend-bridge.md` · ~~`ai-provider-settings~~
`2026-09-05-llm-provider-settings.md` · ~~`secret-migration~~
`2026-09-05-secret-storage-split.md` · ~~`koog-test-workarounds~~
`2026-09-05-koog-test-workarounds.md` · ~~`koog-both-platforms~~
`2026-09-05-koog-both-platforms.md` · ~~`vm-koin-scoping~~
`2026-09-27-vm-koin-scoping-retired.md`

## Agent skills

### Issue tracker

GitHub Issues (git@github.com/gazon1/singularity-clone-kmp.git). See `docs/agents/issue-tracker.md`.

### Domain docs

Single-context layout: one `CONTEXT.md` at the repo root, ADRs at `docs/decisions/`. See `docs/agents/domain.md`.
