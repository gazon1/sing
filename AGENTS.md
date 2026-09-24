# Singularity Todo — Agent Cheatsheet

> Этот файл загружается автоматически в каждый чат с агентом.
> Обновлённая версия: см. `ARCHITECTURE.md` для полной документации.

## Структура проекта

```
androidApp/          — Android shell (MainActivity.kt, AndroidManifest)
desktopApp/          — Desktop Compose entry (main.kt, singleWindowApplication)
shared/              — KMP library: commonMain + androidMain + jvmMain + tests
  src/commonMain/kotlin/com/singularity/todo/
    core/            — инфраструктура: auth, backup, clock, coroutines, database, di, draft, error, files, ids, llm, log, network, notifications, observability, platform, reminders, security, serialization, settings, sync, tree, ui, validation
    feature/         — фичи: agenda, ai, archive, attachments, auth, backup, calendar, checklist, genui, notes, pomodoro, profile, projects, reminders, search, settings, statistics, tags, tasks
    test/fakes/      — Fake-реализации для тестов (без моков)
```

**iOS нет.** Только Android + JVM Desktop.

---

## Канонический CRUD-паттерн (новой фичи)

```
Ids.kt                    — @JvmInline value class (TaskId, NoteId, ProjectId, TagId...)
*Domain.kt                — чистая валидация, бизнес-правила
*Repository.kt            — интерфейс (Result<T>, suspend, Flow)
*RepositoryImpl.kt        — Room-реализация
*UseCase.kt               — ТОЛЬКО реальная логика (валидация, clock.now(), build). НЕ pass-through обёртки. Enforced by `PassThroughUseCase` detekt rule (`:detekt-rules` module).
*ViewModel.kt             — StateFlow<SealedUiState>, sealed Intent, viewModelScope
*Screen.kt                — Compose UI
```

---

## DI: Koin 4.x (pure DSL)

**Koin Annotations не используются** — `koin-annotations 4.x` несовместим с Koin 4.x.
Весь DI — чистый Kotlin DSL. Примеры:

```kotlin
single { SomeRepository(get()) }                    // singleton
factory { SomeViewModel(get(), get()) }            // new instance per injection
viewModel { (p: Param) -> Vm(get(), p) }            // VM с runtime-параметрами (prefer viewModel {}, НЕ factory)
viewModelOf(::MyViewModel)                         // VM без параметров
module { includes(otherModule) }                    // compose modules
```

**ViewModel scope (2026-09-06):**

| DSL | Когда использовать |
|---|---|
| `viewModelOf(::Vm)` | Все VM без runtime-параметров (prefer) |
| `viewModel { (p) -> Vm(...) }` | VM с runtime-параметрами |
| `koinViewModel()` | Инъекция VM в Composable (не `koinInject()`) |
| `koinViewModel { parametersOf(p) }` | Для VM с runtime-параметрами |
| `factory { Vm(...) }` | **Never** для ViewModel — memory leak |
| `koinInject()` | Репозитории и сервисы (не VM) |

Подробности: `singularity-todo-vm-koin-scoping` skill и `docs/decisions/2026-09-06-koin-vm-viewmodelof-koinviewmodel.md`.

Весь DI в `shared/src/commonMain/.../core/di/Modules.kt` (domainModule).
Platform bindings — в `PlatformModule.jvm.kt` / `PlatformModule.android.kt`.

---

## Тесты

| Source set | Что | Как запустить |
|---|---|---|
| `commonTest` | pure Kotlin, без платформы | `./gradlew :shared:commonTest` |
| `jvmTest` | Room + SQLite | `./gradlew :shared:jvmTest` |
| `androidHostTest` | Robolectric, Android resources | `./gradlew :shared:testAndroidHostTest` |

**Fake вместо моков** — все двойники в `test/fakes/FakeRepositories.kt`:
`FakeTaskRepository`, `FakeNotesRepository`, `FakeProjectsRepository`, `FakeTagsRepository`, `FakeSettingsRepository`, `FakeSecureStorage`, `FakeNotificationPort`, `FakeTextGen`.

### Тестовые helpers

```kotlin
// Три формы теста (singularity-todo-test-helpers skill):
testVm({ vm: MyVm -> vm.state }) { MyVm(deps, scope = testScope(this)) }
ctx.act { it.onIntent(Intent.Load) }
ctx.assertIs<UiState.Content>()

// Ожидание виртуального времени (не delay!)
awaitState { vm.state.value is UiState.Content }
```

### Test style: BAN list

| Запрещено | Почему | Альтернатива |
|---|---|---|
| `stateIn` в ViewModel | Держит upstream active 5s, ломает тесты без subscriber | `MutableStateFlow + scope.launch { }.collect {}` |
| `viewModelScope` в production | Tight coupling, нетестируемо | Инъектированный `AutoCloseableCoroutineScope` |
| `runBlocking` в production | Blocking поток, deadlock risk | `scope.launch { }` |
| `delay(N)` в тестах | Реальное время, не virtual time | `advanceUntilIdle()` + debounce mocking |
| `vm.state.launchIn(scope)` workaround | Костыль вокруг `stateIn` | Canonical VM pattern |

### Canonical VM pattern

```kotlin
class MyViewModel(
    private val deps: MyDeps,
    private val scope: AutoCloseableCoroutineScope = AutoCloseableCoroutineScope(),
) : ViewModel() {
    init { addCloseable(scope) }
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state.asStateFlow()
    init {
        scope.launch {
            repo.observe().collect { _state.value = it }
        }
    }
}
```

**Подробности:** `singularity-todo-testable-vm`, `singularity-todo-vm-migration-playbook`

---

## expect/actual порты

**Интерфейсы/классы** (platform boundaries):

| Порт | commonMain | jvmMain | androidMain |
|---|---|---|---|
| `SecureStoragePort` | интерфейс | secret-tool + AES-GCM | EncryptedSharedPreferences |
| `NotificationPort` | интерфейс | notify-send + at | AlarmManager + NotificationManager |
| `FileSystem` | интерфейс | JvmFileSystem | AndroidFileSystem |
| `BackupCodec` | интерфейс | JvmBackupCodec (java.util.zip) | AndroidBackupCodec |
| `MarkdownHtmlPort` | интерфейс | RichEditorMarkdownHtmlPort | — (shared) |
| `AttachmentStorage` | **класс** (не интерфейс) | — | — |
| `Clock` | expect object | — (kotlinx-datetime) | — (kotlinx-datetime) |
| `systemTimeZone` | expect val | — | — |

**Фабричные функции** (platform factories):

| Функция | jvmMain | androidMain |
|---|---|---|
| `createSqlDriver()` | SQLite JDBC | sqlite-bundled |
| `createHttpClient()` | OkHttp | OkHttp |
| `createBackgroundScope()` | `CoroutineScope(Dispatchers.Default)` | `CoroutineScope(Dispatchers.Default)` |
| `initLogging()` | Kermit + Logback | Kermit + Logcat |
| `platformModule()` | все platform bindings | все platform bindings |
| `aiToolsModule()` | 32 Koog tools | 32 Koog tools |
| `createKoogPromptExecutor()` | MultiLLMPromptExecutor + OkHttp | AndroidKoogFactory (error stub) |
| `onSecondaryClick()` | AWT secondary click | desktop: secondary pointer |

**Навигация** (expect/actual NavGraphs): `TasksNavGraph`, `tasksEntryProvider`, `ProjectsNavGraph`, `projectsEntryProvider`, `NotesNavGraph`, `SearchNavGraph`, `SettingsNavGraph`, `CalendarNavGraph`, `calendarEntryProvider`, `AgendaNavGraph`, `agendaEntryProvider`.

---

## Сборка

```bash
# Быстрая проверка
./gradlew :shared:jvmTest

# Полная сборка Android
./gradlew :androidApp:assembleDebug

# Desktop
./gradlew :desktopApp:run

# Локальный check (тесты + Android сборка)
./check.sh

# Desktop JVM UI test
./gradlew :desktopApp:jvmTest

# ── Lint / Code quality ───────────────────────────────────────────────────────
just lint              # detekt analysis (shared + desktopApp), report-only
just detekt-fix       # auto-fix detekt rules + ktlint formatting in-place
just detekt-baseline  # пересоздать baseline файлы
just coverage         # kover XML coverage reports → shared/build/reports/kover/
just tcheck           # tests + assembleDebug + lint (полный pipeline)

# Direct gradle (если just недоступен)
./gradlew :shared:detekt :desktopApp:detekt
./gradlew :shared:koverXmlReport :desktopApp:koverXmlReport
```

---

## 🤖 Dogfooding: MCP Server + ZCode Agent

ZCode / Claude Code / Cursor подключается к `:mcp-server` через stdio и управляет задачами, проектами, заметками, тегами и ADR напрямую.

### Структура MCP сервера

```
mcp-server/src/main/kotlin/com/singularity/todo/mcp/
  Main.kt                  — точка входа, stdio transport
  ToolRegistrar.kt         — Koog SimpleTool → MCP Tool adapter
  ToolAnnotations.kt       — readOnly/destructive/idempotent/openWorld
  errors/
    McpToolError.kt       — Validation/NotFound/Conflict/Unauthorized/Internal
    ErrorMapper.kt         — → isError:true или -32603 JSON-RPC
  pagination/
    CursorCodec.kt        — URL-safe Base64 cursor для list-пагинации
```

### Доступные MCP tools

**Write tools (idempotent, destructive где нужно):**
`create_task`, `update_task`, `delete_task`, `create_note`, `update_note`, `delete_note`, `create_project`, `update_project`, `create_tag`, `delete_tag`, `write_adr`

**Read/List tools:**
`get_task`, `list_tasks`, `search_tasks`, `list_linked_tasks`, `get_note`, `get_project`, `list_adrs`, `read_adr`

**AI Gen tools:**
`refine_task`, `smart_rewrite`, `generate_description`, `decompose_task`, `generate_checklist`, `pick_time`, `cluster_tasks`, `cluster_notes`, `project_review`, `weekly_plan`, `improve_note`

### Подключение в ZCode

```bash
# В ZCode config (.zcode/settings.json или MCP servers):
{
  "mcpServers": {
    "singularity-todo": {
      "command": "java",
      "args": ["-jar", "build/libs/mcp-server-jvm.jar", "--profile=ai-agent"]
    }
  }
}
```

### Запуск вручную

```bash
./gradlew :mcp-server:build
java -jar mcp-server/build/libs/mcp-server-jvm-*.jar
```

### Token observability

Каждый вызов AI tool записывается в `llm_usage` (Room v8):
- `input_tokens`, `output_tokens`, `total_tokens`
- `cost_usd_micros` (по pricing table)
- `duration_ms`
- `profile_id` (из `ProfileAwareCurrentUser`)

Просмотр: **Settings → AI Usage** или `/run-android` → UI Automation.

### Multi-profile

Профили изолируют данные. ZCode подключается с `--profile=ai-agent`:
- Профиль "AI Agent" (🤖) — для dogfooding
- Профиль "Personal" (🏠) — для своих задач
- Переключение: Settings → Profiles

### Важные ограничения

- MCP tools **не меняют** state напрямую — только через репозитории (как обычные VMs)
- `write_adr` создаёт `.md` файлы в `docs/decisions/`
- Все timestamps — `kotlin.time.Instant` (проектный `Clock.now()`)
- Ошибки: business errors → `isError:true`, internal → `-32603`

---

## 🔧 Run-loop для агента (UI-верификация)

### Android (UI Automator через MCP)
```
mcp__android_emulator__android_preflight
mcp__android_emulator__android_build_and_run (serial=...)
mcp__android_emulator__android_ui_status
mcp__android_emulator__android_screenshot        ← baseline
mcp__android_emulator__android_ui_describe       ← UI tree
mcp__android_emulator__android_ui_resolve        ← coords
mcp__android_emulator__android_ui_tap / type_text
mcp__android_emulator__android_screenshot        ← after
mcp__android_emulator__android_logs              ← crashes
```

### Desktop (JVM)
```bash
# Без окна (быстро)
./gradlew :desktopApp:jvmTest

# С окном (headed)
./gradlew :desktopApp:run

# Headless Linux (Xvfb)
xvfb-run -a ./gradlew :desktopApp:run
```

### DB inspect
```bash
# Android
adb shell run-as com.singularity.todo cp databases/singularity.db /sdcard/
adb pull /sdcard/singularity.db /tmp/
sqlite3 /tmp/singularity.db ".schema"

# Desktop
sqlite3 ~/.local/share/singularity/databases/singularity.db ".schema"
```

---

## ❌ Что НЕ делать

1. **`runBlocking` в ViewModel init** — вместо этого: `combine(filterFlow, userIdFlow) { ... }` + `flatMapLatest`
2. **`*Blocking()` методы в репозиториях** — только suspend + Result<T>
3. **MockK / Mockito** — используй `Fake*` из `test/fakes/`
4. **Pass-through CRUD use cases** — `GetTaskUseCase`, `DeleteTaskUseCase` и т.п. — это boilerplate; VMs инжектят `TaskRepository` напрямую. AI-specific use cases (`RefineTaskUseCase`, `DecomposeTaskUseCase`, etc.) — допустимы и нужны.. `ChecklistUseCase` trimmed 2026-09-18. Enforced by `PassThroughUseCase` detekt rule — see `docs/decisions/2026-09-18-no-pass-through-usecases.md`.
5. **`java.io.File` напрямую** — только через `FileSystem` порт
6. **`require { throw ... }` внутри лямбды** — `require` сам бросает; тело `require { throw X }` никогда не выполняется
7. **Импортировать Koog-типы вне `feature/ai` и `core/di`**
8. **Грубые изменения Room schema** — `SCHEMA_VERSION` + `AutoMigration`

---

## 🧠 Decision log — рабочий workflow

Перед началом любой нетривиальной задачи **прочитай `docs/decisions/DIGEST.md`** — это компактная выжимка всех принятых решений и правил проекта. Если задача меняет архитектуру, контракт или обнаруживает неочевидный workaround — **создай новую запись** в `docs/decisions/YYYY-MM-DD-<slug>.md` и пересобери digest:

```bash
./scripts/refresh-decisions-digest.sh
```

**Когда писать запись:**
- Выбор между несколькими разумными вариантами.
- Обнаружен неочевидный workaround (статическая инициализация, generic-type баг).
- Изменился контракт между модулями / слоями.
- Пользователь явно попросил зафиксировать рассуждение.

**Когда НЕ писать:** опечатки, форматирование, новые use case'ы по существующему паттерну (pattern уже покрыт skill-ами).

**Формат записи** — frontmatter + секции `Context / Idea / Decision / Rationale / Consequences / Links`. Подробности в skill `singularity-todo-decisions-workflow`.

### 🛠 Maintenance

| Команда | Что делает |
|---|---|
| `just docs-audit` | Normalize frontmatter (dry-run) + refresh DIGEST + source tree drift check |
| `./scripts/normalize-adr-frontmatter.sh --apply` | Добавить `status: accepted` где отсутствует |
| `./scripts/refresh-decisions-digest.sh` | Пересобрать `DIGEST.md` из ADR |
| `python3 scripts/print-source-tree.py` | Сгенерировать §1 ARCHITECTURE.md |

**Policy**: `docs/doc-maintenance.md` — полные правила (когда писать ADR, KDoc policy, чеклисты).

### Ключевые скиллы (загружаются автоматически)

Skill-ов немного и они узкие. **Большинство архитектурных знаний теперь живёт в `docs/decisions/DIGEST.md`, а не в skill-ах** — этот файл нужно прочитать перед задачей.

| Skill | Когда нужен |
|---|---|
| `singularity-todo-decisions-workflow` | Создание/обновление записей в `docs/decisions/`. Прочитать один раз для понимания формата. |
| `singularity-todo-feature-scaffold` | Новая CRUD-фича (Task, Note, Project, Tag, ...) |
| `singularity-todo-nav3-nested-graphs` | Новая nested graph, новый route в существующем `*Route`, `Local*Navigator`, `entryProvider` |
| `singularity-todo-nav3-savedstate` | Android vs JVM путь для back stack. Добавление типа в sealed `*Route`. Android: `navSavedStateConfig(...)`. JVM: `rememberInMemoryNavBackStack`. |
| `singularity-todo-ai-tool` | Новый Koog `SimpleTool<T>` |
| `singularity-todo-mcp-server` | MCP-сервер, Koog→MCP adapter, ToolRegistrar |
| `singularity-todo-multi-profile` | Profile domain, ProfileRepository, ProfileAwareCurrentUser |
| `singularity-todo-llm-usage-tracking` | UsageRecorder, LlmUsageEntity, pricing table, AI Usage screen |
| `singularity-todo-attachments` | Файл-вложения, upload, storage |
| `singularity-todo-backup` | BackupExporter/Importer, DSL builders, BackupCodec |
| `singularity-todo-koin-di` | Koin '@Module', '@IntoSet', `koinBridge` |
| `singularity-todo-koog-agent` | Koog агент — паттерн (НЕ кросс-платформенная; см. decision `2026-09-05-koog-both-platforms`) |
| `singularity-todo-secure-storage` | SecureStoragePort, secret-tool, EncryptedSharedPreferences |
| `singularity-todo-sync` | HLC, ConflictResolver, SyncOutbox, Supabase API |
| `singularity-todo-notifications` | NotificationPort, ReminderScheduler |
| `singularity-todo-shared-ui-components` | `SettingsSection`, `ResultDialog`, декомпозиция Composable; Slot API; `TaskEditorContent` |
| `singularity-todo-pure-formatters` | Чистые хелперы формата (тестируются без Compose) |
| `singularity-todo-stable-json` | Centralized `StableJson`; `encodeDefaults`, `ignoreUnknownKeys`; `@Serializable` patterns |
| `singularity-todo-draft-restoration` | `DraftStore<T>`; DataStore implementation; seed-if-empty; debounce 500ms; clear on success/discard |
| `singularity-todo-rich-editor` | Rich-text WYSIWYG для заметок |
| `singularity-todo-room-migration` | Миграции Room-схемы |
| `singularity-todo-ui-event-vs-state` | One-shot события vs continuous state в VM |
| `singularity-todo-preview-with-koin` | `@Preview` без Koin — VM-as-parameter pattern, FakeRepositories для preview |
| `singularity-todo-quality-tools` | detekt 2.x + ktlint + kover: запуск, конфиг, baseline, auto-fix. `just lint`, `just detekt-fix`, `just coverage` |
| `singularity-todo-clean-architecture-audit` | Проверка layer boundaries: grep-чеки + `just lint`. Прежде чем мержить feature. |
| `singularity-todo-testable-vm` | Canonical VM pattern: `MutableStateFlow + scope.launch { }.collect {}`, `AutoCloseableCoroutineScope` как default param, BAN list (`stateIn`, `viewModelScope`, `runBlocking`), side-effect extraction pattern. |
| `singularity-todo-vm-migration-playbook` | Как мигрировать VM с `stateIn`/`scopeOverride`/`viewModelScope` на канонический паттерн. 5-шаговый checklist, dedicated-collector pattern для side-effects в combine, `viewModelOf` vs `viewModel {}`. |
| `singularity-todo-test-helpers` | `testVm`, `awaitState`, `TestVmContext`, `assertIs`, `testScope`, `FakeRepositories`. Три формы тестов (smoke, intent→state, regression). `FakeTextGen` для failure-сценариев. |
| `singularity-todo-coroutine-scopes` | Где живут `CoroutineScope` в KMP проекте. Антипаттерн: репозиторий создаёт свой `CoroutineScope(Dispatchers.Default)` — ломает VM-тесты. Канонический pattern: `createBackgroundScope()` через DI, mandatory param в конструкторе. |

**Удалённые skill-ы** (информация переехала в `docs/decisions/`):
~~`singularity-todo-koin-suspend-bridge`~~ — см. `2026-09-05-koin-suspend-bridge.md`.
~~`singularity-todo-ai-provider-settings`~~ — см. `2026-09-05-llm-provider-settings.md`.
~~`singularity-todo-secret-migration`~~ — см. `2026-09-05-secret-storage-split.md`.
~~`singularity-todo-koog-test-workarounds`~~ — см. `2026-09-05-koog-test-workarounds.md`.
~~`singularity-todo-koog-both-platforms`~~ — см. `2026-09-05-koog-both-platforms.md`.
