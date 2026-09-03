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

## Ключевые скиллы (загружаются автоматически)

| Skill | Когда нужен |
|---|---|
| `singularity-todo-sync` | HLC, ConflictResolver, SyncOutbox, Supabase API |
| `singularity-todo-koin-di` | Koin Annotations, @Module, @IntoSet |
| `singularity-todo-feature-scaffold` | Новая CRUD-фича |
| `singularity-todo-ai-tool` | Новый Koog SimpleTool |
| `singularity-todo-attachments` | Файл-вложения, upload, storage |
| `singularity-todo-kotlin-idioms` | boilerplate-reduction: reified, sealed interface, KClass.callBy, value class |
| `singularity-todo-backup` | BackupExporter/Importer, DSL builders, BackupCodec |
| `singularity-todo-notifications` | NotificationPort, ReminderScheduler |
| `singularity-todo-secure-storage` | SecureStoragePort, secret-tool, EncryptedSharedPreferences |
