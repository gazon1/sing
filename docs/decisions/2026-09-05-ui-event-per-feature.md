---
title: Per-feature UiEvent — маршрутизация событий без глобальной утечки типов
date: 2026-09-05
tags: [ui, architecture, events, koin]
status: accepted
---

## Context

Глобальный `UiEvent` в `core/ui/components/UiEvent.kt` определяет три варианта:
`ShowDialog(title, text)`, `ShowError(message)`, `NavigateBack`. Все 8+ экранов
используют одни и те же варианты — это создаёт проблемы:

1. **`ProjectEditorScreen.kt:63-64`** — мёртвый event branch: `is UiEvent.ShowDialog → { /* no-op */ }`. VM никогда не шлёт `ShowDialog`, но экран вынужден его обрабатывать.
2. **`NotificationHost` widget** невозможно сделать generic — он был бы привязан к `UiEvent.ShowDialog/ShowError`, но разные экраны форматируют текст по-разному ( Tasks → "AI Result", Notes → "AI Result", Chat → динамический title).
3. Все фичи делят одни и те же имена → конфликты при расширении.

## Idea

Каждая фича объявляет свой `sealed interface XxxUiEvent : UiEvent` с фиче-специфичными
событиями. `core/ui/components/UiEvent` становится **empty marker interface**.
`NotificationHost<T : UiEvent>` принимает `Flow<T>` + `mapper: (T) → Notification`.

Это позволяет:
- Фиче-специфичные события (TasksUiEvent.AiResult, NotesUiEvent.AiResult, ChatUiEvent.Error)
- Generic `NotificationHost` без знания о конкретном sealed типе
- Сохранить обратную совместимость: существующие `_events: MutableSharedFlow<UiEvent>` продолжают работать

## Decision

### 1. UiEvent → marker interface

`core/ui/components/UiEvent.kt` становится empty marker:

```kotlin
sealed interface UiEvent
```

Конкретные варианты `ShowDialog`, `ShowError`, `NavigateBack` **остаются** в своих
per-feature файлах (не в глобальном). Существующие VM `_events.emit(UiEvent.ShowDialog(...))`
сначала меняем на `emit(TasksUiEvent.ShowDialog(...))` и т.д.

### 2. Per-feature sealed interfaces

Каждая фича определяет свой `sealed interface XxxUiEvent : UiEvent` рядом с ViewModel:

```kotlin
// feature/tasks/TasksUiEvent.kt
sealed interface TasksUiEvent : UiEvent {
    data class AiResult(val text: String) : TasksUiEvent
    data class Error(val message: String) : TasksUiEvent
    data object NavigateBack : TasksUiEvent
}

// feature/notes/NotesUiEvent.kt
sealed interface NotesUiEvent : UiEvent {
    data class AiResult(val text: String) : NotesUiEvent
    data class SaveFailed(val message: String) : NotesUiEvent
    data object NavigateBack : NotesUiEvent
}

// feature/projects/ProjectsUiEvent.kt
sealed interface ProjectsUiEvent : UiEvent {
    data class ProjectReviewResult(val text: String) : ProjectsUiEvent
    data class Error(val message: String) : ProjectsUiEvent
    data object NavigateBack : ProjectsUiEvent
}

// feature/checklist/ChecklistUiEvent.kt
sealed interface ChecklistUiEvent : UiEvent {
    data class Error(val message: String) : ChecklistUiEvent
    data object NavigateBack : ChecklistUiEvent
}

// feature/ai/chat/ChatUiEvent.kt
sealed interface ChatUiEvent : UiEvent {
    data class Error(val message: String) : ChatUiEvent
}

// feature/archive/ArchiveUiEvent.kt
sealed interface ArchiveUiEvent : UiEvent {
    data class Dialog(val title: String, val text: String) : ArchiveUiEvent
    data class Error(val message: String) : ArchiveUiEvent
}
```

### 3. Notification sealed interface (widget-level)

```kotlin
// core/ui/components/Notification.kt
sealed interface Notification {
    data class Text(val title: String, val text: String?) : Notification
    data class Error(val message: String) : Notification
    data object NavigateBack : Notification
    data object Dismiss : Notification
}
```

### 4. NotificationHost<T : UiEvent> widget

```kotlin
@Composable
fun <T : UiEvent> NotificationHost(
    events: Flow<T>,
    mapper: (T) -> Notification,
    onNavigateBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
)
```

- `var notification by remember { mutableStateOf<Notification?>(null) }`
- `CollectEvents(events) { notification = mapper(it) }`
- Рендерит `ResultDialog` для `Text`/`Error`, вызывает `onNavigateBack()` для `NavigateBack`, сбрасывает `Dismiss` → `notification = null`

### 5. Migration strategy

Сначала добавляем per-feature events **параллельно** со старыми. Существующие VM имеют:

```kotlin
private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 4)
val events: Flow<UiEvent> = _events
```

После миграции:

```kotlin
private val _events = MutableSharedFlow<TasksUiEvent>(extraBufferCapacity = 4)
val events: Flow<TasksUiEvent> = _events
```

Один тип меняется — все 8 экранов мигрируют одновременно в одном коммите.
Существующие тесты (которые инжектят `_events.emit(...)`) обновляются на новый тип.

## Rationale

- **Generic NotificationHost** — widget больше не знает про конкретные sealed типы
- **Feature isolation** — TasksUiEvent не может попасть в NotesUiEvent
- **Dead branch elimination** — ProjectEditor больше не обрабатывает ShowDialog который VM не шлёт
- **Consistent naming** — `AiResult` вместо `ShowDialog(title = "AI Result", text = ...)`
- **Backward compatible** — `UiEvent` marker позволяет использовать `Flow<UiEvent>` там где нужен общий тип

## Consequences

- **UiEvent marker** — `ShowDialog/ShowError/NavigateBack` больше не определены глобально
- **8 экранов мигрируют одновременно** — невозможно сделать постепенную миграцию из-за смены типа `_events`
- **Существующие тесты** использующие `TasksViewModel`, `NotesViewModel` и т.д. — `_events.emit(UiEvent.ShowDialog(...))` нужно обновить на `TasksUiEvent.AiResult(...)`
- **CollectEvents** в виджетах принимает `Flow<T : UiEvent>` — generic call site остаётся тем же
- **NotificationHost** — финальный widget для всех экранов, заменяет ~64 строк ручного glue кода

## Links

- `core/ui/components/UiEvent.kt` — текущий глобальный sealed
- `core/ui/components/CollectEvents.kt` — уже существует, используется
- `core/ui/components/ResultDialog.kt` — уже существует
- Decision: `2026-09-05-ui-tests-ultron` — тестовая стратегия
