---
title: UI testing strategy with Ultron + minimal DI seams
status: accepted
date: 2026-09-05
superseded-by: 2026-09-26-ui-testing-deferred
---

## Context

Нужны UI-тесты критических flow на обеих платформах (Android + Desktop JVM).
Page Objects и testTag отсутствуют. Четыре точки в ViewModels напрямую вызывают
статические генераторы (ID, TimeZone, backup filename, attachment save).

## Decision

### 1. Четыре новых порта для тестируемости

| Порт | Зачем | Где используется |
|---|---|---|
| `IdGenerator` | Детерминированные ID в тестах | `TaskEditorViewModel`, `NotesViewModel`, `ChatViewModel` |
| `TimeZoneProvider` | Контролируемая timezone | `TaskEditorViewModel` |
| `BackupFileNamer` | Предсказуемые имена backup-файлов | `BackupViewModel` |
| `AttachmentSaver` | Подменяемый save (без файловой системы) | `TaskEditorViewModel` |

`Clock` — уже абстрагирован через expect/actual. **Не** трогаем:
- `System.currentTimeMillis()` в `SyncEngine`, `ReminderScheduler`, `PomodoroTimer`
- `FilePickerPort` (тесты идут через `FakeAttachmentSaver`)

### 2. Ultron 2.6.5 для widget-тестов

- **Artifact**: `com.atiurin:ultron-compose:2.6.5`
- **Source set**: `androidHostTest` (Robolectric) — покрывает Android widget-тесты
- **Desktop JVM**: стандартный Compose test rule (`createComposeRule`)
- **Page Objects**: тонкий DSL на `SemanticsMatcher.hasTestTag()` — неUltron-специфичный

### 3. Page Objects как SemanticsMatcher-константы

```kotlin
object AuthPage {
    val emailInput: SemanticsMatcher = hasTestTag("auth_email_input")
    val signInButton: SemanticsMatcher = hasTestTag("auth_sign_in_button")
}
```

Используются напрямую с `composeRule.onNodeWithTag(...)` — без наследования от
Ultron `Page<>`, который имеет несовместимый API.

### 4. Трёхуровневая пирамида тестирования

| Уровень | Где | Что покрывает |
|---|---|---|
| **Unit** | `jvmTest` / `commonTest` | ViewModel logic, domain validation, repository contracts |
| **Widget** | `androidHostTest` (Robolectric) | UI elements, user interactions, navigation chrome |
| **Integration** | `jvmTest` с `startKoin` | Full save/read cycle, FakeAppDatabase |

### 5. testTag конвенция

`feature_role` → `auth_email_input`, `task_editor_title_input`, `nav_tab_inbox`

Добавлены в: `LoginScreen`, `TasksScreen`, `TaskCard`, `TaskEditorScreen`, `AndroidShell`, `MenuBottomSheet`.

### 6. DI биндинги

Все 4 порта зарегистрированы в `coreDomainModule()`:
```kotlin
single<IdGenerator> { UlidIdGenerator }
single<TimeZoneProvider> { systemTimeZone }
single<BackupFileNamer> { DefaultBackupFileNamer }
single<AttachmentSaver> { AttachmentsViewModelAttachmentSaver { get() } }
```

### 7. Существующие фейки переиспользованы

- `FakeTaskRepository`, `FakeNotesRepository`, `FakeChecklistRepository`, `FakeReminderRepository`
- `FakeAuthRepository`, `FakeCurrentUser`
- `FakeAttachmentSaver` — новый, реализует `AttachmentSaver`

## Rationale

1. **75% переиспользуем** существующие fakes/порты — не дублируем.
2. **4 новых порта** вместо 8 — минимальный surface area.
3. **Конструктор-VM + явные параметры** вместо `CompositionLocal` — короче и явнее.
4. **Koin в integration-тестах**, не в widget — каждая точка решает одну задачу.

## Consequences

- **~25 новых файлов**: 4 порта, 7 Page Objects, test infrastructure, integration tests
- **~14 изменённых файлов**: Screen.kt + testTag, VM constructors, DI module
- **CI требует adb-устройство** для instrumentation — `SKIP_ADB=1` для пропуска
- **`performTextClear`** не доступен в Robolectric — используется `performTextInput` напрямую
- **`koinInject()` в Screen** требует Koin контекст — widget тесты обходят это через Robolectric + `createComposeRule` без Koin

## Links

- [Ultron GitHub](https://github.com/open-tool/ultron)
- `docs/decisions/DIGEST.md` — актуальная выжимка всех решений
