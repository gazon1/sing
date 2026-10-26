# Contract: The Note-Attachment Write Path

**Feature**: [spec.md](./spec.md) · **Data model**: [data-model.md](./data-model.md)
**Date**: 2026-10-25

Контракт записи заметки, прикреплённой к задаче. Он же — контракт того, почему логбук
терял заметку: примитива «create» была применена к уже существующей строке.

---

## Форма

```kotlin
interface NotesRepository {

    /** Creates a note with content (autosave path). Returns the saved note. */
    suspend fun createWithContent(
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<NoteId>

    /**
     * Creates a note attached to [taskId].
     *
     * The caller owns the id: the editor generates it before the note exists, so the
     * draft, the navigation and the row already speak about the same note. The
     * attachment is structural — it is written once, here, and never by a content save.
     */
    suspend fun createForTask(
        id: NoteId,
        taskId: TaskId,
        title: String,
        bodyMarkdown: String?,
        bodyHtml: String?,
    ): Result<NoteId>

    /** Updates only the content columns of an existing note. */
    suspend fun updateContent(
        id: NoteId,
        title: String,
        bodyMarkdown: String,
        bodyHtml: String,
    ): Result<Unit>
}
```

---

## Гарантии

1. `createForTask(id, taskId, …)` создаёт заметку **с** `task_id = taskId` и **с** `id = id`.
2. Ни один путь записи содержимого не меняет `task_id` существующей строки.
3. `updateContent` меняет только `title`, `body_markdown`, `body_html`, `word_count`,
   `char_count`, `updated_at` — структурные поля (`task_id`, `kind`, `is_pinned`, `color`,
   `sort_order`, `parent_note_id`, `created_at`) не затрагиваются.
4. Ошибка записи возвращается как `Result.failure` / `Either.Left`; строки не остаётся;
   ошибка видна пользователю (`SaveFailed`).
5. Владелец заметки — текущий пользователь; чтение логбука изолировано по `user_id`.
6. Заметка, прикреплённая к задаче, **не переприкрепляется** другой задачей: смена привязки —
   отдельная операция (вне объёма).
7. `createForTask` вызывается ровно один раз на заметку: при её первом сохранении.

## Производители (единственные вызывающие)

| Вызывающий | Когда | Путь |
|---|---|---|
| `NoteEditor` (writer) | первое сохранение заметки из логбука | `createForTask(id, draft.taskId, …)` |
| `NoteEditor` (writer) | первое сохранение обычной заметки | `createWithContent(id, …)` |
| `NoteEditor` (writer) | правка существующей заметки | `updateContent(id, …)` |

## Потребители

- `TaskLogbookCollector` — читает `watchForTask(taskId)`; единственный источник строк логбука.
- `NoteEditor` — читает заметку через `get(id)` после открытия существующей.

## Отвергнутые альтернативы

| Альтернатива | Почему отвергнута |
|---|---|
| Read-modify-write слияние структурных полей внутри `createWithContent` | Список «структурных полей» в Kotlin — ловушка: новая колонка `NoteEntity` забудется в списке, и баг вернётся. Домашняя примитива `edit` для этого не предназначена. |
| DAO-уровень `ON CONFLICT(id) DO UPDATE SET …` | Сырой SQL и вопрос совместимости SQLite на API 26+ (UPSERT требует SQLite 3.24). Плюс список колонок всё равно пришлось бы вести — только в SQL. |
| Создавать заметку при открытии редактора и править только запись | Оставляет пустые «(untitled)»-строки от незавершённых действий. |
| Переприкрепление заметки при повторном `createForTask` | Меняет привязку заметки неявно; смена привязки — отдельная осознанная операция. |
