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
- **Кто каким worktree владеет и какой демон сколько живёт** — `scripts/gradle-daemons.sh`:
  читает `/proc`, печатает pid, возраст, версию и worktree. `ps -eo …args` даёт тот же
  ответ дампом в ~40 KB — его и не делай.
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

**Паттерны.** Канонический CRUD-паттерн (новой фичи) — `docs/architecture/crud-pattern.md`.
DI: Koin 4.x (pure DSL): формы DSL, KOIN-W003, где лежат биндинги — `docs/architecture/koin-di.md`.

## Инструменты: харнесс → rtk → сырая команда

**Бери самый узкий инструмент, который даёт ответ.** Сначала инструменты харнесса
(`grep`, `glob`, `read`) — их вывод уже отфильтрован. Дальше `rtk` (`/home/max/.local/bin/rtk`) —
он сжимает вывод. Сырая команда — последний шаг, когда нужен точный байт.

| Задача | Инструмент |
|---|---|
| найти в коде, найти файл, прочитать файл | `grep` / `glob` / `read` харнесса |
| список файлов каталога | `rtk ls` |
| история коммитов | `rtk git log` |
| прогон тестов | `rtk test ./gradlew :shared:jvmTest` |
| отчёт линтера (detekt, ktlint) | `rtk err <команда>` |
| сводка длинного вывода | `rtk summary <команда>` |

**Фоновая задача — один вызов с ожиданием:** `task_output(task_id, wait_ms=30000)` ждёт
до 30 секунд за вызов; без `wait_ms` результат возвращается мгновенно и раунд уходит впустую.

## Код выхода важен → вывод в файл

**Сведение вывода для чтения — норма:** `| head`, `| grep -c`, `| wc -l` сокращают объём и не ломают команду.
**Код выхода важен (`git push`, гейт, тест) — пиши вывод в файл:**

```bash
cmd > /tmp/probe/push.log 2>&1
rc=$?
tail -40 /tmp/probe/push.log
```

В пайпе код выхода — код последней команды, а `git push | tail` убивает push по SIGPIPE (141) — пуш не уходит, а выглядит успешным. Для пушей в репозитории есть обёртка `scripts/push.sh`: пишет лог в файл и печатает код выхода.

## Тесты

**Правила и рецепты тестов — `docs/architecture/tests.md`:** где живут тесты
(`commonTest` выполняется внутри `:shared:jvmTest`), fake вместо моков, теги `fast`/`slow`
и под-теги, VM-тесты через харнесс `testVm`, coroutine-тесты, диагностика падений
(`build/diagnostics/<TestClass>/coroutines.txt`), BAN list, пересборка каталога тегов
`Maestro/TAGS.md`. Иди туда, когда пишешь тест или разбираешь падение.

**Запуск.** `./gw :shared:jvmTest` — быстрый цикл (только `fast`);
`-Ptest.population=full` — полный набор, как в CI; `-Ptest.tags=slow:migration` — одна
категория медленных.

## expect/actual порты

Порт — интерфейс в `commonMain`, два actual, `single<Port>` в обоих
`PlatformModule.*.kt`. Единственный expect/actual seam — `platformModule()`.
Инвентарь портов, фабричные функции, время и NavGraphs — `docs/PLATFORM-REFERENCE.md`.

## Сборка

**Порядок проверки — `docs/architecture/verification.md`:** `./gw` вместо `./gradlew`
(приватный `GRADLE_USER_HOME` на worktree), карта команд (`just gate`, `./check.sh`,
`bash scripts/ci/static-gates.sh`, `just tests::impact`), порядок detekt
(`:shared:detekt` первым), компиляция сразу после связной правки, долгий запуск как одна
фоновая задача, гейтовый venv, загрузка хоста (`./sg host-load`), покрытие и floors,
«находки detekt — одним файлом». Иди туда перед запуском гейтов.

**Две вещи, нужные в каждом ходе.** `./gw` вместо `./gradlew`: делённый Gradle Home —
это общий лок кэша и общий реестр демонов, и сборка идёт в разы дольше.
`./gw :shared:compileKotlinJvm` (для тестов — `:shared:jvmTestClasses`) — сразу после
связной правки (сигнатура, интерфейс, DI-биндинг): полный прогон на сломанной
компиляции стоит сорока минут ожидания.

## CI/CD

**Что делает CI — `docs/architecture/ci.md`:** джобы `ci.yml` (`static`, `meta`, `tests`,
`android`, `ci-gate`), слои проверок, реестр гейтов как единственный источник
(`scripts/src/sgate/`), чем CI отличается от pre-push-хука, гейты-«меры» с floors,
ожидание CI одной командой (`gh pr checks --watch`). Иди туда, когда разбираешь красный
CI или добавляешь гейт.

**Реестр гейтов — единственный список.** `scripts/ci/static-gates.sh` и
`docs/scripts/gate-registry.md` генерируются из него, руками их не правь. Новый гейт =
одна запись в реестре + строка `gate blocking|advisory <name> <cmd>`; `|| true` на строке
`gate` запрещён. Branch protection требует **один** check — `CI gate`. Состав workflow'ев,
частей A–G мета-гейта и рецепты — скилл `singularity-todo-quality-tools`.

**Пуш в ветку PR — без `--no-verify`:** pre-push hook проверяет **пушимое дерево**
(структурные гейты + `detekt --auto-correct` + правила дерева). Подробности —
`docs/architecture/verification.md`.

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
- **Issue registry:** `.specify/issues/<slug>.yaml` — record на change (source of truth); импорт легаси `python3 scripts/openspec-import-all.py [--dry-run] [--replace]`. Запись создаётся **сразу** после создания ченджа — гейт `issue registry covers every change` падает на незарегистрированном чендже (четыре таких прошли в `main` мимо гейта). Запись называет **только те документы, которые у ченджа есть**: гейт падает и на записи, у которой в `documents` лежит несуществующий файл. Архивирование ченджа переписывать запись не требует: гейт ищет документы по slug, как и сам чендж.
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
> разбор падений gfxstream по сигнатуре и код-путь, где живёт сам примитив. Подготовку устройства
> (wake + unlock + `settings put secure show_ime_with_hard_keyboard 0`) делает `acquire_device`
> сам — при каждом запуске AVD. Если ты поднял AVD руками, минуя `ensure-emulator`, примени её
> сам: с поднятой клавиатурой все замеры флоу после рестарта неверны — так потеряно 6 замеров
> в одной сессии (2026-10-28).

## 🤖 Coroutine test failures

Падение JVM-теста пишет `build/diagnostics/<TestClass>/coroutines.txt` (состояние, контекст,
job-иерархия, стектрейлы) — читай **первым**: кадры приложения раньше kotlinx указывают, где
корутина была и где она умерла; одинаковые стектрейсы — не доказательство утечки. ADR
`2026-10-03-kotlinx-coroutines-debug.md`, разбор — шаг 5 `debugging-investigation`.

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
8. **Пушить по SSH без keepalive, когда pre-push длинный** — git открывает соединение с
   GitHub *до* хука, и пока хук крутит detekt (несколько минут), соединение простаивает:
   GitHub закрывает его, и пуш падает с 141, хотя хук только что напечатал
   `=== Pre-push OK ===`. Это тот же 141, но другая причина. Лечится keepalive в общем
   git-конфиге: `just setup-hooks` ставит `core.sshCommand` (`ssh -o ServerAliveInterval=30
   -o ServerAliveCountMax=10`) один раз для репозитория — он безопасен для всех worktree'ов.

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
