# Singularity Todo — Agent Cheatsheet

> Загружается автоматически в каждый чат с агентом. Полная документация: `ARCHITECTURE.md`.
> Перед нетривиальной задачей прочитай `docs/decisions/DIGEST.md`.

## Начало задачи

**`git fetch` — до того, как что-либо сделал, а не на пуше.** Расхождение, найденное
в начале, стоит один rebase пустого дерева; найденное на пуше — полную перепроверку
готовой работы, потому что чужие коммиты меняют то, на что опиралась твоя.

Замер за одну сессию (2026-10-05): два ребейза, и fetch в начале сэкономил один и
заодно раньше показал транш из 15 новых спек. `main` двигается параллельными траншами —
чужие изменения принимай как данность и перепроверяй, а не откатывай.
`git fetch origin && git log --oneline HEAD..origin/main | head`.

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

**DI-валидация:** `koin-compiler-plugin 1.2.1` (build-logic/Plugins.kt) даёт
compile-time проверку графа; аннотации (`@Single`, `@Factory`) не используются.
**Никогда** не используй `*domainModule().toTypedArray()` в `modules()` — это даёт
KOIN-W003 (graph unverifiable). Используй list composition:
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

Порт — интерфейс в `commonMain`, два actual, `single<Port>` в обоих
`PlatformModule.*.kt`. Единственный expect/actual seam — `platformModule()`.
Инвентарь портов, фабричные функции, время и NavGraphs — `docs/PLATFORM-REFERENCE.md`.

## Сборка

```bash
just gate         # ВСЕ гейты: check.sh → detekt → just cr → just gm agenda (SKIP_MAESTRO=1 — без flows)
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

> `just <recipe> name=value` **не** присваивает — приходит весь токен. Только
> позиционная форма: `just gm agenda`, не `just gm tags=agenda`.

**Гейты «меры», а не «булевы»** — все блокирующие; спека:
`openspec/specs/test-execution-integrity/spec.md`, ADR `2026-10-04-measurement-integrity`.

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

Любой JVM-тест при падении пишет `build/diagnostics/<TestClass>/coroutines.txt`
(состояние, контекст, job-иерархия, стектрейлы) — читай **первым**: кадры
приложения раньше kotlinx указывают, где корутина была, умерла она там, где
`lastObservedStackTrace`, а одинаковые стектрейсы — не доказательство утечки.
ADR `2026-10-03-kotlinx-coroutines-debug.md`, разбор — шаг 5 `debugging-investigation`.

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
**Писать:** выбор между несколькими разумными вариантами; неочевидный workaround; изменение
контракта между слоями. **Не писать:** опечатки, форматирование, новый use case по
существующему паттерну. Формат: frontmatter + `Context / Idea / Decision / Rationale /
Consequences / Links`. Policy: `docs/doc-maintenance.md`, процесс — `decisions-workflow`.

## 🗂 Skills

Каталог с описаниями — `docs/SKILLS-CATALOG.md` (auto-generated, не редактировать).
Правила написания — `writing-for-agents`. Навигация по темам — `find-skills` / `wayfinder`.
Ключевые: `singularity-todo-testable-vm` (canonical VM) · `feature-scaffold` ·
`test-helpers` · `koin-di` · `mcp-server` · `quality-tools` · `maestro-flows`.

> **Фича «готова», но ничего не делает** — самый частый дефект: код компилируется,
> покрыт тестами и **не вызывается никем**. Проверка: `scripts/find-unwired-surfaces.py`,
> подробности — скилл `singularity-todo-unwired-surface-audit`.

## Agent skills

Issue tracker — `docs/agents/issue-tracker.md` (GitHub Issues). Доменные доки —
`docs/agents/domain.md` (один `CONTEXT.md` в корне, ADR-и в `docs/decisions/`).
**Удалённые skill-ы** (не воскрешать): `koin-suspend-bridge`, `ai-provider-settings`,
`secret-migration`, `koog-test-workarounds`, `koog-both-platforms`, `vm-koin-scoping` —
причина каждого в ADR с его именем в `docs/decisions/`.
