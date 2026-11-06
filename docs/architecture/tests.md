# Тесты — правила и рецепты

Правила тестов — здесь, а не в `AGENTS.md`: файл, который читает каждая сессия,
должен помещаться в один проход, а правило тестов — это десятки строк, которые нужны
тогда, когда пишешь тест или разбираешь падение.

**Кто владеет сценарием.** Если сценарий проверяется в двух тирах, у него один владелец.
Таблица в [test-tier-ownership.md](test-tier-ownership.md) называет владельца, говорит
почему именно он, и что стало с копией в другом тире: удалена, оставлена осознанно (с
причиной) или отложена до следующей работы.

`commonTest` (pure Kotlin) выполняется внутри `./gradlew :shared:jvmTest` — отдельного таска нет.
`jvmTest` — Room + SQLite + Konsist arch tests. **Fake вместо моков** — все двойники в
`test/fakes/FakeRepositories.kt`. Каждый тестовый класс обязан иметь `@Tag`, иначе
`-Ptest.population=fast` в CI (и `-Ptest.population=full` в ночном прогоне) молча его
исключит (`TestTagCoverageTest`). **`slow` = класс
пересекает границу процесса** (Compose-харнесс, реальный файл/БД/часы, Konsist-скан, spawn),
не «долгий». Никаких skipped: `@Disabled` или упавший assumption guard валят `check-test-runs.py`.

**У арх-гейтов свой таск — `:shared:archTest`.** Классы из `com.singularity.todo.arch`
читают всё дерево, и их Konsist-скоуп строится один раз на JVM: четыре форка построили бы
его четыре раза (≈25 с на разбор 1 238 файлов `commonMain`). Поэтому `:shared:jvmTest`
исключает `com.singularity.todo.arch.*`, а `:shared:archTest` их выполняет — в одном форке.
Разделение по пакету, файлы не переезжают: обе задачи берут одни и те же скомпилированные
классы и делят их фильтром. Входы арх-гейтов объявлены на `archTest`, а не на `jvmTest`:
гейт, который не сбрасывается изменением того, что читает, выносит вердикт о файле, который
не перечитывал.

**Локальный цикл умеет «только затронутое».** `./check.sh --affected` (или
`just tests::check-affected`) запускает тесты, которые изменение может сломать (выборка
`scripts/test-impact-map.py` — та же, что у CI-джобы «Affected tests»), и detekt только по
изменённым файлам (`scripts/lint-changed.py`). Это полоса, а не сокращённый цикл: все гейты
runs, а полный цикл по-прежнему гоняет CI. Floor прогонов к полосе не применяется — он
описывает полный набор.

**Быстрая полоса — то, что агент гоняет на один issue.** `./check.sh --fast` компилирует
приложение, запускает те классы, которые изменение может сломать, линтит только изменённые
файлы и гоняет деревянные статические гейты. Пропускает она медленную половину: весь
JVM-набор, арх-гейты, ADR- и desktop-наборы, модульный detekt-обход и гейты гейтовой машинерии
(5 минут на проверку самих гейтов). Пропущенное судит не «никто»: полный цикл гоняет CI, и
merge-пайплайн гоняет его снова на ветке, которую пришлось двигать. Полный цикл —
`./check.sh` без `--fast`; ADR — `2026-11-01-the-agent-runs-the-fast-lane.md`.

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

**Тестовая БД — один владелец.** Каждый JVM-тест, которому нужна настоящая база, берёт её
из `DatabaseTestFixture` (`shared/src/jvmTest/.../core/database/DatabaseTestFixture.kt`):
`onDisk()` — файл во временном каталоге, которым владеет фикстура; `inMemory()` — без
файла. `database(name)` даёт вторую базу, `path(name)` — путь, `reopen(name)` — перезапуск
поверх того же файла, `connect(name)` — сырое соединение до/после Room. `close()` закрывает
всё, что открыла фикстура. Файл на диске выбирается только там, где файл и есть предмет
теста (миграции, рестарт, WAL, «файл существует»); остальное — память. Правило
механическое: `:shared:archTest` (`DatabaseTestFixtureHasOneOwnerTest`) валит сборку, если
файл вне фикстуры открывает БД или заводит временный каталог под неё. В скан входят
`jvmTest` и `commonTest`: последний исполняется внутри `:shared:jvmTest`, и копия там была
бы той же второй жизнью БД.

**`awaitState` будит background-scope.** Один `advanceUntilIdle()` доводит только
*foreground*-работу, поэтому VM на `VmScope.Background` (долгоживущий коллектор) не увидит
изменение, которое тест только что сделал. `awaitState` после `advanceUntilIdle()` делает
`runCurrent()`: он диспатчит то, что стоит в очереди сейчас, и не двигает часы — то есть не
запускает бесконечный таймер. Так три теста приветствия, висевшие по минуте, снова
завершаются.

**`@Tag("slow")` — тест, который локальный цикл не гоняет.** Дефолтный прогон
исключает `slow`, поэтому поломка в таком тесте видна только в CI: `McpServerEndToEndTest`
лежал сломанным в `main` несколько мержей. Полный прогон (`-Ptest.population=full`) обязан
выполнить каждый `slow`-класс — это проверяет `scripts/check-slow-tests-ran.py` (шаг CI сразу
после прогона: следующие Gradle-шаги могут перезаписать отчёт). Модули `adr-cli` и
`adr-core` тоже гоняются в CI — иначе их `slow`-классы не выполнялись бы нигде.

## Диагностика падения

Красный тест должен отвечать на вопрос «что было на экране и как далеко дошёл тест» без
открытия артефактов. Ответ — **бандл**: набор файлов, который лежит рядом с отчётом и имя
которого едет в само сообщение об ошибке.

| Продюсер | Артефакты | Куда | Кто везёт в CI |
|---|---|---|---|
| `shared` — `FailureContextExtension` | `coroutines.txt` | `shared/build/diagnostics/<TestClass>/` | `ci-evidence-<run_id>` |
| `shared` — `TestFailureDetailsWriter` | `failure-details.txt` | `shared/build/diagnostics/<TestClass>/` | `ci-evidence-<run_id>` |
| `desktopApp` — `FailureBundle` | screenshot, screen tree, steps, nodes, db-state, kermit log, coroutines | `desktopApp/build/diagnostics/<TestClass>/attempt-N/` | `ci-evidence-<run_id>` |
| `androidApp` (instrumented) — `FailureBundle` | screenshot, screen tree, steps, environment | `/data/local/tmp/singularity-diagnostics/<TestClass>/attempt-N/`, затем `androidApp/build/diagnostics/` | `ci-evidence-<run_id>` |

Правила контракта:

1. **Каждый артефакт снимается независимо.** Упавший скриншот не должен стоить шагов и
   окружения: снимок экрана — единственное, чего нельзя восстановить позже, а шаги — то, что
   отвечает на «как далеко дошёл тест».
2. **Имена артефактов едут в сообщение об ошибке.** Бандл добавляет себя в исключение как
   suppressed-exceptions, поэтому отчёт сам называет файлы, и до артефакта доходишь по отчёту.
3. **Каждая попытка — свой каталог.** `attempt-N/` перезаписывается на старте попытки: два
   ретрая не смешивают артефакты. На Android файлы лежат плоско (`<Class>-attempt-N-<name>`),
   потому что umask приложения (0077) делает созданный им подкаталог недоступным оболочке.
4. **Два `FailureBundle` — две реализации одного контракта.** Кодом они не делятся:
   `desktopApp/jvmTest` и `androidApp/androidTest` — разные модули и разные source set'ы, и
   общий код означал бы тестовую зависимость, которую тащит ещё и прод-сборка. Набор
   артефактов, независимость захвата и правило про попытки — одни и те же.

Диагностика падения — не только бандл: `scripts/src/traceability/junit_xml.py` читает JUnit
отчёты, а `scripts/junit-annotations.py` и `scripts/ci/print-test-failures.sh` показывают
одну и ту же сводку по ним. Формат отчёта задаёт `build.gradle.kts` (`mergeReruns`,
`exceptionFormat = FULL`), поэтому читателю не нужно знать, какой модуль отчёт породил.

### Артефакты прогона

Что CI везёт с собой и что в каждом лежит:

| Артефакт | Что внутри | Кто его читает |
|---|---|---|
| `junit-results` | JUnit XML всех модулей | `check-test-runs.py`, аннотации, трейсировка |
| `allure-results` | сырые `*-result.json` и вложения | доказательство; читает отчёт |
| `allure-report-<run_id>` | собранный HTML-отчёт | человек |
| `ci-evidence-<run_id>` | бандлы диагностики, трейсировка, kover | человек, гейты |
| `html-reports-<run_id>` | Gradle HTML-отчёты всех модулей | человек |
| `build-log-<run_id>`, `build-log-detekt-<run_id>` | полный лог Gradle | человек |

Отчёт Allure собирается задачей `allureReport` в корневом `build.gradle.kts`: результаты трёх
модулей сливаются в один плоский каталог (`allureMergeResults`), потому что генератор читает
ровно один каталог и не заходит в подкаталоги. ADR —
`docs/decisions/2026-11-06-the-allure-report-is-generated-in-the-build.md`.

## Тесты гейт-сьюта

Сьют гейтов живёт в `scripts/tests/`, и точка входа у него одна:
`python3 scripts/run-gate-tests.py`. Раннер сам привязывает пакет `sgate` к
своему дереву и передаёт привязку дочерним процессам, поэтому прямой запуск
тестового модуля (`python3 scripts/tests/test_*.py`) обходит её — и измеряет
не тот checkout, который имеет в виду автор.

Правило привязки — **одно на все скрипты**: любой скрипт, который импортирует
`sgate.*`, обязан привязать пакет к своему дереву **до** импорта (`sys.path.insert`
с путём к `scripts/src`). Область правила — **весь репозиторий**, кроме
установленного пакета `scripts/src/` (его модули импортируют друг друга
абсолютными именами — они и есть пакет). Перечисление каталогов было бы
правилом, который выполняется именем каталога, а не скриптом: скрипт в
`scripts/lint/` или `infra/` импортировал бы пакет по тому, что лежит в
окружении, и никто бы об этом не сказал.

Исключение одно — пометка `# no-sgate:` в шапке, и она у тех скриптов, которые
не импортируют `sgate` вовсе: спросить, откуда взялся пакет, нельзя,
импортировав его. Проверяет правило `scripts/check-script-conventions.py`
(находка R2.5) — и привязку **после** импорта он находит так же, как её
отсутствие.

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
**`import kotlin.io.path.*` is caught by ktlint's `standard:no-wildcard-imports`** — use explicit imports
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

Тесты пишутся в `shared/src/{jvmTest,commonTest}`, двойники — в
`test/fakes/FakeRepositories.kt` (fake, не моки). Скилл
`singularity-todo-test-helpers` — три формы теста; `singularity-todo-testable-vm` —
харнесс ViewModel-тестов; `singularity-todo-test-flaky-prevention` — правила, по
которым тест не становится плавающим.
