# Kotlin gate audit: the `shared/src/jvmTest/.../arch/` corpus

> **Reference, not a procedure.** This is the inventory of the repository's Kotlin-side
> architecture gates, taken on 2026-10-21 against commit `674d1c1e`. It goes stale
> whenever the corpus changes, so a change to the corpus updates this file in the same
> commit.

Scope: `shared/src/jvmTest/kotlin/com/singularity/todo/arch/**` — 48 files, 43 of them
`*Test.kt`, plus 5 helpers. Companion to `docs/engineering/script-tooling-audit.md`,
which inventoried `scripts/**` and the four Python packages. That audit's scope
explicitly excluded product code; this one covers the Kotlin gates, which the Python
audit could not see and did not try to.

The question this document answers is not "how many tests are there". It is **which
rules of this repository live in these 43 files, which of them can actually fail, and
which of them are the same rule twice**. The third question is the one that matters:
an architecture gate that cannot fail is indistinguishable from a gate that passes,
and both look green in CI.

Three classifications drive every row below:

1. **A rule that exists only here stays.** The Konsist layer, the DAO-scoping rules,
   the seam registry, the tag inventory — no library enforces them, and none of the
   Python gates know they exist.
2. **A rule enforced twice is a liability.** Two gates that check the same property
   by different means drift, and the weaker one silently stops covering what the
   stronger one covers. Two of the findings below are of this class.
3. **A gate without a positive control is a claim, not a check.** It cannot
   distinguish "the rule was violated" from "the rule did not apply". Where the
   corpus carries a self-test — a planted defect that must be caught — that is
   recorded explicitly in the inventory.

## Baseline

Measured on the checkout at `674d1c1e`, no local state:

| Surface | Result |
|---|---|
| `shared/src/jvmTest/kotlin/com/singularity/todo/arch/` | 48 files, 43 `*Test.kt` + 5 helpers, 12 937 lines |
| Test methods across the 43 `*Test.kt` | 155 |
| `:shared:jvmTest` | runs in `check.sh` step 3/9 and in the CI `tests` job; not a `static-gates.sh` gate |
| `scripts/ci/static-gates.sh` | 30 gate lines; **none** references the `arch/` corpus |
| `scripts/src/sgate/commands/_registry.py` | no entry, no control, no sabotage for any `arch/` file |
| `config/docs/test-runs-baseline.txt` | counts `shared:jvmTest` as one number (1 836), not per class |

The last three rows are the structural fact this audit is about. `check-test-runs.py
--require shared:jvmTest` counts **how many** tests executed. It has no notion of
**which rules** they encode. Nothing in the Python tooling can enumerate the 155
tests, name the rule behind each, or tell a reader that one of them stopped
checking anything.

## Inventory

`Позитивный контроль` — есть ли в файле «злой» пример, который обязан сработать
(planted defect / self-test). `—` означает, что контроля нет: тест проходит на
пустом совпадении и не может отличить нарушение правила от неприменимости правила.

| Файл | Правило | Техника | Позитивный контроль | ADR | Классификация |
|---|---|---|---|---|---|
| `ArchitectureTest.kt` | 20 правил: границы слоёв фичи, тема, koog-импорты, JVM-API в commonMain, DI-импорты, `Blocking()` в репозиториях, ownership-скоуп DAO-мутаций, user-скоуп чтений, nav3 backStack, `SyncEngine`/`OutboxService`/`SyncBootstrapper` | Konsist `scopeFromExternalDirectory(commonMain)` + regex по `file.text` | — | 2026-09-26-konsist, 2026-09-27-di-module, 2026-09-27-write-layer, 2026-10-03-nav3, 2026-10-05-materialkolor | **fix** (F1, F2) |
| `AtBackendConfinedTest.kt` | `at(1)`-демон удалён и остаётся удалённым; AT-аннотированный код не исполняется в запертом контексте | Regex-скан по исходникам + позитивный контроль на трёх формах | ✅ (3) | 2026-10-06-notification-port, 2026-10-07-desktop-reminders | keep |
| `CachedIdentityReadArchitectureTest.kt` | Чтение кэшированной identity (`scopedUserId`, `currentUserId`) внутри реактивного блока запрещено | AST-подобный обход тела `flow { }`/`combine { }` + скан продакшена | ✅ (11 синтетических случаев) | 2026-10-04-derived-identity-flows | keep |
| `CapabilityFlagTest.kt` | Флаг возможности может быть константой, только если на месте сказано, почему это так | Regex по KDoc-описанию флага + проверка наличия пояснения | — | — | **investigate** (F9) |
| `ClassBodyScannerAgreementTest.kt` | Kotlin- и Python-сканеры «должен ли класс исполняться JUnit» дают одинаковый вердикт на каждом реальном тест-классе | Сравнение `RunnableTestMember` против `scripts/src/scanners/test_classes.py` | ✅ (5) | 2026-10-19-ast-grep | keep |
| `CommonMainJvmApiTest.kt` | Ни одного `java.*` / `javax.*` / `android.*` в `commonMain` | Regex по строкам + baseline | ✅ (5 строк-примеров) | — | keep |
| `CrashReportingWiringTest.kt` | ViewModel, который может упасть, обязан принимать `CrashReportingPort`; ViewModel не конструирует порт сам | Regex по `init`-блокам + «воронка» от каталога ViewModel'ов | ✅ (2) | 2026-10-05-no-direct-dispatchers, 2026-10-05-positive-tests | keep |
| `CrossProfileReadRegistryTest.kt` | Санкционированный список кросс-профильных чтений закреплён и не может сгнить | Сверка списка с реальными методами DAO + проверка, что метод всё ещё используется путём re-arm | ✅ (4) | — | keep |
| `CrossUserWriteRegistryTest.kt` | Санкционированный список кросс-пользовательских записей закреплён | Сверка списка с реальными методами репозитория | ✅ (3) | — | **fix** (F7) |
| `DetektSourceSetsAreAllScannedTest.kt` | Каждый source set, объявленный модулем, должен сканироваться detekt | Чтение `build.gradle.kts` + сравнение со списком detekt | ✅ (3) | — | keep |
| `DiFacadeTest.kt` | `core/di/Modules.kt` — фасад-агрегатор: биндинги живут только в per-domain `*DiModule.kt` | Regex по тексту `Modules.kt` + список известных фасадных функций | ✅ (6) | 2026-09-27-di-module | keep |
| `DroppedResultIsReportedTest.kt` | `Result`, который никто не посмотрел, либо обработан, либо отрепорчен там, где произведён | Резолв типа возврата по объявлению + разбор тела метода | ✅ (11) | — | keep |
| `EnqueueSiteIsScannedTest.kt` | Множество мест, которые сканируют write-правила, равно множеству мест, которые enqueue'ят | Сопоставление glob'а write-правил с реальными call-site'ами | ✅ (6) | — | keep |
| `EntityMapperCompletenessTest.kt` | Каждый параметр конструктора `@Entity` обязан появиться в маперере `toX()` | Разбор `Entities.kt` + `toX()`-функций, сверка множеств | ✅ (9) | 2026-09-30-draft-save, 2026-10-03-entity-mapper | keep |
| `ExtensionServiceRegistrationTest.kt` | Каждый зарегистрированный JUnit `Extension` резолвится в реальный загружаемый класс | Чтение `META-INF/services/...` + `Class.forName` | — | — | keep (проверка «список непуст» есть) |
| `KotlinTextFixtureTest.kt` | Kotlin-половина общей таблицы фикстур «где заканчивается этот блок» | Чтение `config/test-fixtures/kotlin-brace-matching.txt` + сверка с `KotlinText.blockBody` | ✅ (16) | — | keep |
| `KotlinTextTest.kt` | Собственные правила Kotlin-лексера, закреплённые по случаям | Модульные тесты лексера | ✅ (8) | — | keep |
| `MaestroFlowTagsTest.kt` | Каждый `id:` в Maestro-flow резолвится в `TestTagsCatalog`; каждый `runFlow` указывает на существующий файл; каждый id команды — реальная команда Maestro | Regex по YAML + `TestTagsCatalog` (парсит `TestTags.kt`) | ✅ (5 + анти-вакуумный порог `ids.size > 40`) | — | **consolidate** (F4) |
| `PlaintextTokenIsolationTest.kt` | Ни один токен не пишется в plain-text preferences | Поиск вызовов `DataStore<Preferences>` по токен-полям | ✅ (1) | — | keep |
| `PlatformClaimWiringTest.kt` | Файл, который описывает себя как platform-specific, обязан лежать в source set'е этой платформы | Сопоставление KDoc-формулировки с путём файла | ✅ (1) | — | keep |
| `PlatformSeamGuardTest.kt` | Каждый platform-bound seam классифицирован, и каждая классификация всё ещё верна | Чтение реестра seam'ов + сверка с `PlatformModule.{android,jvm}.kt` | ✅ (5) | — | keep |
| `RunnableTestFixtureTest.kt` | Kotlin-половина общей таблицы «runnable test member» | Чтение `config/test-fixtures/runnable-test-members.txt` | ✅ (20) | — | keep |
| `ScopedReadQueryIsolationTest.kt` | Каждый `SELECT`, читающий профиле-владельческую таблицу, обязан скоупиться по профилю | Regex по `Daos.kt` + вывод scoped-таблиц из `Entities.kt` | ✅ (7 модульных + анти-вакуумный порог) | — | **fix** (F3) |
| `ScopedWriteQueryIsolationTest.kt` | Каждый `UPDATE`/`DELETE` в DAO обязан скоупиться по пользователю | Regex по `@Query` во всех commonMain-файлах | ✅ (12) | — | keep (см. F3) |
| `SelectorSerializerCoverageTest.kt` | Каждый вариант `Selector` обязан иметь запись в реестре сериализаторов | Сопоставление sealed-иерархии с реестром | — | — | keep |
| `SharedHarnessConventionsTest.kt` | Конвенции тест-харнесса `shared`: `awaitState` вместо `advanceUntilIdle`, `runVmTest` для ViewModel, никаких `stateIn`/`koinInject`/MockK | Regex по `shared/src/{jvmTest,commonTest}` | ✅ (19) | 2026-10-27-vm-test-scope | keep |
| `SingleOfKotlinDefaultsTest.kt` | `singleOf`/`factoryOf`/`viewModelOf` не должны указывать на класс с Kotlin-дефолтами (Koin их не применяет) | Regex по call-site'ам + разбор primary-конструктора | ✅ (7) | — | keep |
| `SubprocessConfinedTest.kt` | Каждый subprocess в репозитории порождается только через `Subprocess` | Regex по вызовам spawn + проверка allowlist-файла | ✅ (2) | 2026-10-07-closing-child-streams | keep |
| `SyncedEntityDeleteIsSoftTest.kt` | Каждый репозиторий, куда диспетчеризует pull-обработчик `DELETED`, обязан удалять мягко | Разбор тела `delete` + таблица диспетчеризации | ✅ (12) | — | keep |
| `SyncedWriteEnqueuesTest.kt` | Запись в synced-таблицу обязана enqueue-ить patch | Regex по DAO-вызовам + сверка с таблицами | ✅ (4) | — | keep |
| `SyncEngineTakesNoFeatureTypesTest.kt` | Sync-движок не принимает фичевые типы | Сверка сигнатур `SyncEngine` с core-портами | ✅ (1) | 2026-10-06-sync-engine | keep |
| `SyncPeriodicTriggerWiringTest.kt` | Один периодический драйвер на платформу, и никакой platform sniffing | Чтение `PlatformModule.*.kt` + проверка, что в sync core нет платформенных типов | — | — | keep (анти-вакуумный порог есть) |
| `SyncTransportIdentityTest.kt` | Ни один метод транспорта не принимает owner id от вызывающего | Regex по сигнатурам транспорта | ✅ (3) | — | keep |
| `SyncWriteIsAtomicTest.kt` | Каждый patch enqueue-ится внутри unit of work | Разбор тела метода + обход графа вызовов | ✅ (6) | 2026-10-05-who-owns-a-row | keep |
| `TestTagCoverageTest.kt` | Каждый тест-класс несёт `@Tag`, и каждое значение тега выбирается CI | Скан тест-классов + проверка `includeTags` в build-скриптах | ✅ (1) | 2026-09-25-test-suite-tag-defaults | keep |
| `TestTagsWiringTest.kt` | Каждая константа `TestTags` обязана применяться к composable | Regex по `Modifier.testTag` + сверка с `TestTags.kt` | — | 2026-09-30-draft-save, 2026-09-30-test-infra, 2026-10-04-undo-snackbar | keep |
| `TextFieldRewriteContractTest.kt` | Кто имеет право переписывать текстовое поле, и как код это говорит | Чтение таблицы контрактов в SKILL.md + сверка с владельцами | ✅ (6) | — | keep |
| `TextFieldShapeConventionTest.kt` | Каждое текстовое поле классифицировано (local / bridged / anchored), и классификация совпадает с кодом | Konsist-импорты (форма выведена из импортов, не из текста) + сверка с таблицей в SKILL.md | — | — | keep |
| `UiAutomationSelectorTest.kt` | Селектор — контракт: не строится из display-метки, и тег окна экспонирует свои `testTags` | Regex по `testTag` / `Modifier.semantics` | ✅ (3) | 2026-10-04-testtag-visibility | keep |
| `UnannotatedTestMemberTest.kt` | Каждый неприватный член тест-класса несёт аннотацию | Разбор тел тест-классов + поиск аннотаций | ✅ (11) | — | keep |
| `VendorSdkConfinementTest.kt` | Supabase SDK встречается только в транспортном и auth-seam'ах | Regex по импортам + сверка с allowlist | ✅ (3) | — | keep |
| `ViewModelInitOrderTest.kt` | Порядок инициализации свойств внутри ViewModel'ов | Разбор порядка объявлений vs `init`-блоков | ✅ (6) | 2026-09-30-vm-init-property | keep |
| `ViewModelTestCoverageTest.kt` | Каждый production-ViewModel имеет тест — или стоит в списке с причиной | Сверка каталога ViewModel'ов с тест-классами + `KNOWN_UNCOVERED` | ✅ (4) | 2026-10-04-mr5-retro-gate | **investigate** (F8) |

### Helpers

| Файл | Роль | Кто импортирует | Дублирует Python? |
|---|---|---|---|
| `KotlinText.kt` (193 стр.) | Лексер Kotlin: `stripComments`, `stripCode`, `matchingBrace`, `firstCodeBrace`, `blockBody`, `lineDepths` | 14 файлов | Да — `scripts/src/scanners/source_text.py` реализует те же шесть функций. **Но это не дубль, а контракт:** общая таблица фикстур `config/test-fixtures/kotlin-brace-matching.txt` + `ClassBodyScannerAgreementTest`/`KotlinTextFixtureTest` + `RunnableTestFixtureTest` держат две реализации в согласии |
| `RunnableTestMember.kt` | «Должен ли JUnit исполнить этот класс?» | `ClassBodyScannerAgreementTest`, `RunnableTestFixtureTest`, `TestTagCoverageTest` | Да — `scripts/src/scanners/test_classes.py::has_runnable_test`. Та же схема: общая таблица `config/test-fixtures/runnable-test-members.txt` |
| `SkillTables.kt` | Чтение таблиц SKILL.md (text-field shape, rewrite contract) | `TextFieldRewriteContractTest`, `TextFieldShapeConventionTest` | Нет — `check-skill-drift.py` проверяет экспортируемую поверхность тула, а не эти таблицы |
| `SourceScan.kt` | Обход дерева исходников commonMain | `CrossProfileReadRegistryTest`, `DroppedResultIsReportedTest`, `ScopedReadQueryIsolationTest`, `SyncedWriteEnqueuesTest` | Нет |
| `TextFieldShapeScan.kt` | Вывод формы текстового поля из импортов | `TextFieldRewriteContractTest`, `TextFieldShapeConventionTest` | Нет |

Вывод по хелперам: **дублирования с Python-сканерами нет** в смысле «одно правило написано дважды». Дублирование есть на уровне алгоритма (`source_text.py` и `KotlinText.kt` решают одну задачу — лексинг Kotlin), и оно **сознательное и закреплённое**: два языка, общая таблица фикстур, отдельный тест на согласие (`ClassBodyScannerAgreementTest` — 5 позитивных контролей, «every_shared_fixture_matches_its_agreed_verdict»). Это ровно тот случай, когда дублирование оправдано: оно не может разъехаться молча, потому что расхождение ломает сборку.

## Findings

### F1. `ArchitectureTest`: правило «repository read methods are user-scoped» не может упасть — оно читает пустой корпус

`ArchitectureTest.kt:573-593` ищет `class \w+RepositoryImpl` в файлах, а потом через
`queryFunctions(file)` (`ArchitectureTest.kt:89-107`) вытаскивает из **того же файла**
аннотации `@Query`. Но `@Query` живёт в DAO-интерфейсах, а не в `*RepositoryImpl`.

Проверка на корпусе: 24 файла содержат `class \w+RepositoryImpl`
(`shared/src/commonMain/kotlin/**`); 0 из них содержат хоть одну `@Query`,
соответствующую паттерну `queryFunctions`. Итого правило всегда строит пустой
список кандидатов и всегда зелёное.

```
$ python3 - <<'EOF'   # выборка: файлы, проходящие фильтр правила
files matching 'class \w+RepositoryImpl': 24
of which contain @Query matches: 0
total queries found: 0
EOF
```

Это ровно тот класс дефекта, который `script-tooling-audit.md` назвал «заявление о
корпусе уже самого заявления»: гейт заявляет «каждое чтение из репозитория
скоуплено по userId», а читает ноль чтений. Allowlist `UNSCOPED_READ_ALLOWLIST`
(`ArchitectureTest.kt:603`) при этом содержит три живых метода
(`TaskDao.getDependencyIdsForTask`, `getDependencyIdsForUser`,
`getBlockingTaskIdsForTask` — все три существуют в `Daos.kt:267-280`), то есть
allowlist у правила есть, а самого правила нет.

**Предложение.** Либо перенаправить правило на DAO-файлы (и тогда оно станет
дубликатом `ScopedReadQueryIsolationTest`, см. F3), либо удалить его вместе с
`UNSCOPED_READ_ALLOWLIST` и передать read-скоупинг `ScopedReadQueryIsolationTest`,
который работает. Правило не должно оставаться в виде «проверка, которая ничего не
проверяет».

### F2. `ArchitectureTest`: правило nav3-backStack не может упасть — целевой файл вне scope Konsist

`ArchitectureTest.kt:632-635` ищет файл `feature/nav/JvmNavEntries.kt` через
`scope.files`, где `scope` — это
`Konsist.scopeFromExternalDirectory(commonMainRoot)`
(`ArchitectureTest.kt:39`), а `commonMainRoot` указывает на
`shared/src/commonMain/kotlin` (`shared/build.gradle.kts:508-510`).

`JvmNavEntries.kt` лежит в **jvmMain**:

```
$ find shared/src -name JvmNavEntries.kt -not -path "*/build/*"
./shared/src/jvmMain/kotlin/com/singularity/todo/feature/nav/JvmNavEntries.kt
```

Файл вне scope → `scope.files.find { … }` возвращает `null` → `?: return` на
`ArchitectureTest.kt:635` → тест зелёный всегда. Правило, которое при этом
должно было бы работать, содержит 9 `entry<AppDestination.X> { route ->` блоков в
целевом файле — то есть проверять было что.

**Предложение.** Расширить scope правила до `jvmMain` (отдельный
`scopeFromExternalDirectory` для jvm-корня) либо перенести правило в отдельный
файл с собственным scope. Текущий `?: return` — это «правило не применилось»,
поданное как «правило выполнено».

### F3. Два write-гейта с разными корпусами: `ArchitectureTest` (79 мутаций) против `ScopedWriteQueryIsolationTest` (103)

Оба правила проверяют одну и ту же вещь — «каждый `UPDATE`/`DELETE` обязан
скоупиться по пользователю» — но разными механизмами и разными корпусами.

`ArchitectureTest.kt:96-98` использует паттерн
`@Query\(\s*((?:"(?:[^"\\]|\\.)*"\s*)+)\)\s*\n\s*(?:suspend\s+)?fun\s+(\w+)` —
литерал в двойных кавычках, склеенный только пробелами.

`ScopedWriteQueryIsolationTest.kt:105` использует
`@Query\(\s*((?:"(?:[^"\\]|\\.)*"\s*\+\s*)*"(?:[^"\\]|\\.)*")\s*,?\s*\)` —
допускает конкатенацию через `+` и запятую.

Измерение по корпусу (`shared/src/commonMain/kotlin/**`):

| Метрика | Значение |
|---|---|
| Всего аннотаций `@Query` | 323 |
| Из них в raw-строке (`"""…"""`) | 52 |
| Видит `ArchitectureTest` | 220 |
| Видит `ScopedWriteQueryIsolationTest` | 271 |
| Видит объединение | 271 |
| Видят оба | 220 |
| **Не видит никто** | **52** (все raw-строки) |

Мутаций (`UPDATE`/`DELETE`): `ArchitectureTest` видит 79, `ScopedWrite` — 103.
Разница в 24 мутации — это запросы, написанные конкатенацией литералов или
через raw-строку; `ScopedWrite` их видит, `ArchitectureTest` — нет.

Из 24 «видных только `ScopedWrite`» мутаций ровно одна не имеет колонки
скоупинга (`UPDATE sync_outbox SET attempts = … WHERE patch_id = :patchId`) — и
она покрыта `ALLOWED_UNSCOPED` в `ScopedWriteQueryIsolationTest`. То есть **сегодня
лейз-окна нет**, но корпус у `ArchitectureTest` уже сейчас на четверть меньше, и
ни один из двух гейтов не видит raw-строковые запросы.

**Предложение.** Оставить один write-гейт (`ScopedWriteQueryIsolationTest` — у него
корпус больше, есть allowlist, есть проверка «allowlist-запись всё ещё присутствует»
(`every_allowlist_entry_is_still_present`) и 12 позитивных контролей) и удалить
дублирующую ветку из `ArchitectureTest`. Отдельно: оба гейта должны понимать
raw-строки, иначе 19 мутаций из 52 raw-запросов не проверяются никем.

### F4. `MaestroFlowTagsTest` и `scripts/ci/validate-maestro-selectors.py` — одно правило, два корпуса, два вердикта

Оба проверяют «каждый `id:` в Maestro-flow резолвится в каталог тестовых тегов».
Kotlin-тест сам называет себя «the blocking JVM equivalent of the shell-script check
in `scripts/ci/validate-maestro-selectors.py`» (`MaestroFlowTagsTest.kt:22`).

Стороны расходятся:

| | `MaestroFlowTagsTest.kt` | `scripts/ci/validate-maestro-selectors.py` |
|---|---|---|
| Корпус | `Maestro/flows` **+ `Maestro/helpers`** (`MaestroFlowTagsTest.kt:112`) | только `Maestro/flows`, `helpers` пропускаются явно (`validate-maestro-selectors.py:409`) |
| Каталог | `TestTagsCatalog` — Kotlin-объект, парсит `TestTags.kt` | `_parse_testtags_kt()` — Python-регекс по `TestTags.kt` + `DYNAMIC_EXPANSIONS` + `ALLOWLIST` (`validate-maestro-selectors.py:400`) |
| Шаблонные id | пропускаются (`!id.startsWith("\${")`) | пропускаются (`TEMPLATE_VAR_PATTERN`) |
| Форма `calendar_day_*` | отдельный `calendarDayPattern` (`MaestroFlowTagsTest.kt:76`) | входит в `DYNAMIC_EXPANSIONS` |
| Форма `profile_item_*` | отдельный `profileItemPattern` (`MaestroFlowTagsTest.kt:85`) | **нет** — только `DYNAMIC_EXPANSIONS` |
| Статус в CI | `blocking` внутри `:shared:jvmTest` | `blocking` как `maestro selectors valid` в `static-gates.sh:377` |

Измерение: Kotlin видит 221 flow-id, Python видит те же 221 (расхождение по
множествам — ноль в обе стороны) **плюс** 5 helper-id, которых Python не видит
вообще (`auth_continue_offline_button`, `nav_tab_inbox`, `nav_tab_today`,
`settings_tab_account`, `task_item_buy_milk`).

То есть это не «дубль, который разошёлся» — это **два дуба с разными корпусами**:
Kotlin-гейт держит helper-ids, Python-гейт их не видит; Python-гейт держит
`DYNAMIC_EXPANSIONS`/`ALLOWLIST` как отдельный список, Kotlin-гейт держит два
специальных regex'а. Правило «id резолвится» сегодня у обоих зелёное, но
гарантии у них разные, и ни одна из них не покрывает корпус другого.

**Предложение.** Оставить один источник истины. Практичный вариант — сделать
Python-гейт читать `TestTagsCatalog` (уже есть `TestTagsCatalog` в
`shared/src/jvmTest/.../core/ui/TestTagsCatalog.kt`, он парсит тот же `TestTags.kt`
тем же regex'ом) и включить `helpers` в оба корпуса; тогда `maestro selectors valid`
и `MaestroFlowTagsTest` станут одним правилом с одним корпусом. Второй вариант —
оставить только Python-гейт (он уже в `static-gates.sh`, то есть виден реестру)
и удалить Kotlin-тест.

### F5. Четыре собственных «stripper'а» комментариев в Kotlin-гейтах, при том что в пакете уже есть `KotlinText`

В `arch/` лежит собственный лексер `KotlinText.kt` с корректной обработкой
вложенных комментариев, raw-строк и шаблонов. Но четыре гейта объявляют свои
приватные версии той же операции:

| Файл:строка | Код | Что делает |
|---|---|---|
| `ArchitectureTest.kt:77` | `text.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "")` | не вложенные; `//` внутри строки съедает остаток строки |
| `DiFacadeTest.kt:88` | то же | то же |
| `EntityMapperCompletenessTest.kt:49` | `val blockComment = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL)` | то же |
| `SyncPeriodicTriggerWiringTest.kt:131` | `Regex("""/\*.*?\*/""", DOT_MATCHES_ALL).replace(source, "")` | то же |

Дополнительно `PlatformSeamGuardTest.kt:150-160` и `SyncPeriodicTriggerWiringTest.kt:131`
реализуют собственную «strip comments and strings» — то есть `stripCode` дублируется
ещё раз, мимо `KotlinText.stripCode`.

Измерение расхождения на корпусе `shared/src/commonMain/kotlin/**` (эталон —
`KotlinText.stripComments`, то есть вложенные комментарии и raw-строки):

```
DAO-token divergence (stripComments vs weak regex): 1 файл
  feature/reminders/data/CrossProfileReadRegistry.kt
mutation-call divergence: 0 файлов
```

Расхождение сегодня малое — но оно не нулевое, и оно **структурное**: слабый
regex не понимает вложенность. В корпусе есть 4 файла с вложенными блочными
комментариями (`feature/nav/TabReselect.kt:9`,
`feature/tasks/presentation/components/detail/FirstRunSection.kt:28`,
`feature/calendar_sync/data/google/GoogleEventJson.kt:15`,
`core/ui/components/ConfirmActionDialog.kt:12`), и 41 строка-литерал с `//`
внутри (`"task://$taskId"`, `"https://api.openai.com/v1"` и т.п.), где `//`
съедает остаток строки кода.

**Предложение.** Заменить четыре приватных реализации на `KotlinText.stripComments`
и `KotlinText.stripCode`. Это ровно тот случай, где «механизм, существующий
только чтобы нести правило», должен уйти в библиотеку — библиотека здесь уже
рядом, в том же пакете, и она умеет вложенные комментарии.

### F6. `ScopedReadQueryIsolationTest` читает 62 SELECT'а из 103 — остальные не видит

Правило читает `Daos.kt` (`ScopedReadQueryIsolationTest.kt`, функция `daoSource()`)
и извлекает SELECT'ы построчным regex'ом `@Query\("([^"]+)"\)`. Этот regex ловит
только однострочные запросы в двойных кавычках.

Измерение по `Daos.kt`:

| Метрика | Значение |
|---|---|
| Всего SELECT'ов в `Daos.kt` | 103 |
| Из них в raw-строке (`"""…"""`) | 21 |
| Видит правило | **62** |
| Не видит | **41** |

По всему репозиторию: 198 SELECT'ов, из них правило видит 62 — и только потому,
что читает один файл. Из 41 невидимого SELECT'а на профиле-владельческих
таблицах **ни одного нескоупленного** нет (проверено: `unscoped SELECTs invisible
to the read gate: 0`), то есть лейз-окна сегодня нет. Но за пределами `Daos.kt`
лежат ещё 95 SELECT'ов (в `OwnerEraseDao.kt`, `OwnerReassignDao.kt`,
`AttachmentDao.kt`, `TimeEntryDao.kt`, `ProposalDao.kt` и др.), которые правило
не читает вообще; среди них есть один нескоупленный SELECT по профиле-владельческой
таблице — `TimeEntryDao.kt`: `SELECT * FROM time_entries WHERE task_id = :taskId AND
deleted_at IS NULL`, где `time_entries` объявлена с `user_id`
(`core/database/Entities.kt:368-380`). Он корректен только потому, что `task_id`
уже проскоуплен вызывающим; правило этого не проверяет и не увидит.

**Предложение.** Расширить корпус на все DAO-файлы (переиспользовать
`SourceScan.kt`, который уже умеет обходить дерево) и перейти на
`KotlinText`-based извлечение `@Query`, покрывающее raw-строки и многострочные
запросы — тем же механизмом, что и `ScopedWriteQueryIsolationTest`.

### F7. `CrossUserWriteRegistryTest` ссылается на несуществующий файл бэклога

`CrossUserWriteRegistryTest.kt:50` в тексте ошибки указывает: «record it in
`docs/decisions/deferred-backlog.md`». Такого файла нет:

```
$ ls docs/decisions/deferred-backlog.md
ls: cannot access 'docs/decisions/deferred-backlog.md': No such file or directory
```

Реальный файл — `docs/decisions/2026-09-24-deferred-backlog.md`. Это единственный
мёртвый path- ref среди всех 48 файлов `arch/` (проверено регуляркой по
`docs|shared|scripts|config|…/…\.kt|py|sh|md` без требования backticks).

**Предложение.** Поправить путь на `docs/decisions/2026-09-24-deferred-backlog.md`.
Мелочь, но гейт, который говорит человеку «запиши решение туда», должен указывать
на существующее место.

### F8. `ViewModelTestCoverageTest`: allowlist из 8 ViewModel'ов без записи в бэклоге

`ViewModelTestCoverageTest.kt` требует (в тексте ошибки) добавить ViewModel и в
`KNOWN_UNCOVERED`, и в `docs/decisions/deferred/<slug>.md`. Каталога
`docs/decisions/deferred/` не существует, а ни один из восьми
(`AccountSettingsViewModel`, `AiUsageViewModel`, `AppVersionGateViewModel`,
`ArchiveViewModel`, `AttachmentsViewModel`, `AuthViewModel`, `CalendarSyncViewModel`,
`ProfileSwitcherViewModel`) не упоминается ни в одном файле `docs/decisions/`.
Фактически записи бэклога нет — есть только список в тесте.

**Предложение.** Либо создать `docs/decisions/deferred/` и внести восемь записей,
либо переформулировать требование так, чтобы оно соответствовало тому, что
реально проверяется (список с причиной в самом тесте). Правило «список растёт без
решения» уже реализовано (`the sanctioned list has not grown without a deliberate
decision` у `CrossProfileReadRegistryTest`) — для `KNOWN_UNCOVERED` аналогичной
проверки нет.

### F9. `CapabilityFlagTest` — правило без позитивного контроля и без ADR

`CapabilityFlagTest.kt` требует, чтобы у флага-константы была причина «на месте».
Ни позитивного контроля (нет примера, который обязан сработать), ни ссылки на
решение в `docs/decisions/` (единственный файл без ADR среди тех, где правило
сформулировано как «флагаж разрешён только если…»). Проверка целиком опирается на
то, что комментарий есть, — но не проверяет, что отсутствие причины ловится.

**Предложение.** Добавить позитивный контроль (синтетический флаг без пояснения
обязан падать) и ссылку на ADR.

### F10. Правило «commonMain does not use JVM file APIs» дублирует `CommonMainJvmApiTest` — и оба не видят `kotlin.io.path`

`ArchitectureTest.kt:462-491` запрещает в `commonMain` `java.io.File`,
`java.nio.file.*`, `kotlin.io.path.*` и ещё четыре точных класса.
`CommonMainJvmApiTest.kt:120` запрещает в `commonMain` **любую** ссылку на
`java.*` / `javax.*` / `android.*` (regex `(?<![A-Za-z0-9_])(java|javax|android)\.[A-Za-z_]\w*`,
`CommonMainJvmApiTest.kt:234`) — с пустым baseline.

Второе правило строго сильнее первого по всем перечисленным префиксам: `java.io.File`
попадает под `java.*`, `java.nio.file.*` — тоже. Единственное, что даёт первое
правило сверх второго, — `kotlin.io.path.*`, потому что `kotlin` не входит в
перечень. Но `kotlin.io.path` без `java.` не подпадает и под `kotlin.io.path.*`? —
подпадает, но это единственное исключение.

Сейчас в `commonMain` нет ни одного `import kotlin.io.path` и ни одного
`import java.io.File` — то есть оба правила проверяют пустой нарушительный класс.
Allowlist `FILE_API_ALLOWLIST = setOf("feature/ai/tools/AdrTools.kt")`
(`ArchitectureTest.kt:197`) существует, хотя `AdrTools.kt` больше не импортирует
файловых API: grep по `import java.io.File` в нём даёт пусто.

**Предложение.** Удалить более узкое правило из `ArchitectureTest` и оставить
`CommonMainJvmApiTest`, расширив его на `kotlin.io.path.*`; либо, если различие
осознанное, зафиксировать его в ADR и убрать allowlist-запись, ставшую ненужной.

### F11. 22 из 48 файлов не ссылаются ни на одно решение в `docs/decisions/`

Полная карта ссылок по 48 файлам (по дате-слегу `YYYY-MM-DD-<slug>`):

Ссылаются: `ArchitectureTest` (5), `AtBackendConfinedTest` (2), `CachedIdentityReadArchitectureTest` (1),
`ClassBodyScannerAgreementTest` (1), `CrashReportingWiringTest` (2), `DiFacadeTest` (1),
`EntityMapperCompletenessTest` (2), `SharedHarnessConventionsTest` (1), `SubprocessConfinedTest` (1),
`SyncEngineTakesNoFeatureTypesTest` (1), `SyncWriteIsAtomicTest` (1), `TestTagCoverageTest` (1),
`TestTagsWiringTest` (3), `UiAutomationSelectorTest` (1), `ViewModelInitOrderTest` (1),
`ViewModelTestCoverageTest` (1 — но это `docs/plans/2026-10-04-mr5-retro-gate.md`, а не решение).

Не ссылаются (из 43 тестов): `CapabilityFlagTest`, `CommonMainJvmApiTest`,
`CrossProfileReadRegistryTest`, `CrossUserWriteRegistryTest`,
`DetektSourceSetsAreAllScannedTest`, `DroppedResultIsReportedTest`,
`EnqueueSiteIsScannedTest`, `ExtensionServiceRegistrationTest`, `KotlinTextFixtureTest`,
`KotlinTextTest`, `MaestroFlowTagsTest`, `PlaintextTokenIsolationTest`,
`PlatformClaimWiringTest`, `PlatformSeamGuardTest`, `RunnableTestFixtureTest`,
`ScopedReadQueryIsolationTest`, `ScopedWriteQueryIsolationTest`,
`SelectorSerializerCoverageTest`, `SingleOfKotlinDefaultsTest`, `SyncedEntityDeleteIsSoftTest`,
`SyncedWriteEnqueuesTest`, `SyncPeriodicTriggerWiringTest`, `SyncTransportIdentityTest`,
`TextFieldRewriteContractTest`, `TextFieldShapeConventionTest`, `UnannotatedTestMemberTest`,
`VendorSdkConfinementTest`, `EntityMapperCompletenessTest` (ссылается, но не на write-слой).

Аналогия с R5.4 из `script-tooling-audit.md`: там отсутствие ADR-ссылки было
находкой, потому что правило без решения — это правило без владельца. Здесь то же,
но слабее: многие из этих правил — «конвенция, а не решение» (например
`PlatformClaimWiringTest` проверяет, что файл лежит там, где говорит его KDoc), и
требовать ADR для каждого было бы насилием. Но по правилам, которые **записывают
исключение** (`ScopedWriteQueryIsolationTest.ALLOWED_UNSCOPED`,
`ArchitectureTest.GLOBAL_DAO_MUTATION_ALLOWLIST`, `CrossProfileReadRegistry`),
ADR-ссылка обязательна — это прямо написано в тексте самих правил
(`ScopedWriteQueryIsolationTest.kt:120`, `ArchitectureTest.kt:535`,
`ArchitectureTest.kt:215`), но не обеспечено проверкой.

Все упомянутые слаги существуют — «висячих» ссылок на несуществующие ADR в
`arch/` нет, кроме `2026-10-04-mr5-retro-gate` (это `docs/plans/`, файл есть) и
`2026-10-06-notification-port-deleted-because-it-cancelled-` в
`AtBackendConfinedTest.kt:14,88` — обрывок слага из-за переноса строки; полное имя
`2026-10-06-notification-port-deleted-because-it-cancelled-other-peoples-jobs`
существует, но именно в таком виде слаг не резолвится.

**Предложение.** Ввести правило: allowlist, который допускает исключение из
правила изоляции, обязан ссылаться на ADR, и проверять это автоматически (например,
расширив `scripts/check-adr-references.py` на Kotlin-источники — сейчас его
`SCAN_GLOBS` покрывают только `docs/**`, `.agents/skills/**`, `openspec/**`,
`config/**`, а `shared/src/**/*.kt` не сканируются вовсе).

### The findings, as issues

Every finding above is filed, so a reader of this document can follow the work
without reading this document:

| Finding | Issue | Shape of the work |
|---|---|---|
| F1 | [#788](https://github.com/gazon1/sing/issues/788) | the user-scope read rule reads an empty corpus |
| F2 | [#789](https://github.com/gazon1/sing/issues/789) | the nav3 rule cannot fail — its file is outside the scope |
| F3 | [#790](https://github.com/gazon1/sing/issues/790) | two write gates, two corpora, 52 raw queries unseen |
| F4 | [#791](https://github.com/gazon1/sing/issues/791) | the Maestro selector rule exists twice, with two catalogues |
| F5 | [#792](https://github.com/gazon1/sing/issues/792) | four private comment strippers next to `KotlinText` |
| F6 | [#793](https://github.com/gazon1/sing/issues/793) | the read-isolation gate sees 62 of 103 SELECTs |
| F7 | [#794](https://github.com/gazon1/sing/issues/794) | a message points at a backlog file that does not exist |
| F8 | [#795](https://github.com/gazon1/sing/issues/795) | 8 exempted ViewModels with no record of why |
| F9 | [#796](https://github.com/gazon1/sing/issues/796) | a capability-flag rule with no positive control |
| F10 | [#797](https://github.com/gazon1/sing/issues/797) | two rules for JVM file APIs, both reading nothing |
| F11 | [#798](https://github.com/gazon1/sing/issues/798) | 22 of 48 files cite no decision, and nothing checks |


## Duplication map

Двусторонняя карта. «A ↔ B» означает, что правило выражено в обоих местах.

### Kotlin ↔ Kotlin

| Правило | Место A | Место B | Статус |
|---|---|---|---|
| DAO-мутация скоуплена по user | `ArchitectureTest.kt:519-537` (`DAO mutations are ownership-scoped`) | `ScopedWriteQueryIsolationTest.kt` (`every_update_and_delete_query_scopes_itself_to_a_user`) | **Дубль.** Корпус A — 79 мутаций, B — 103. A не видит raw-строки (F3) |
| Чтение из репозитория скоуплено по user | `ArchitectureTest.kt:573-593` | `ScopedReadQueryIsolationTest.kt` | **Мёртвый дубль.** A не видит ни одного чтения (F1); B работает, но на 62 из 103 SELECT'ов (F6) |
| JVM-API в `commonMain` | `ArchitectureTest.kt:462-491` | `CommonMainJvmApiTest.kt:120` | **Дубль, второй строго сильнее.** A даёт только `kotlin.io.path.*` сверх B (F10) |
| Kotlin-лексер (комментарии, строки, скобки) | `KotlinText.kt` | `scripts/src/scanners/source_text.py` | **Дубль по алгоритму, но не по правилу.** Скреплён общей таблицей фикстур + `ClassBodyScannerAgreementTest` |
| «Класс исполняем JUnit?» | `RunnableTestMember.kt` | `scripts/src/scanners/test_classes.py::has_runnable_test` | **То же.** Скреплено `config/test-fixtures/runnable-test-members.txt` + `RunnableTestFixtureTest` |
| Комментарии/строки (приватные копии) | `ArchitectureTest.kt:77`, `DiFacadeTest.kt:88`, `EntityMapperCompletenessTest.kt:49`, `SyncPeriodicTriggerWiringTest.kt:131` | `KotlinText.stripComments` / `stripCode` | **Дубль внутри пакета, без скрепления.** (F5) |
| Maestro-id резолвится | `MaestroFlowTagsTest.kt:111` | `scripts/ci/validate-maestro-selectors.py:400` | **Дубль с разными корпусами.** (F4) |

### Kotlin ↔ Python (остальное)

| Правило | Место A | Место B | Статус |
|---|---|---|---|
| Профиль-изоляция записи | `ScopedWriteQueryIsolationTest.kt` | `scripts/check-sync-allowlist-regenerated.py` | **Не дубль.** Python проверяет, что сид allowlist'а совпадает с контрактом; Kotlin — что каждый `UPDATE`/`DELETE` скоуплен. Разные вопросы |
| Неиспользуемые поверхности | `PlatformSeamGuardTest.kt` | `scripts/find-unwired-surfaces.py` | **Не дубль, но пересекаются.** Python ищет orphan-биндинги вообще; Kotlin — что каждый зарегистрированный seam реально связан на обеих платформах. Python-гейт про Kotlin-классы seam'ов не знает (`find-unwired-surfaces.py` упоминает seam только в комментариях) |
| Теги `@Tag` присутствуют | `TestTagCoverageTest.kt` | `scripts/check-test-runs.py` | **Не дубль.** Python считает, сколько тестов выполнилось; Kotlin требует, чтобы каждый класс был помечен и каждое значение выбиралось CI |
| Конвенции скриптов | `SharedHarnessConventionsTest.kt` | `scripts/check-script-conventions.py` | **Не дубль.** Python — по `scripts/**`; Kotlin — по `shared/src/{jvmTest,commonTest}`. Разные корпуса |
| Скилл-дрейф | `SkillTables.kt` + `TextFieldShapeConventionTest.kt` | `scripts/check-skill-drift.py` | **Не дубль.** Python проверяет экспортируемую поверхность тула против ADR; Kotlin читает две конкретные таблицы SKILL.md |
| ADR-ссылки | 22 файла без ссылки (F11) | `scripts/check-adr-references.py` | **Дыра в покрытии.** Python сканирует `docs/**`, `.agents/skills/**`, `openspec/**`, `config/**` — Kotlin-исходники не сканируются |
| Мёртвые ссылки в исходниках | `CrossUserWriteRegistryTest.kt:50` (F7) | `scripts/check-doc-dead-refs.py` | **Дыра в покрытии.** `PATH_RE` требует backticks (`scripts/check-doc-dead-refs.py:176-178`), а путь в Kotlin лежит в строке сообщения об ошибке, без backticks |

### Konsist ↔ ручные правила

ADR `docs/decisions/2026-09-26-konsist-architecture-tests.md` закрепил Konsist как
слой автоматических границ. Проверено: `ArchitectureTest.kt` — единственный файл в
корпусе, использующий Konsist API (`Konsist.scopeFromExternalDirectory`,
`scope.files`, `importFqns()`), и он содержит 20 правил, из которых **два
реализованы не через Konsist, а через собственный regex** (`:96-98` — паттерн
`@Query`, `:475-483` — импортный FQN-фильтр). `TextFieldShapeScan.kt` — второй
потребитель Konsist в корпусе, и он использует его правильно: форму поля выводит
из списка импортов, а не из текста.

То есть дублирования «Konsist vs ручной regex внутри одного правила» нет: Konsist
использован как обход дерева и источник списка файлов/импортов, а не как
парсер SQL или Kotlin-выражений. Это соответствует ADR. Дублирование, которое
есть, — между двумя **ручными** правилами внутри `ArchitectureTest` и между
`ArchitectureTest` и `ScopedWriteQueryIsolationTest` (F3).

## Sizing the remaining work

| Находка | Что осталось | Размер | Риск |
|---|---|---|---|
| F1 | Перенаправить или удалить правило `repository read methods are user-scoped`; вычистить `UNSCOPED_READ_ALLOWLIST` | M — правка одного правила + удаление allowlist | **Высокий.** Правило зелёное всегда; при удалении `ScopedReadQueryIsolationTest` должен уметь покрыть то, что правило заявляло |
| F2 | Дать правилу nav3-backStack доступ к `jvmMain` | S — второй scope + перенос правила | **Средний.** Текущий `?: return` скрывает 9 `entry`-блоков; после включения правило может потребовать правок кода |
| F3 | Удалить дублирующую ветку write-правила из `ArchitectureTest`; научить оба гейта raw-строкам | M — удаление ~20 строк + расширение регекса + пересчёт allowlist'а | **Высокий.** 52 raw-запроса (19 мутаций) не проверяются никем; после расширения корпуса могут всплыть реальные нарушения |
| F4 | Свести `MaestroFlowTagsTest` и `validate-maestro-selectors.py` к одному правилу с одним корпусом | M — унификация каталога (`TestTagsCatalog` ↔ `_parse_testtags_kt()`), включение `helpers` | **Средний.** Два зелёных гейта сегодня проверяют разные корпуса; объединение выявит 5 helper-id, которых Python не видит |
| F5 | Заменить 4 приватных stripper'а на `KotlinText.stripComments` / `stripCode` | S — механическая замена в 4 файлах | **Низкий.** Расхождение сегодня — 1 файл; но вложенные комментарии в 4 файлах и `//` в 41 строке-литерале означают, что расхождение может вырасти |
| F6 | Расширить `ScopedReadQueryIsolationTest` на все DAO-файлы и на raw-строки | M — замена построчного regex'а на `KotlinText`-based, обход дерева через `SourceScan.kt` | **Средний.** 41 SELECT' в `Daos.kt` и 95 за его пределами не проверяются; среди них — `TimeEntryDao` без user-скоупа |
| F7 | Поправить путь в сообщении об ошибке | XS — одна строка | **Низкий.** Но это инструкция человеку, которая уводит в несуществующий файл |
| F8 | Завести `docs/decisions/deferred/` и внести 8 записей, либо переформулировать требование | S — 8 файлов или правка текста правила | **Низкий.** Правило «список без записи» не обеспечено |
| F9 | Добавить позитивный контроль и ADR-ссылку в `CapabilityFlagTest` | S — один синтетический пример + ссылка | **Низкий** |
| F10 | Удалить узкое правило из `ArchitectureTest`, расширить `CommonMainJvmApiTest` на `kotlin.io.path.*`, вычистить `FILE_API_ALLOWLIST` | S — правка правила + расширение регекса | **Низкий.** Оба правила сегодня проверяют пустое множество нарушителей |
| F11 | Расширить `check-adr-references.py` на `shared/src/**/*.kt`; потребовать ADR-ссылку у правил с allowlist'ами | M — расширение `SCAN_GLOBS` + проверка в самих гейтах | **Средний.** Без этого allowlist'ы правил изоляции растут без записи в решениях |

Суммарно: три находки требуют изменения корпуса продукта (F2, F3, F6 — после
расширения корпусов могут открыться реальные нарушения), остальные —
правки самих гейтов.

## What was measured and rejected

**«`KotlinText.kt` дублирует `source_text.py`» — отклонено как дефект.**
Функционально они решают одну задачу, и это ровно та дубликация, которую
`script-tooling-audit.md` квалифицировала бы как находку. Здесь — нет, потому что
дублирование **скреплено контрактом**: `config/test-fixtures/kotlin-brace-matching.txt`
(12 записей) — единственное определение ответа, `KotlinTextFixtureTest` (16 тестов)
доказывает, что Kotlin-сторона с ним согласна, `scripts/tests/test_kotlin_brace_matching.py`
делает то же для Python-стороны, а `ClassBodyScannerAgreementTest` (5 позитивных
контролей) сравнивает обе реализации на **каждом реальном тест-классе** репозитория.
Расхождение ломает сборку, а не «медленно протухает». Два языка, одна таблица,
один тест на согласие — это не дефект, это способ сделать дублирование честным.

**«`TestTagCoverageTest` дублирует `check-test-runs.py`» — отклонено.**
`check-test-runs.py` отвечает на вопрос «сколько тестов выполнилось» (и ловит
прогон, который не случился, через `--since` и `--gradle-log`). `TestTagCoverageTest`
отвечает на вопрос «каждый ли класс помечен и каждое ли значение тега выбирается
CI». Разные вопросы к разным корпусам: Python смотрит на JUnit-XML, Kotlin — на
исходники тестов. `check-test-runs.py` в своей документации
(`scripts/check-test-runs.py:263`) прямо ссылается на `TestTagCoverageTest` как на
смежную, но не дублирующую проверку.

**«`PlatformSeamGuardTest` дублирует `find-unwired-surfaces.py`» — отклонено.**
Python-гейт ищет orphan-биндинги вообще, по всему DI-графу; Kotlin-гейт проверяет,
что каждый зарегистрированный seam реально связан на **обеих** платформах и что
его классификация (`real` / `stub` / `exempt`) всё ещё истинна. Python-инструментарий
о seam'ах не знает: `grep -n "seam" scripts/find-unwired-surfaces.py` даёт три
попадания, все — в комментариях. Это взаимодополняющие правила с разными корпусами.

**«`SharedHarnessConventionsTest` дублирует `check-script-conventions.py`» — отклонено.**
Первый — по `shared/src/{jvmTest,commonTest}/**`, второй — по `scripts/**`.
Пересечения корпусов нет.

**«`SkillTables.kt` дублирует `check-skill-drift.py`» — отклонено.**
Python проверяет, что экспортируемая поверхность тула совпадает с ADR
(`scripts/check-skill-drift.py:257`). Kotlin читает две конкретные таблицы SKILL.md —
shape-классификацию текстовых полей и контракты перезаписи. Разные вопросы.

**«Вложенные комментарии ломают `ArchitectureTest`» — отклонено как находка.**
Проверено: `ArchitectureTest` использует `scope.files` + Konsist-парсер, а не свой
regex, для правил на слои; собственный regex используется в двух местах, и
измерение показало расхождение ровно в 1 файле
(`feature/reminders/data/CrossProfileReadRegistry.kt`) по DAO-токенам и 0 файлов по
mutation-вызовам. Проблема реальна (F5), но её масштаб — «четыре приватных
stripper'а вместо одного `KotlinText`», а не «правила не работают».

**«`ScopedWriteQueryIsolationTest` пропускает реальные нарушения» — отклонено.**
Проверено на корпусе: 52 невидимых raw-запроса, из них 19 мутаций, и **ни одна**
не лишена колонки скоупинга (`raw-string mutating queries WITHOUT scoping column: 0`).
Также 24 мутации, видимые только `ScopedWrite`, дают ровно одну нескоупленную
(`sync_outbox`), и она в `ALLOWED_UNSCOPED`. Лейз-окна нет; проблема — в корпусе
и в дублировании, а не в пропущенном нарушении.

**«`ScopedReadQueryIsolationTest` пропускает реальное нарушение» — отклонено как
«нарушение», подтверждено как «пробел в корпусе».** Среди 41 невидимого SELECT'а
нескоупленных нет (0). Единственный нескоупленный SELECT на профиле-владельческой
таблице вне корпуса гейта (`TimeEntryDao`) корректен по смыслу (`task_id` скоуплен
вызывающим). Это F6 как пробел покрытия, а не как баг.

**«`ArchitectureTest` дублирует Konsist» — отклонено.**
ADR `2026-09-26-konsist-architecture-tests.md` закрепил Konsist; в коде он
использован как обход дерева и источник импортов, а не как парсер SQL или
Kotlin-выражений. `TextFieldShapeScan.kt` — второй Konsist-потребитель, и он
использован по назначению (форма выводится из импортов, а не из текста). Дублирования
«Konsist vs ручной regex в одном правиле» не найдено.

**«`SharedHarnessConventionsTest` запрещает `stateIn`/`viewModelScope`, а
`AGENTS.md` это уже запрещает» — отклонено.**
`AGENTS.md` — это инструкция агенту, а не исполняемое правило; `SharedHarnessConventionsTest`
переводит её в проверку, которая падает в CI. Это ровно та конструкция, которую
аудит Python-тулинга назвал «правило, существующее только здесь, остаётся».

## Evidence and limits

**Что измерено.**

- Корпус: 48 файлов, 43 `*Test.kt`, 5 хелперов, 12 937 строк — `ls`/`wc` по
  `shared/src/jvmTest/kotlin/com/singularity/todo/arch/`.
- 155 тестовых методов — `grep -c "@Test"` по каждому файлу.
- Полное извлечение `@Query` (323 штуки), классификация на plain-literal (271) и
  raw-строку (52), разбор обоих регулярных выражений — репликация паттернов
  `ArchitectureTest.kt:96-98` и `ScopedWriteQueryIsolationTest.kt:105` на
  `shared/src/commonMain/kotlin/**`, с проверкой пересечения множеств
  (`arch: 220, sw: 271, union: 271, intersection: 220`).
- Корпус правила `repository read methods are user-scoped`: 24 файла под фильтр
  `class \w+RepositoryImpl`, 0 аннотаций `@Query` внутри них.
- Файлы, которые персистент-сканнер видит: `scopeFromExternalDirectory(commonMainRoot)`
  (`ArchitectureTest.kt:39`) + `commonMain.root` = `src/commonMain/kotlin`
  (`shared/build.gradle.kts:508-510`) против фактического положения
  `JvmNavEntries.kt` в `jvmMain`.
- SELECT'ы: 198 по репозиторию, 103 в `Daos.kt`, 62 видны гейту; профиле-владельческие
  таблицы выведены из `Entities.kt` (10 таблиц).
- Расхождение «слабый regex vs `KotlinText.stripComments`»: 1 файл по DAO-токенам,
  0 по mutation-вызовам; 4 файла с вложенными блочными комментариями; 41 строковый
  литерал с `//` внутри.
- Maestro-корпусы: Kotlin видит 221 flow-id + 5 helper-id; Python — те же 221 flow-id,
  helpers пропускает (`validate-maestro-selectors.py:409`); множества совпадают
  полностью.
- ADR-ссылки: полный перечень 48 файлов; все упомянутые слаги существуют, кроме
  `2026-10-04-mr5-retro-gate` (это `docs/plans/`, файл существует) и обрывка
  `2026-10-06-notification-port-deleted-because-it-cancelled-`
  (`AtBackendConfinedTest.kt:14,88` — перенос строки).
- Мёртвые path-ссылки: 1 (`CrossUserWriteRegistryTest.kt:50`).
- Покрытие Python-инструментарием: `scripts/ci/static-gates.sh` — 30 gate-строк,
  ни одна не ссылается на `arch/`; `_registry.py` — ни одной записи; `config/docs/test-runs-baseline.txt`
  — одно число на весь `shared:jvmTest`.

**Чего я не мог измерить.**

- **Тесты не запускались.** Ни `:shared:jvmTest`, ни `check.sh`, ни `sgate run-all`.
  Все утверждения вида «правило зелёное везде» — статические, выведенные из
  исходников, а не наблюдаемые прогоны. Документ не заменяет прогон: он говорит,
  где прогон должен упасть, и не говорит, что он упал.
- **Время выполнения не измерялось.** Какие из 43 файлов дают реальный сигнал, а
  какие — мгновенный зелёный, по чтению исходников не видно.
- **Не проверено, что правила срабатывают при мутации.** «Правило может упасть» —
  это утверждение о структуре (тело достижимо, корпус непуст, утверждение не
  тавтологично), а не о наблюдённом падении. У Python-гейтов для этого есть
  саботаж-контроль (`GATE_CONTROLS`); у Kotlin-гейтов его нет, и это отдельная
  работа, а не пункт этого документа.
- **Полнота не доказана.** Перечень правил получен разбором всех 48 файлов; правило,
  выраженное не аннотацией `@Test`, а вызовом хелпера, в перечень не попало бы.
  Пересечение с Python-аудитом — не перекрёстная проверка: обе стороны смотрели на
  один и тот же корпус разными инструментами, но ни одна не проверяла другую.
- **Замеры сделаны на коммите `674d1c1e`.** Корпус меняется; числа вроде «103
  SELECT в `Daos.kt`» устаревают вместе с ним, и обновление файла — часть работы
  того, кто меняет корпус.

## Links

- `docs/engineering/script-tooling-audit.md` — the Python-side inventory; this
  document is its companion and covers the corpus the Python audit excluded.
- `docs/decisions/2026-10-19-ast-grep-for-kotlin-parsing-in-python-gates` — why
  the Kotlin side has its own lexer (`KotlinText.kt`) and the Python side its own
  (`scanners/source_text.py`), and why the two are not a duplication to remove.
