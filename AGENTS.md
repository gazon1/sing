# Singularity Todo — Agent Cheatsheet

> Этот файл загружается автоматически в каждый чат с агентом.
> Обновлённая версия: см. `ARCHITECTURE.md` для полной документации.

## Структура проекта

```
androidApp/          — Android shell (MainActivity.kt, AndroidManifest)
desktopApp/          — Desktop Compose entry (main.kt, singleWindowApplication)
shared/              — KMP library: commonMain + androidMain + jvmMain + tests
  src/commonMain/kotlin/com/singularity/todo/
    core/            — инфраструктура: auth, backup, database, di, files, notifications, security, settings, sync, ui
    feature/         — фичи: tasks, notes, projects, tags, search, ai, auth, settings, attachments, backup, reminders
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
*UseCase.kt               — ТОЛЬКО реальная логика (валидация, clock.now(), build). НЕ pass-through обёртки.
*ViewModel.kt             — StateFlow<SealedUiState>, sealed Intent, viewModelScope
*Screen.kt                — Compose UI
```

---

## DI: Koin Annotations 4.2.2

```kotlin
@Single                      // singleton
@Factory                     // new instance per injection
@IntoSet                     // add to a Set<T> (напр. 16 AI tools)
@Module
@ComponentScan("com.myapp.feature")
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

```kotlin
// Типичный VM-тест
val vm = NotesViewModel(FakeNotesStore(), FakeHtmlPort(), FakeSettingsRepo())
runTest {
    vm.openEditor("n1")
    assertTrue(vm.editorState.value is EditorState.Editing)
}
```

---

## expect/actual порты (7 штук)

| Порт | commonMain | jvmMain | androidMain |
|---|---|---|---|
| `SecureStoragePort` | интерфейс | secret-tool + AES-GCM | EncryptedSharedPreferences |
| `NotificationPort` | интерфейс | notify-send + at | AlarmManager + NotificationManager |
| `FileSystem` | интерфейс | JvmFileSystem | AndroidFileSystem |
| `BackupCodec` | интерфейс | JvmBackupCodec (java.util.zip) | AndroidBackupCodec |
| `MarkdownHtmlPort` | интерфейс | RichEditorMarkdownHtmlPort | — (shared) |
| `AttachmentStorage` | интерфейс | — | — |
| `createKoogPromptExecutor()` | expect fun | JvmKoogFactory (MultiLLMPromptExecutor) | AndroidKoogFactory (error) |

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
```

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
4. **Pass-through use cases** — `GetTaskUseCase`, `DeleteTaskUseCase` и т.п. — это boilerplate; VMs инжектят `TaskRepository` напрямую
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

### Ключевые скиллы (загружаются автоматически)

Skill-ов немного и они узкие. **Большинство архитектурных знаний теперь живёт в `docs/decisions/DIGEST.md`, а не в skill-ах** — этот файл нужно прочитать перед задачей.

| Skill | Когда нужен |
|---|---|
| `singularity-todo-decisions-workflow` | Создание/обновление записей в `docs/decisions/`. Прочитать один раз для понимания формата. |
| `singularity-todo-feature-scaffold` | Новая CRUD-фича (Task, Note, Project, Tag, ...) |
| `singularity-todo-ai-tool` | Новый Koog `SimpleTool<T>` |
| `singularity-todo-attachments` | Файл-вложения, upload, storage |
| `singularity-todo-backup` | BackupExporter/Importer, DSL builders, BackupCodec |
| `singularity-todo-koin-di` | Koin `@Module`, `@IntoSet`, `koinBridge` |
| `singularity-todo-koog-agent` | Koog агент — паттерн (НЕ кросс-платформенная; см. decision `2026-09-05-koog-both-platforms`) |
| `singularity-todo-secure-storage` | SecureStoragePort, secret-tool, EncryptedSharedPreferences |
| `singularity-todo-sync` | HLC, ConflictResolver, SyncOutbox, Supabase API |
| `singularity-todo-notifications` | NotificationPort, ReminderScheduler |
| `singularity-todo-shared-ui-components` | `SettingsSection`, `ResultDialog`, декомпозиция Composable |
| `singularity-todo-pure-formatters` | Чистые хелперы формата (тестируются без Compose) |
| `singularity-todo-rich-editor` | Rich-text WYSIWYG для заметок |
| `singularity-todo-room-migration` | Миграции Room-схемы |
| `singularity-todo-ui-event-vs-state` | One-shot события vs continuous state в VM |

**Удалённые skill-ы** (информация переехала в `docs/decisions/`):
~~`singularity-todo-koin-suspend-bridge`~~ — см. `2026-09-05-koin-suspend-bridge.md`.
~~`singularity-todo-ai-provider-settings`~~ — см. `2026-09-05-llm-provider-settings.md`.
~~`singularity-todo-secret-migration`~~ — см. `2026-09-05-secret-storage-split.md`.
~~`singularity-todo-koog-test-workarounds`~~ — см. `2026-09-05-koog-test-workarounds.md`.
~~`singularity-todo-koog-both-platforms`~~ — см. `2026-09-05-koog-both-platforms.md`.
