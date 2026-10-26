# Phase 1 Data Model: Task Logbook Attachment

**Feature**: [spec.md](./spec.md) · **Plan**: [plan.md](./plan.md) · **Research**: [research.md](./research.md)
**Date**: 2026-10-25

---

## 1. `Note` (существующая сущность; меняется только контракт создания)

| Поле | Тип | Правило |
|---|---|---|
| `id` | `NoteId` | назначается вызывающим (черновиком редактора); `createForTask` обязан записать именно его |
| `userId` | `UserId` | владелец; все чтения фильтруются по текущему пользователю |
| `title` | `String` | может быть пустым — пустая заметка допустима |
| `bodyMarkdown` / `bodyHtml` | `String?` | содержимое; HTML — каноническая форма (ADR `2026-10-02`) |
| `taskId` | `TaskId?` | **структурная привязка**; `null` — заметка не прикреплена. Пишется один раз, при создании |
| `createdAt` / `updatedAt` | `Instant` | `createdAt` не переписывается сохранением содержимого |
| `kind` | `NoteKind` | `Plain` по умолчанию |
| `isPinned` / `pinnedAt` | `Boolean` / `Instant?` | структурные поля — точечный UPDATE их не трогает |
| `color` | `Int?` | структурное поле |
| `sortOrder` | `Int` | структурное поле |
| `parentNoteId` | `NoteId?` | структурное поле (папки) |
| `outgoingLinks` | `String` (JSON) | викилинки; `task://`-токен сюда **не** пишется (ADR `2026-10-02`) |

**Правила валидации**
- `taskId` ≠ null ⇒ строка `notes.task_id` ссылается на существующую задачу (FK
  `notes.task_id → tasks(id)`, `ON DELETE SET NULL`).
- Идентификатор заметки уникален; создание с существующим id — не путь этого фича.

---

## 2. `Editing` (черновик редактора) — новое поле

| Поле | Тип | Правило |
|---|---|---|
| `id` | `String` | id заметки; до первого сохранения заметки в БД нет |
| `title` | `Revisioned<String>` | содержимое заголовка + счётчик замен извне |
| `html` | `Revisioned<String>` | содержимое тела + счётчик замен извне |
| `isDirty` | `Boolean` | пользователь что-то менял |
| `isNew` | `Boolean` | заметка ещё не в БД (create-on-first-save) |
| `taskId` | `TaskId?` | **новое**: привязка, заданная при открытии из логбука; переносится в заметку при создании |

**Связи**
- `Editing.taskId` → `Task.id` (структурная привязка логбука).
- `Editing.id` → `Note.id` (черновик и строка — одна заметка; до сохранения строки нет).

---

## 3. Состояния записи логбук-заметки

```text
   openLogbookEditor(taskId)
            │
            ▼
     [NOT_PERSISTED] ──сохранение после правки──▶ createForTask(id, taskId, …) ──▶ [ATTACHED]
            │                                              │                            │
            │                                              └── ошибка ──▶ [FAILED]     │ правка
            └── выход без сохранения ──▶ (нет состояния: ничего не создано)            ▼
                                                                                                 [ATTACHED]
```

| Состояние | Что в БД | Что видит пользователь |
|---|---|---|
| `NOT_PERSISTED` | ничего | пустой логбук |
| `ATTACHED` | строка с `task_id = taskId` | заметка в логбуке задачи |
| `FAILED` | ничего (запись — одна транзакция) | ошибка сохранения |

**Переходы**
- `NOT_PERSISTED → ATTACHED`: единственный путь — `createForTask(id, taskId, …)`, то есть
  запись, несущая привязку.
- `NOT_PERSISTED → FAILED`: запись вернула ошибку; строка не остаётся.
- `NOT_PERSISTED → (выход)`: пользователь закрыл редактор, ничего не написав; записи нет.
- `ATTACHED → ATTACHED`: `updateContent` — точечный UPDATE; `task_id` не меняется.

**Инварианты**
1. В `NOT_PERSISTED` в БД нет ни одной строки, `watchForTask` не показывает ничего.
2. `task_id` меняется только в `NOT_PERSISTED → ATTACHED`.
3. Из `ATTACHED` нельзя переприкрепить заметку к другой задаче: смена привязки — отдельная
   операция (вне объёма фичи).
4. Ошибка записи не оставляет частично записанной строки.

---

## 4. `LogbookEntry` / `TaskLogbookState` (без изменений)

| Тип | Поля | Правило |
|---|---|---|
| `LogbookEntry.NoteEntry` | `note: Note` | заметка, прикреплённая к задаче |
| `LogbookEntry.TimeEntryRow` | `entry: TimeEntry` | запись тайм-трекинга |
| `TaskLogbookState.Empty` | — | задача не загружена или записей нет |
| `TaskLogbookState.Loaded` | `entries: List<LogbookEntry>` | записи по дате убыв. |

**Источники**: `notesRepo.watchForTask(task.id)` (фильтр по `task_id` и uid) и
`timeTrackingRepo.watchEntries(task.id)`. Оба реактивны: новая заметка появляется без
ручного обновления.
