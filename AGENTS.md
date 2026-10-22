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

Экспорты схемы Room — каталог `shared/schemas/com.singularity.todo.core.database.AppDatabase/` (файл на версию).
В `jvmTest` их читает **один** владелец: `MigrationRoundTripSupport.applyExportedSchema(N, connection)`
(и `schemaFile(N)` — путь к экспорту). Тест миграции пишет «посади вот это — проверь вот это»,
а не SQL; свой экземпляр фикстуры — это copy-paste, который ловит `MigrationFixtureHasOneOwnerTest`.

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
`-Ptest.population=fast` в CI (и `-Ptest.population=full` в ночном прогоне) молча его
исключит (`TestTagCoverageTest`). **`slow` = класс
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

**`@Tag("slow")` — тест, который локальный цикл не гоняет.** Дефолтный прогон
исключает `slow`, поэтому поломка в таком тесте видна только в CI: `McpServerEndToEndTest`
лежал сломанным в `main` несколько мержей. Полный прогон (`-Ptest.population=full`) обязан
выполнить каждый `slow`-класс — это проверяет `scripts/check-slow-tests-ran.py` (шаг CI сразу
после прогона: следующие Gradle-шаги могут перезаписать отчёт). Модули `adr-cli` и
`adr-core` тоже гоняются в CI — иначе их `slow`-классы не выполнялись бы нигде.

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

## Сборка и гейты

Справочник — скиллы `singularity-todo-build-and-gates` (сборка, detekt, гейты,
загрузка хоста, долгие запуски) и `singularity-todo-ci-cd` (реестр гейтов,
поверхности CI, ожидание прогона, вердикт ревью). Ниже — строки-указатели.

- **`./gw` вместо `./gradlew`** — приватный `GRADLE_USER_HOME` на worktree; общий
  `~/.gradle` — общий лок кэша и общий реестр демонов (5 минут против 40).
- **Хост бывает занят** — `./sg host-load` (порог 2.0/ядро, измерен): занят →
  целевой набор (`--tests`) или ждать; полный прогон — когда целевой зелёный.
- **Долгий запуск — одна фоновая задача, а не цикл опроса.** `./wait-for <log>
  <паттерн>` ждёт строку в логе и печатает хвост один раз.
- **Компилируй сразу после связной правки** — `./gw :shared:compileKotlinJvm`
  (для тестов `:shared:jvmTestClasses`), иначе падение компиляции прячется за
  «упало в тестах» и стоит сорока минут ожидания.
- **Пуш в ветку PR — без `--no-verify`**: pre-push hook проверяет пушимое дерево
  (структурные гейты + `detekt --auto-correct`); пропуск хука отправляет ту же
  находку в CI.
- **`just lint-changed`** — detekt только по изменённым файлам (секунды); он же в
  pre-commit. Полный модульный detekt — `just lint`.

> **Дешёвая проверка — отдельно, когда нужен её ответ.** `./gw :shared:detekt` —
> пара минут; `./gw :shared:jvmTest -Ptest.tags=slow:konsist` — десятки минут.
> В одной команде вердикт detekt придёт в конце, вместе с медленной: правило
> «сначала дешёвое» экономит не минуты, а цикл ожидания. И не гоняй detekt
> вручную перед пушем: pre-push гоняет его сам и блокирует пуш на находке.

## CI/CD

Единственный список гейтов — **реестр** (`scripts/src/sgate/commands/_registry.py` +
`scripts/src/sgate/gate_manifest.py`); `scripts/ci/static-gates.sh` и
`docs/scripts/gate-registry.md` **генерируются** из него, руками их не правь.
Джоб `static` в `ci.yml` и `check.sh` берут тот же реестр: гейт не может жить на
одной поверхности и отсутствовать на другой. Branch protection требует **один**
check — `CI gate`. Справочник — скилл `singularity-todo-ci-cd`.

**Ждёшь CI — одной командой, а не циклом опроса:**

```bash
gh pr checks <pr> --watch --interval 60   # ждёт до конца, печатает вердикт
```

Десять `sleep 30 && gh pr checks` — это десять раундов контекста впустую, и
ровно то, что запрещает абзац про долгие запуски выше. `--watch` ждёт сам и
отдаёт финальный вердикт одной строкой.

**Один `--watch` на PR — и никаких снимков рядом с ним.** Пока идёт `--watch`,
состояние не снимают: `gh pr checks` для «как там» — это тот же цикл опроса,
только тише. Дорогой локальный прогон — тоже: `python3 -m unittest discover -s
scripts/tests` (≈2,5 мин) запускают **один раз**, пишут вывод в файл и читают
сводку по совпадениям, а не гоняют заново ради второго вопроса.

## 🤖 Dogfooding, spec-kit, Run-loop

- **MCP-сервер** (задачи/проекты/заметки/теги/ADR из агента) — скилл
  `singularity-todo-mcp-dogfooding`; полный список tools — `docs/architecture/mcp-server.md`.
- **spec-kit / OpenSpec** (решение «change или ADR», артефакты, верификация) —
  скилл `singularity-todo-openspec-workflow`; полный справочник — `docs/spec-kit-guide.md`.
  Реестр ченджей — `.specify/issues/<slug>.yaml`, запись создаётся **сразу** после
  создания ченджа.
- **UI-верификация** — скиллы `singularity-todo-emulator-launch` (запуск и
  восстановление AVD), `singularity-todo-desktop-compose-ui-tests` (харнесс и
  компонентные тесты), `singularity-todo-android-cli` (CLI и разбор БД).

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
5. **`java.io.File` в `commonMain`** — только через `FileSystem` порт (`core/files/`).
   В `jvmMain`/`androidMain` это и есть реализация порта, там `java.io.File` — правильный
   инструмент; то же в тестах, которые читают дерево исходников. Правило держит
   feature-код, а не платформенный слой: в репозитории `java.io.File` встречается
   в 110 файлах, и запрет без границы учит только игнорировать запрет.
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

## 🗂 Коммиты

Один коммит — одна мысль; в этом репозитории так принято, и diff на 100 файлов
читается плохо. Когда работа распадается на части (фичи, пункты списка,
«это и это вместе»), разложи её **до** коммита, а не после:

1. `git add -p` по логическим кускам — или `git add <путь>` по файлам целиком,
   если куски совпадают с файлами;
2. один `git commit` на кусок, с сообщением в стиле репозитория:
   `feat(area): что изменилось` + абзац «почему» + ссылки на ADR и OpenSpec;
3. каждый коммит должен собираться и проходить гейты сам по себе — иначе
   bisect указывает на место, которого не существует.

Регенерируемые файлы (`scripts/ci/static-gates.sh`, `docs/scripts/gate-registry.md`,
`docs/SKILLS-CATALOG.md`) в коммит попадают вместе с тем, что их породило.

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
