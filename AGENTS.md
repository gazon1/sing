# Singularity Todo — Agent Cheatsheet

> Загружается автоматически в каждый чат с агентом. Полная документация: `ARCHITECTURE.md`.
> Перед нетривиальной задачей прочитай `docs/decisions/DIGEST.md`.

## Структура

`androidApp/` — Android shell. `desktopApp/` — Desktop Compose entry.
`shared/src/commonMain/kotlin/com/singularity/todo/` — KMP library (commonMain + androidMain
+ jvmMain + tests): `core/` (auth, backup, coroutines, database, di, draft, error, files, ids,
llm, log, notifications, observability, platform, reminders, security, serialization, settings,
sync, tree, ui), `feature/` (agenda, ai, archive, attachments, auth, backup, calendar,
checklist, genui, notes, pomodoro, profile, projects, reminders, search, settings, statistics,
tags, tasks), `test/fakes/` (Fake-реализации, без моков). **iOS нет** — только Android + JVM.

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

| DSL | Когда |
|---|---|
| `single { Repo(get()) }` | singleton |
| `viewModelOf(::Vm)` | все VM без runtime-параметров (**prefer**) |
| `viewModel { (p: Param) -> Vm(get(), p) }` | VM с runtime-параметрами |
| `koinViewModel()` / `koinViewModel { parametersOf(p) }` | инъекция VM в Composable (не `koinInject()`) |
| `factory { Vm(...) }` | **Never** для VM — memory leak |
| `koinInject()` | репозитории и сервисы (не VM) |

**Где лежат биндинги.** `core/di/Modules.kt` — **фасад-агрегатор** (`coreLoggingModule()` +
`domainModule(): List<Module>`), не источник истины. Реальные биндинги — в per-domain
`core/di/{Core,Calendar,Notes,Projects,Tags,Tasks}DiModule.kt` и `feature/*/*DiModule.kt`
(каждая отдаёт свою `*Module()`), платформенные — в `core/di/PlatformModule.{jvm,android}.kt`.

> Список, а не `includes()`: `includes()` создаёт child scope, и биндинги из него не видны
> соседним модулям на parent level. ADR `2026-09-27-di-module-aggregator-narrative.md`.
> Подробности: скилл `singularity-todo-koin-di`, ADR `2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`.

## Тесты

`commonTest` (pure Kotlin) выполняется внутри `./gradlew :shared:jvmTest` — отдельного таска нет.
`jvmTest` — Room + SQLite + Konsist arch tests. **Fake вместо моков** — все двойники в
`test/fakes/FakeRepositories.kt`. Каждый тестовый класс обязан иметь `@Tag`, иначе
`-Ptest.tags=fast,slow` в CI молча его исключит (`TestTagCoverageTest`).
**`slow` = класс пересекает границу процесса** (Compose-харнесс, реальный файл/БД/часы,
Konsist-скан, spawn) — не «долгий». Никаких skipped: `@Disabled` или упавший assumption
guard валят `check-test-runs.py`.

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

**`kotlin.test.assertTrue` does NOT accept a lambda as message** — use `assertTrue(condition, "description")`.
**`import kotlin.io.path.*` bypasses detekt's `NoWildcardImports` rule** — use explicit imports
(`kotlin.io.path.exists`, `kotlin.io.path.readText`, `kotlin.io.path.isRegularFile`).
**Stale-test-classes is NOT a thing (verified 2026-10-04)** — after editing a test (incl.
a `systemProperty`-reading one), a plain rerun recompiles and re-executes correctly;
`--rerun-tasks` is only a debugging crutch. Evidence: ADR
`2026-10-04-configuration-cache-hardening` §A3.

## expect/actual порты

| Порт | commonMain | jvmMain | androidMain |
|---|---|---|---|
| `SecureStoragePort` | интерфейс | secret-tool + AES-GCM | EncryptedSharedPreferences |
| `NotificationPort` | интерфейс | notify-send + at | AlarmManager + NotificationManager |
| `SharePort` | интерфейс | JvmSharePort | AndroidSharePort |
| `FileSharePort` | интерфейс | JvmFileSharePort | AndroidFileSharePort |
| `FileRevealer` | интерфейс | JvmFileRevealer | AndroidFileRevealer |
| `FileSystem` | интерфейс | JvmFileSystem | AndroidFileSystem |
| `BackupCodec` | интерфейс | JvmBackupCodec (java.util.zip) | AndroidBackupCodec |
| `AttachmentStorage` | **класс** (не интерфейс) | — | — |
| `TimeZoneProvider` | expect val | actual | actual |

**Время.** `core.platform.Clock` object больше нет (ADR `2026-09-27-remove-platform-clock-object.md`):
`kotlin.time.Clock.System.now()` (внедряй `Clock` параметром для тестов), `core.platform.todayFlow()` /
`todayAt(zone)` / `todayInSystemZone()` для `LocalDate`, `delayUntilNextMidnight()`.

**Фабричные функции:** `createSqlDriver()`, `createHttpClient()`, `createBackgroundScope()`
(`Dispatchers.Default`), `initLogging()`, `platformModule()`, `aiToolsModule()` (32 Koog tools),
`createKoogPromptExecutor()`, `onSecondaryClick()`. `isDesktop` удалён — определяй платформу
через конкретный actual, а не флаг.

**Навигация** (expect/actual NavGraphs): `TasksNavGraph`, `ProjectsNavGraph`, `NotesNavGraph`,
`SearchNavGraph`, `SettingsNavGraph`, `CalendarNavGraph`, `AgendaNavGraph` + парные `*EntryProvider`.

## Сборка

```bash
./check.sh                    # тесты + Android
./gradlew :shared:jvmTest     # быстрый цикл (без -Ptest.tags = только fast)
./gradlew :shared:jvmTest -Ptest.tags=fast,slow   # полный набор, как в CI
./gradlew :androidApp:assembleDebug   # Android
./gradlew :desktopApp:run      # Desktop (xvfb-run -a)
./gradlew :desktopApp:test     # Desktop UI tests (задача `test`, не `jvmTest`)

just lint        # detekt (shared + desktopApp), enforcing
just detekt-fix  # auto-fix detekt + ktlint in-place
just detekt-baseline; just coverage; just tcheck; just tcheck-evals; just docs-audit
```

**Гейты «меры», а не «булевы»** (все блокирующие; спека —
`openspec/specs/test-execution-integrity/spec.md`, ADR `2026-10-04-measurement-integrity`):

`check-test-runs.py --require <set>` — прогон выполнил меньше классов/тестов, чем floor,
**или** хоть один тест skipped · `check-coverage.py` — покрытие `com.singularity.todo.*`
ниже floor (23.0 / 18.3 / 24.4) · `check-flaky-tests.py --current DIR --previous DIR` —
тест упал в прошлом прогоне и прошёл сейчас · `python3 -m unittest discover -s
scripts/tests` — регрессия в самих гейтах (≈20 мс). Floor = **минимальный** легитимный
прогон: падение — расследовать, не регенерировать.

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
## 🔄 OpenSpec

Spec-driven workflow: `proposal → specs → design → tasks → apply → verify → archive`.
Use `singularity-todo-openspec-workflow` skill. `openspec/config.yaml` has project context
(KMP conventions, DI rules, testing patterns) and artifact rules.

**When to use:** behavior changes. **When NOT:** bug fix, test-only, dependency update,
doc-only, rename without contract change. Run `openspec list --specs` before proposing —
existing spec → write a change to modify it, not a new proposal.
└─ verify: `openspec validate --all --strict` (after apply, before archive)

## 🔧 Run-loop (UI-верификация)

**Android:** `mcp__android_emulator__android_{preflight,build_and_run,ui_status,screenshot,
ui_describe,ui_resolve,ui_tap,ui_type_text,logs}` — screenshot до и после действия,
`android_logs` для крашей. **Desktop:** `./gradlew :desktopApp:test` или
`xvfb-run -a ./gradlew :desktopApp:run`.

**DB inspect:** `adb shell run-as com.singularity.todo cp databases/singularity.db /sdcard/`
+ `adb pull` → `sqlite3 /tmp/singularity.db ".schema"` (android);
`sqlite3 ~/.local/share/singularity/databases/singularity.db ".schema"` (desktop).

> **Эмулятор**: `./scripts/ensure-emulator.sh` → serial или AVD; `scripts/run-maestro.sh`
> перезапустит, если устройство пропало. Не подкручивайте `-gpu` — дефолтный host GPU
> периодически падает в gfxstream (скилл `singularity-todo-emulator-launch`). **Maestro сейчас
> падает в этом окружении** (`DeviceServerDiedException`, в т.ч. на контрольном потоке) —
> это эмулятор/драйвер, не код.

## 🤖 Coroutine test failures

On any desktop or shared JVM test failure, `build/diagnostics/<TestClass>/coroutines.txt`
is written automatically — full coroutine snapshot (state, context, job hierarchy,
creation and last-observed stack traces). Read it first: application frames before
kotlinx internals indicate where the coroutine was; `lastObservedStackTrace` is where it
died. Do NOT conclude a leak from identical stack traces alone — repeated stacks are
normal for background collectors. ADR `2026-10-03-kotlinx-coroutines-debug.md`.

## ❌ Что НЕ делать

1. **`runBlocking` в ViewModel init** — вместо этого `combine(...)` + `flatMapLatest`
2. **`*Blocking()` методы в репозиториях** — только suspend + Result<T>
3. **MockK / Mockito** — fakes для state-тестов; MockK только для исходящих вызовов
4. **Pass-through CRUD use cases** — VMs инжектят репозиторий напрямую (AI — допустимо).
   Enforced by `PassThroughUseCase` detekt rule
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
`decisions-workflow` · `openspec-workflow` · `maestro-flows` · `emulator-launch` · `unwired-surface-audit`.

> **Фича «готова», но ничего не делает** — самый частый дефект проекта: код
> компилируется, покрыт тестами и **не вызывается никем**. Проверка:
> `scripts/find-unwired-surfaces.py`. Подробности — скилл
> `singularity-todo-unwired-surface-audit`.

Issue tracker: GitHub Issues (git@github.com:gazon1/singularity-clone-kmp.git), процедура —
`docs/agents/issue-tracker.md`. Доменные доки: один `CONTEXT.md` в корне, ADR-и в
`docs/decisions/` — `docs/agents/domain.md`.

