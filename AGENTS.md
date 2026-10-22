# Singularity Todo — Agent Cheatsheet

> Загружается автоматически в каждый чат с агентом. Полная документация: `ARCHITECTURE.md`.

## Начало задачи

**Обзор документации — один раз за сессию.** `DIGEST.md` перечисляет, что где лежит:
дерево `docs/`, свежие решения, указатели. Дальше иди по именам файлов (`grep` / `find`),
а не перечитывай индексы — имена самодостаточны: `docs/architecture/sync.md` описывает sync. Полный индекс
решений — `DIGEST-full.md`, открывай его только если слоя не хватило.

**`DIGEST.md` генерируется, а не лежит в репозитории.** Файла нет в свежом клоне: он
собирается из `docs/decisions/` и лежит в `.gitignore`. Нет файла — запусти
`./scripts/refresh-decisions-digest.sh` (или `just setup-hooks`, чтобы хуки
перегенерировали его сами при checkout/merge) и прочитай его тогда.

**`git fetch` — до того, как что-либо сделал, а не на пуше.** Расхождение, найденное
в начале, стоит один rebase пустого дерева; найденное на пуше — полную перепроверку
готовой работы, потому что чужие коммиты меняют то, на что опиралась твоя.

Замер за одну сессию (2026-10-05): два ребейза, и fetch в начале сэкономил один.
`main` двигается параллельными траншами — чужие изменения принимай как данность и
перепроверяй, а не откатывай. `git fetch origin && git log --oneline HEAD..origin/main`.

**Механическая защита от divergence (#268):**
- `pre-rebase` hook (`.githooks/pre-rebase`) — блокирует rebase на локальный main,
  который отстаёт от `origin/main`; предупреждает о неpushнутых коммитах.
- Проверки divergence в CI больше нет: runner checkout'ит detached-коммит без трекинговой
  ветки и без рефа `origin/main`, поэтому гейт падал на каждом прогоне настройкой раннера,
  а не расхождением. Локально ту же проверку делает `pre-rebase` — на тот ref, на который
  собираешься переносить коммиты.

## Worktree: делегату — свой, канонический checkout — владельцу

**Делегированная задача (субагент) получает свой worktree.** Канонический checkout
принадлежит сессии, которая его заняла: две сессии в одном дереве — это две правки
в одном рабочем дереве и ветка, занятая чужой работой.

```bash
git worktree add /home/max/work/singularity-todo-<slug> -b <slug>/<что>
```

- **Не переключай ветку в чужом worktree** — список чужих: `git worktree list` (там, где `main`,
  ветка занята). Свою ветку — только в своём worktree.
- `./gw` даёт worktree приватный `GRADLE_USER_HOME`: `./gradlew --stop` в одном worktree убивает
  демоны всех остальных, а общий `~/.gradle` — общая блокировка кэша модулей.
- **Два Gradle-вызова одновременно не запускай** — ни два своих, ни свой и чужой: блокируется
  общий кэш, и падает не та сборка, которую ждут.
- Свой временный worktree сноси через `git worktree remove --force <path>`; чужой — не трогай.

**Правила ревью — в `CODING_STANDARDS.md`.** Файл читается на ревью, не при
реализации: там то, что ни один гейт проверить не может (согласованность между
файлами, «соответствует окружающему стилю», «сообщение говорит, что произошло»),
и таблица проверяемых конвенций с гейтом под каждой. Ревьюишь — читай его
первым; правило, у которого нет гейта, держит ревью на себе.

## Структура

`androidApp/` — Android shell. `desktopApp/` — Desktop Compose entry.
`shared/src/commonMain/kotlin/com/singularity/todo/` — KMP library (commonMain + androidMain +
jvmMain + tests): `core/` (auth, backup, coroutines, database, di, draft, error, files, ids, llm,
log, notifications, observability, platform, reminders, security, serialization, settings, sync,
tree, ui), `feature/` (agenda, ai, archive, attachments, auth, backup, calendar, checklist, genui,
notes, pomodoro, profile, projects, reminders, search, settings, statistics, tags, tasks),
`test/fakes/` (Fake-реализации, без моков). **iOS нет** — только Android + JVM.

## Канонический CRUD-паттерн (новой фичи)

`Ids.kt` (@JvmInline value class) → `*Domain.kt` (валидация) → `*Repository.kt`
(интерфейс: `Result<T>`, suspend, Flow) → `*RepositoryImpl.kt` (Room) → `*UseCase.kt`
(**только реальная логика**, не pass-through — enforced by `PassThroughUseCase` detekt rule)
→ `*ViewModel.kt` (`StateFlow<SealedUiState>`, sealed Intent, инъектированный scope)
→ `*Screen.kt` (Compose).

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

> Список, а не `includes()`: `includes()` создаёт child scope, и биндинги из него не видны соседним
> модулям на parent level (ADR `2026-09-27-di-module-aggregator-narrative.md`; скилл `singularity-todo-koin-di`).

## Тесты

`commonTest` (pure Kotlin) выполняется внутри `./gradlew :shared:jvmTest` — отдельного таска нет.
`jvmTest` — Room + SQLite + Konsist arch tests. **Fake вместо моков** — все двойники в
`test/fakes/FakeRepositories.kt`. Каждый тестовый класс обязан иметь `@Tag`, иначе
`-Ptest.tags=fast,slow` в CI молча его исключит (`TestTagCoverageTest`). **`slow` = класс
пересекает границу процесса** (Compose-харнесс, реальный файл/БД/часы, Konsist-скан, spawn),
не «долгий». Никаких skipped: `@Disabled` или упавший assumption guard валят `check-test-runs.py`.

**Под-тег `slow` — что именно медленно.** Медленный класс несёт второй тег из словаря:
`slow:konsist`, `slow:migration`, `slow:compose`, `slow:data`, `slow:platform`. Основной тег
(`fast`/`slow`) остаётся: CI выбирает по нему, и класс без него не попал бы ни в один прогон.
Словарь закрытый — гейт `TestTagCoverageTest` проверяет и основной тег, и под-тег.

```kotlin
// Три формы теста (singularity-todo-test-helpers skill):
testVm({ vm: MyVm -> vm.state }) { MyVm(deps, scope = this) }
ctx.act { it.onIntent(Intent.Load) }
ctx.assertIs<UiState.Content>()
awaitState { vm.state.value is UiState.Content }   // виртуальное время, не delay()
```

**Тест VM — через харнесс** (`testVm` из `shared/src/commonTest/.../test/helpers/RunVmTest.kt`),
и это гейт: `SharedHarnessConventionsTest`, правило «runVmTest is used for ViewModel tests» —
свой scope руками = падение. Исключения и их причины — `EXEMPT_VM_TEST` в
`shared/src/jvmTest/.../arch/SharedHarnessConventionsTest.kt` (карта «файл → причина»;
типичная повестка: поток часов не кончается → виртуальное время не доводится до idle, scope
не может быть тестовым).

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
**Stale-test-classes is NOT a thing (verified 2026-10-04)** — a plain rerun after editing a test
recompiles and re-executes; `--rerun-tasks` — только отладочная костыль. ADR `2026-10-04-configuration-cache-hardening` §A3.

**Добавил `TestTag` → пересобери каталог.** `Maestro/TAGS.md` — золотой файл: его
сгенерированная часть сверяется с `TestTags.kt`, и расхождение падает в
`TestTagsCatalogJvmTest` — то есть на полном JVM-прогоне (≈40 мин). Пересборка:

```bash
./gradlew :shared:jvmTest -PupdateGoldens=true   # переписывает сгенерированную часть TAGS.md
```

`scripts/check-test-tags-catalog.py` делает ту же сверку за секунды, без JVM, —
он и стоит в гейтах; полный прогон нужен, чтобы *переписать* файл, а не чтобы
*узнать*, что он устарел.

## expect/actual порты

Порт — интерфейс в `commonMain`, два actual, `single<Port>` в обоих
`PlatformModule.*.kt`. Единственный expect/actual seam — `platformModule()`.
Инвентарь портов, фабричные функции, время и NavGraphs — `docs/PLATFORM-REFERENCE.md`.

## Сборка

**`./gw` вместо `./gradlew`.** Параллельные worktree делят `~/.gradle`, а делённый
Gradle Home — это общий лок кэша и общий реестр демонов: сборка толкается с
соседями и идёт в разы дольше. `./gw` даёт worktree приватный `GRADLE_USER_HOME`
(кеши и JDK остаются общими — их не пишут одновременно). Мелочь, но на машине
с несколькими checkout'ами это разница между 5 и 40 минутами.

```bash
just gate         # ВСЕ гейты: check.sh → detekt → just cr → just gm agenda (SKIP_MAESTRO=1 — без flows)
./check.sh                    # реестр гейтов + тесты + Android + detekt
bash scripts/ci/static-gates.sh   # ТОЛЬКО скриптовые гейты (≈1 мин, без JVM)
just tests::impact    # тесты, которые эта правка может сломать (карта по дереву)
./gw :shared:jvmTest     # быстрый цикл (без -Ptest.tags = только fast)
./gw :shared:jvmTest -Ptest.tags=fast,slow   # полный набор, как в CI
./gw :shared:jvmTest -Ptest.tags=slow:migration   # одна категория медленных
./gw :androidApp:assembleDebug   # Android
./gw :desktopApp:run      # Desktop (xvfb-run -a)
./gw :desktopApp:test     # Desktop UI tests (задача `test`, не `jvmTest`)
./gw :shared:exportToolSurface   # перегенерировать config/docs/tool-surface.json

just lint        # detekt (все модули с baseline), enforcing
just detekt-fix  # auto-fix detekt + ktlint in-place
```

**Карта затронутых тестов — `just tests::impact`.** Ответ на вопрос «что эта правка
ломает?» считается по дереву, а не выдумывается: правка в области тянет тесты этой
области, правка в классе тянет тесты, которые этот класс упоминают, изменённый тест
тянет сам себя. Это **кандидаты**, а не вердикт: карта никогда не говорит «вне карты
безопасно» — она говорит, что стоит прогнать первым.

**Пуш в ветку PR — без `--no-verify`.** `pre-push` hook (`.githooks/pre-push`) проверяет
**пушимое дерево**: структурные гейты + `detekt --auto-correct` (сам чинит большую часть
находок). Пропуск хука отправляет ту же находку в CI — минуты вместо получаса. `--no-verify` —
только для WIP-коммитов.

**Находки detekt — одним файлом:** `shared/build/reports/detekt/detekt.xml` (путь, строка, правило);
консоль Gradle даёт только счётчик. **Правило и его подоплёка — в комментариях
`config/detekt/detekt.yml`**: там, почему порог такой; кастомные правила — в `detekt-rules/`
(напр. `NoDroppedUiStateProperty`: свойство состояния — обязательный параметр **каждого**
варианта, дефолт запрещён). `ClassSignature` — сигнатура data class, влезающая в строку, пишется
в одну строку (ADR `2026-10-09-detekt-class-signature-gotcha`).

> `just <recipe> name=value` **не** работает (just 1.57.0: `tags=agenda` парсится как env var, не как параметр). Используй позиционную форму: `just gma` (алиас для `gm agenda`).

**Порядок detekt.** Сначала `:shared:detekt` — там почти весь код, и замечание там
останавливает проверку через две минуты, а не через четыре модуля:

```bash
./gw :shared:detekt                                  # сначала — 90% кода
./gw :desktopApp:detekt :androidApp:detekt :mcp-server:detekt   # потом остальные
```

**Компилируй сразу после связной правки.** Правка, которая тянет за собой соседний
файл (сигнатура, интерфейс, DI-биндинг), проверяется компиляцией в ту же минуту:
`./gw :shared:compileKotlinJvm` (для тестов — `:shared:jvmTestClasses`).
Открытие полного прогона на сломанной компиляции стоит сорока минут ожидания,
и диагностика «упало в тестах» уводит не туда: компиляция падает раньше и падает
другой строкой.

**Гейтовый venv принадлежит checkout'у.** `sgate` — editable-установка `scripts/src/`,
поэтому венв, поставленный в другом worktree, меряет *тот* checkout: гейт зелёный,
а проверял он чужое дерево. `bash scripts/ci/preflight-gate-tools.sh` печатает, чей
это checkout (строка `sgate`; `FOREIGN` — не тот). Свой венв на checkout:

```bash
SINGULARITY_TOOLS_VENV="$HOME/.venvs/singularity-todo-$(basename "$PWD")" \
    bash scripts/ci/setup-local-tools.sh
export PATH="$HOME/.venvs/singularity-todo-$(basename "$PWD")/bin:$PATH"
```

> `xvfb-run` есть не везде: `:desktopApp:test` идёт без дисплея, а `:desktopApp:run`
> — GUI, и ему нужен X-сервер (`xvfb-run -a ./gw :desktopApp:run`). Если `xvfb-run`
> не установлен, это не повод пропускать тесты — они headless.

> **Каталог инструментов — производный файл.** `config/docs/tool-surface.json`
> читает MCP-сервер и агент; он генерируется (`./gw :shared:exportToolSurface`) и
> проверяется гейтом. Меняешь схему или описание инструмента — перегенерируй
> сразу, иначе гейт скажет, что каталог устарел, а не что ты изменил.

> **Гейты — одной командой, со сводкой.** `sgate run-all` печатает таблицу и панель
> на каждый упавший гейт: это много строк, из которых читателю нужны только
> падения. `sgate run-all --summary` печатает по строке на каждый упавший гейт
> с причиной — этого хватает, чтобы решить, что делать дальше. Полный отчёт нужен
> только когда причина из сводки непонятна.
>
> ```bash
> sgate run-all --summary              # сводка: по строке на упавший гейт
> sgate run-all                        # полный отчёт (таблица + панели)
> sgate run-all --gate "write tool"    # один гейт по имени
> ```
>
> **Долгий запуск — одна фоновая задача, а не цикл опроса.** Задача сама
> печатает финальные строки (`BUILD SUCCESSFUL` по каждому шагу) — тогда опрос
> не нужен вовсе. Десять `sleep 30 && tail` — это десять раундов контекста
> впустую. Лог, который нужен потом, пиши в файл с уникальным именем
> (`build/diagnostics/<что>-<метка>.log`), а не перетирай общий.

## CI/CD

Единственный список гейтов — **реестр** (`scripts/src/sgate/commands/_registry.py` +
`scripts/src/sgate/gate_manifest.py`); `scripts/ci/static-gates.sh` и
`docs/scripts/gate-registry.md` **генерируются** из него, руками их не правь:
`PYTHONPATH=scripts/src python3 -m sgate render-static-gates --write` и
`python3 -m sgate render-registry`. Джоб `static` в `ci.yml` и `check.sh` берут
тот же реестр: гейт не может жить на одной поверхности и отсутствовать на другой.
Новый гейт = одна запись в реестре + строка `gate blocking|advisory <name> <cmd>`;
`|| true` на строке `gate` запрещён. Branch protection требует **один** check —
`CI gate`. Состав workflow'ев, частей A–G мета-гейта и рецепты — скилл
`singularity-todo-quality-tools`.

**Гейты «меры», а не «булевы»** — все блокирующие; спека:
`openspec/specs/test-execution-integrity/spec.md`, ADR `2026-10-04-measurement-integrity`.
`check-test-runs.py --require <set>` (прогнал меньше классов/тестов, чем floor, **или** пропустил
хоть один) · `:koverVerify` (INSTRUCTION/BRANCH/LINE покрытие `com.singularity.todo.*` ниже floor 27/20/27)
· `check-flaky-tests.py --current DIR --previous DIR` · `unittest discover -s scripts/tests`
(регрессия в самих гейтах, ≈20 мс). Floor = **минимальный** легитимный прогон: падение —
расследовать, не регенерировать.

**Перед PR — ревью отдельным агентом** (`code-review-pr-workflow`): он получает диф, а не код,
и у него нет давления контекста реализации — поэтому он ловит то, чего не ловит гейт (контракт
состояния, алиас, запись в реестр issues). Гейт ловит механику; ревью ловит смысл.

## 🤖 Dogfooding: MCP Server

Агенты подключаются к `:mcp-server` через stdio и правят задачи/проекты/заметки/теги/ADR.
Запуск: `./gradlew :mcp-server:build && java -jar mcp-server/build/libs/mcp-server-jvm-*.jar --profile=ai-agent`.
Полный список tools (22 write/read + 13 AI gen) — `docs/architecture/mcp-server.md`.

MCP tools меняют state только через репозитории; `write_adr` пишет в `docs/decisions/`;
timestamps — `kotlin.time.Instant`; business errors → `isError:true`, internal → `-32603`.
Каждый AI-вызов пишет в `llm_usage` (tokens, `cost_usd_micros`, `duration_ms`, `profile_id`).
Профили изолируют данные: `ai-agent` (🤖) для dogfooding, `personal` (🏠) для своих задач.

## 🔄 spec-kit (Primary Workflow Automation)

spec-kit — **primary** workflow engine (hooks, gates, fan-out): `uv tool install specify-cli`. Полный справочник (slash-команды `/speckit.specify|plan|tasks|implement|converge|checklist|analyze`, `specify workflow run ...`, hooks, constitution) — `docs/spec-kit-guide.md`.

- **Behavior change** → workflow `openspec-change`; **ADR-only** (нет spec-level изменения) → `adr-create`; **не нужен:** bug fix, test-only, dep update, doc-only, rename без контрактного изменения
- Hooks: после implement — `singularity-adr validate` (`.specify/extensions.yml`); после правок ADR — `./scripts/refresh-decisions-digest.sh`
- **Issue registry:** `.specify/issues/<slug>.yaml` — record на change (source of truth); импорт легаси `python3 scripts/openspec-import-all.py [--dry-run] [--replace]`. Запись создаётся **сразу** после создания ченджа — гейт `issue registry covers every change` падает на незарегистрированном чендже (четыре таких прошли в `main` мимо гейта).
- Constitution: `.specify/constitutions/singularity-todo.md`

## 🔧 Run-loop (UI-верификация)

**Android:** `mcp__android_emulator__android_{preflight,build_and_run,ui_status,screenshot,ui_describe,
ui_resolve,ui_tap,ui_type_text,logs}` — screenshot до и после действия, `android_logs` для крашей.
**Desktop:** `./gradlew :desktopApp:test` или `xvfb-run -a ./gradlew :desktopApp:run`.

**Desktop-тесты — по скиллу `singularity-todo-desktop-compose-ui-tests`:** харнесс,
ожидания, диагностический bundle и — отдельным разделом — компонентные тесты
(экран в изоляции): размер окна, `ModalBottomSheet` в своём окне, long-press через
`SemanticsActions.OnLongClick`. Читай его до написания теста: ответы на «как
дождаться узла» и «почему long-press не срабатывает» лежат там, а не в коде.

**Android CLI (agentic):** `android studio check` · `analyze-file` · `find-declaration` · `find-usages` ·
`render-compose-preview` · `version-lookup` · `open-file`; **Journeys:** `android journey create|run <name>`.
Подробности: `singularity-todo-android-cli` skill.

**DB inspect:** `adb shell run-as com.singularity.todo cp databases/singularity.db /sdcard/` + `adb pull` → `sqlite3 /tmp/singularity.db ".schema"` (android); desktop — `sqlite3 ~/.local/share/singularity/databases/singularity.db ".schema"`.

> **Эмулятор**: `ensure-emulator` — единственная точка входа за устройством; свой `adb devices | awk`
> найдёт только уже поднятый девайс. Пин `SERIAL=…` recovery не выключает. Не подкручивайте `-gpu` —
> дефолтный host GPU периодически падает в gfxstream.
>
> **Читай скилл `singularity-todo-emulator-launch` целиком, а не по памяти.** Там рецепт запуска,
> `settings put secure show_ime_with_hard_keyboard 0` после **каждого** перезапуска AVD и разбор
> падений gfxstream по сигнатуре. Без этой строки клавиатура остаётся поднятой, и все замеры
> флоу после рестарта неверны — так потеряно 6 замеров в одной сессии (2026-10-28).

## 🤖 Coroutine test failures

Любой JVM-тест при падении пишет `build/diagnostics/<TestClass>/coroutines.txt` (состояние,
контекст, job-иерархия, стектрейлы) — читай **первым**: кадры приложения раньше kotlinx указывают,
где корутина была, умерла она там, где `lastObservedStackTrace`, а одинаковые стектрейсы — не
доказательство утечки. ADR `2026-10-03-kotlinx-coroutines-debug.md`, разбор — шаг 5 `debugging-investigation`.

## ❌ Что НЕ делать

1. **`runBlocking` в ViewModel init** — вместо этого `combine(...)` + `flatMapLatest`
2. **`*Blocking()` методы в репозиториях** — только suspend + Result<T>
3. **MockK / Mockito** — fakes для state-тестов; MockK только для исходящих вызовов
4. **Pass-through CRUD use cases** — VMs инжектят репозиторий напрямую (AI — допустимо);
   enforced by `PassThroughUseCase` detekt rule
5. **`java.io.File` напрямую** — только через `FileSystem` порт
6. **`require { throw ... }` внутри лямбды** — `require` сам бросает
7. **Импортировать Koog-типы вне `feature/ai` и `core/di`**; **грубые изменения Room
   schema** без `SCHEMA_VERSION` + `AutoMigration`
8. **Пайпить вывод команды в `tail`/`head`** — `cmd | tail -3` убивает `cmd` сигналом SIGPIPE:
   код выхода пайпа — это код выхода `tail`, а не команды, и `git push` уходит с 141, не
   отправив ничего. Перенаправляй в файл (`cmd > /tmp/probe/push.log 2>&1`) и читай файл.
   Замер: 6 пушей в одной сессии ушли с 141 и «успешным» кодом выхода.
9. **Пушить по SSH без keepalive, когда pre-push длинный** — git открывает соединение с
   GitHub *до* хука, и пока хук крутит detekt (несколько минут), соединение простаивает:
   GitHub закрывает его, и пуш падает с 141, хотя хук только что напечатал
   `=== Pre-push OK ===`. Это тот же 141, но другая причина. Лечится keepalive:
   `git config core.sshCommand "ssh -o ServerAliveInterval=30 -o ServerAliveCountMax=10"`.

## 🧠 Decision log

Если задача меняет архитектуру, контракт или обнаруживает неочевидный workaround — создай запись в
`docs/decisions/YYYY-MM-DD-<slug>.md` и пересобери digest (`./scripts/refresh-decisions-digest.sh`).
**Писать:** выбор между несколькими разумными вариантами; неочевидный workaround; изменение
контракта между слоями. **Не писать:** опечатки, форматирование, новый use case по существующему
паттерну. Формат: frontmatter + `Context / Idea / Decision / Rationale / Consequences / Links`;
policy — `docs/doc-maintenance.md`.

## 🗂 Skills

Скилл — это **процедура**: что агент делает. Состояние кода — это документ в `docs/architecture/`
(см. обзор в `DIGEST.md`). Каталог — `docs/SKILLS-CATALOG.md` (auto-generated, не редактировать);
правила написания — `writing-for-agents`.

> **Фича «готова», но ничего не делает** — самый частый дефект: код компилируется, покрыт
> тестами и **не вызывается никем**. Проверка: `scripts/find-unwired-surfaces.py`.

Issue tracker — `docs/agents/issue-tracker.md`; доменные доки — `docs/agents/domain.md` (`CONTEXT.md`
в корне, ADR-и в `docs/decisions/`). Список снятых скиллов и причина каждого — `docs/doc-maintenance.md`.
