# Implementation Plan: Logbook — привязка заметки к задаче (`015-fix-task-logbook`)

**Branch**: `gazon1/houndshark` — отдельная ветка **не создаётся**: хука `before_specify`/`before_plan`
в `.specify/extensions.yml` нет, рабочее дерево чистое.
**Date**: 2026-10-25 · **Spec**: `specs/015-fix-task-logbook/spec.md` (создаётся шагом 0)
**Input**: Feature specification from `/specs/015-fix-task-logbook/spec.md`

> **Происхождение документа.** Этот план — результат `/speckit-plan` от 2026-10-25,
> переписанный после self-review (гриль собственного плана). Артефакты Phase 0 и Phase 1
> лежат рядом: `research.md`, `data-model.md`, `contracts/`, `quickstart.md`.
>
> **План переписан после self-review** (гриль собственного плана): из него выброшено
> read-modify-write-слияние в `createWithContent` (противоречило домашней модели записи),
> привязка перенесена из поля VM в черновик, добавлено правило «незатронутый черновик не
> записывается» (без него решение пользователя №2 не выполнялось), добавлены разделы
> «Скиллы» и «Читаемость vs индустриальные практики». Полный список правок — §7.

---

## Summary

Три независимо сливаемые части. **A — запись.** Логбук задачи не работает: заметка, созданная
из логбука, теряет привязку к задаче при первом сохранении и исчезает из логбука. Виновата не
логика чтения, а **применение не той примитивы записи**: заметка создаётся заранее (при
открытии редактора), а её первое сохранение идёт примитивой «create» — полной заменой строки,
а значит обнуляет `notes.task_id`. Фикс: прикреплённая заметка создаётся **один раз, полной
сущностью, уже с привязкой**, а все последующие сохранения идут точечным UPDATE, который
трогает только колонки содержимого. **B — адресация.** У секции логбука нет `testTag`, поэтому
флоу не может её ни найти, ни дождаться: добавляются четыре тега, золотой `Maestro/TAGS.md`
перегенерируется. **C — проверка на устройстве.** Новый Maestro-флоу `tasks-logbook-note`
(сценарий `TASK-LOG-01`) проходит весь путь на подключённом телефоне: пустой логбук → «+» →
текст → сохранение → возврат → заметка в логбуке.

Порядок: **A → B → C**; A и B самоценны, C требует обоих.

**Ключевые решения пользователя (опрос):** логбук содержит **только структурно прикреплённые**
заметки (`notes.task_id`); заметки с `task://`-ссылкой живут в карточке «Связанное» и в логбук
не попадают. Заметка **не создаётся**, пока пользователь её не сохранил: пустых «(untitled)»-
строк в логбуке не бывает.

**Ключевые технические решения:** привязка живёт **в черновике** (`Editing.taskId`), а не в
отдельном поле VM; у редактора заметок **один** метод записи на оба пути (автосейв и явный
Save); id заметки генерирует вызывающий (редактор) и передаёт в репозиторий — как требует
домашняя модель «create»; инвариант «структурные поля не трогаются» обеспечивается **самим
выбором примитивы**, а не списком полей в Kotlin.

---

## 1. Диагноз

### 1.1 Цепочка (текущее поведение)

1. `LogbookSection.kt` → «+» → `TasksNavigator.openCreateNote(taskId)` →
   `NotesGraph(NotesStartRoute.EditorForTask(taskId))` — навигация исправна.
2. `NoteEditorScreen.kt:87` → `viewModel.createNoteForTask(route.taskId)`.
3. `NoteEditor.kt:231-237` → `createNoteForTask` **сразу пишет заметку в БД**
   (`repo.createForTask(taskId, title = "", …)`), затем `cachedNote = note`, затем
   `createNote(preExistingId = note.id.value)` → черновик с `isNew = true`.
4. Пользователь пишет → автосейв (`NoteEditor.kt:101-124`) или Save (`:251-289`) →
   ветка «новая заметка» → **`repo.createWithContent(...)`**.
5. `NotesRepositoryImpl.createWithContent` (`:192-230`) → `noteDao.upsert(NoteEntity(...))`
   (`:201`) — в сущности **нет** `taskId`, а Room `@Upsert` пишет **все** колонки.
   ⇒ `notes.task_id` обнуляется.
6. `watchForTask` (`NotesRepositoryImpl.kt:476-478`) фильтрует по `task_id` → заметка исчезает.

### 1.2 Это не «случайный баг», а нарушение домашней модели записи

Скилл `singularity-todo-write-pipeline` формулирует это буквально:

> «Room `@Upsert` is a full-row REPLACE: any column not explicitly set takes its Kotlin
> default. This makes three distinct operations necessary.»

и делит запись на `create` (полная строка, caller-supplied entity) / `edit` (read-modify-write) /
`apply` (remote upsert). Здесь редактор применил **`create` к уже существующей строке** — это и
есть запрещённый случай: `task_id` не был передан, поэтому получил Kotlin-дефолт `null`.

Собственные документы репозитория описывали правильное поведение, и именно логбук его нарушил:

| Документ | Что обещает | Что в коде |
|---|---|---|
| ADR `2026-09-22-noteeditor-refactor.md` | «`createNote()` … opens `Editing(isNew=true)` and **the first successful `save()`** triggers `repo.createWithContent`» | так и есть для обычных заметок |
| ADR `2026-10-01-notes-task-logbook-substrate.md` | «`NoteEditor` … when present the editor pre-fills the task link and uses `createForTask` **on save**» | сделано **on open** — и потом перезаписано |
| KDoc `Editing.isNew` (`Ids.kt:172`) | «True until the note is persisted for the first time (**create-on-first-save**)» | для задачи заметка персистится до первого сохранения |
| KDoc `NoteEditor.kt:56-59` | «Autosave uses `NotesRepository.update` via `buildPersistedNote` … preserving every field: … **taskId** …» | функции `buildPersistedNote` **не существует**, `cachedNote` **не читается** |

Вывод: регрессию внёс сам фич-логбук (2026-10-01) — реализация разошлась с собственным ADR,
а KDoc редактора описал несуществующий механизм, поэтому ревью не увидел расхождения.

### 1.3 Что сломано и что НЕ сломано

Сломано: одна вещь — путь записи прикреплённой заметки (шаги 3-5 выше) и три места, которые
этот путь разъезжаются (см. §5.3).

НЕ сломано (чинить не надо): чтение (`watchForTask` → `watchByTaskForUser`, фильтр по
`task_id`), коллектор `TaskLogbookCollector` (объединяет заметки и time-записи, сортирует по
дате убыв.), UI карточки `LogbookSection`, навигация, DAO-запросы, схема Room (`task_id` уже
есть, `SCHEMA_VERSION` не меняется).

---

## 2. Границы

**В объёме:** запись прикреплённой заметки; правило «нет заметки без сохранения»; единый
метод записи в редакторе; удаление мёртвого кода и правка неверных KDoc; 4 `testTag`а;
Maestro-флоу + сценарная спека; 3 юнит-теста; ADR + спека.

**Вне объёма:** union-запрос `task_id ∪ outgoing_links` (решение пользователя №1); заметки со
ссылками в логбуке; дизайн карточки логбука; схема Room и миграции; desktop-порт логбука
(секция Android-only, `extraSections = null`); восстановление привязок, потерянных в старых
сборках (исторические данные не различимы); iOS (его нет); push и PR (не запрошены).

---

## 3. Ограничения и права

**Планирование:** только чтение (репозиторий, `adb devices`, `./sg gates`, `git log`/`ls-files`)
и запись **ровно** этого файла. Git-состояние не менялось, файлы репозитория не создавались,
устройство не трогалось.

**Реализация (нужно по запросу пользователя «почини, напиши тесты, прогони на телефоне»):**
запись в файлы репозитория; сборка и установка APK на телефон `0O65321F32204DD5` (realme
RMX3938, Android 15, 720×1600 @320dpi): `./gw :androidApp:installDebug
-Pandroid.device=0O65321F32204DD5`; запуск Maestro (2.10.0 в PATH) на этом устройстве.
**Не коммитить и не пушить без отдельной команды.**

**Правила репозитория:** Gradle только через `./gw`; два Gradle-вызова одновременно не
запускать; долгий запуск — одна фоновая задача, лог в уникальный файл
(`build/diagnostics/<что>-<метка>.log`), ожидание одним `./wait-for <лог> <паттерн>`; перед
работой с телефоном — `./sg device-holders` (на момент планирования устройство свободно);
`TMPDIR` — приватный каталог; никаких `runBlocking` в init, pass-through use case, `java.io.File`
в `commonMain`; Koin — pure DSL.

---

## 4. Скиллы, которые нужны в задаче

Порядок загрузки — как их применять в этом конкретном PR.

| Скилл | Зачем именно здесь |
|---|---|
| `singularity-todo-write-pipeline` | **Ключевой.** Даёт домашние примитивы `create`/`edit`/`apply` и правило «`@Upsert` — полная замена строки». Именно оно объясняет баг и диктует фикс. |
| `singularity-todo-repository-architecture` | Инварианты репозитория (сигнатуры портов, `Result<T>`, отсутствие pass-through). |
| `singularity-todo-mvi-framework` + `singularity-todo-vm-intent-pattern` | Черновик (`Editing`), `isNew`/`isDirty`, sealed Intent, один writer. |
| `singularity-todo-coroutine-scopes` | Владение scope в VM, отмена автосейва, «долгий запуск — одна фоновая задача». |
| `singularity-todo-testable-vm` + `singularity-todo-test-helpers` | Харнесс VM-тестов, виртуальное время для дебаунса 500 мс, fakes вместо моков. |
| `singularity-todo-test-tag-strategy` | Куда и как объявлять `testTag` (`TestTags.kt` — единственный источник), как их классифицирует золотой каталог. |
| `singularity-todo-maestro-flows` | Правила написания флоу, фикстуры (`seed-task.yaml`), гонка `RUN_ID`, уборка, запуск на устройстве. |
| `singularity-todo-adb-workflow` | Работа с подключённым телефоном: сборка/установка, разбор логов, скриншоты. |
| `singularity-todo-build-and-gates` | `./gw`, порядок гейтов, что зелёное意味着 «пройдено». |
| `singularity-todo-detekt-workflow` | detekt/ktlint по изменённым файлам, baseline. |
| `singularity-todo-decisions-workflow` | Формат ADR, `status: open` для нового, обновление DIGEST. |
| `singularity-todo-openspec-workflow` | Решение «change или ADR» — здесь ADR, без OpenSpec (см. D-12). |
| `singularity-todo-unwired-surface-audit` | Финальная проверка «фича не осталась неподключённой» — ровно тот класс дефекта, который мы чиним. |
| `speckit-specify` → `speckit-plan` → `speckit-tasks` | Спека, план, задачи — артефакты `/speckit-specify`/`/speckit-plan`. |
| `code-review` (по желанию) | Ревью диффа перед фиксацией. |

**Явно НЕ нужны** (и почему): `singularity-todo-room-migration` — схема не меняется, `task_id`
уже есть; `singularity-todo-koin-di` / `singularity-todo-di-graph-testing` — биндинги не
меняются; `singularity-todo-kmp-reorderable`, `singularity-todo-multi-profile` — не в зоне
изменений; `debugging-investigation` — диагноз уже установлен чтением кода.

---

## 5. Целевой дизайн

### 5.1 Порт `NotesRepository` — один параметр, без новых методов

```kotlin
/** Creates a note with content (autosave path). Returns the saved note. */
suspend fun createWithContent(id: NoteId, title: String, bodyMarkdown: String, bodyHtml: String): Result<NoteId>

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
```

Поверхность порта растёт на **один параметр**, а не на новый метод: `createWithContent` и
`createForTask` остаются двумя разными намерениями («заметка вообще» / «заметка, прикреплённая
к задаче»), обе реализации уходят в одну приватную функцию репозитория.

KDoc `watchForTask` переписывается с «via wikilinks» на «notes **attached** to a task
(`task_id`)» — по решению пользователя №1 (ADR `2026-10-01` уже сделал `task_id` источником
истины, а KDoc остался от прежней модели).

### 5.2 Репозиторий — привязка пишется только при создании

`NotesRepositoryImpl.createForTask` пишет **переданный** `id` (не свежесгенерированный) и
`task_id = taskId.value`; `createWithContent` и `createForTask` делят приватную
`createInternal(entity)`, которая строит **полную** сущность (userId, kind, createdAt,
sortOrder, outgoingLinks, taskId) и делает `noteDao.upsert(entity)` — как требует примитива
`create`.

Никакого read-modify-write-слияния и никакого списка «структурных полей» в Kotlin: структурные
колонки не трогает **точечный UPDATE** `updateContentForUser`, которым идёт любое последующее
сохранение (`NotesRepositoryImpl.kt:258-277` → `Daos.kt:647-659`; он меняет только `title`,
`body_markdown`, `body_html`, `word_count`, `char_count`, `updated_at`).

### 5.3 Редактор — один writer, оба пути

Сейчас в `NoteEditor` **три** расходящихся места записи:

| Место | Что делает | Проблема |
|---|---|---|
| автосейв-лямбда `:101-124` | create/update | **не** пишет `outgoing_links`; `launch` + возврат немедленно → базовый класс считает сохранение успешным **до** записи; ошибка автосейва только логируется |
| `persist` `:251-289` | create/update + `setOutgoingLinks` | своя копия ветки create/update |
| `onAutosaved` `:303-309` | снимает `isDirty`/`isNew` | решает «новая ли заметка» по своим правилам |

Целевой вид:

```kotlin
// Один writer — и автосейв, и явный Save идут через него.
internal suspend fun AutosaveContext.writeNote(draft: Editing): Either<AppError, Unit> = try {
    val noteId = NoteId.fromString(draft.id)
    val result = if (draft.isNew) {
        val taskId = draft.taskId
        if (taskId != null) {
            repo.createForTask(noteId, taskId, draft.title.value, bodyMarkdown = "", bodyHtml = draft.html.value)
        } else {
            repo.createWithContent(noteId, draft.title.value, bodyMarkdown = "", bodyHtml = draft.html.value)
        }
    } else {
        repo.updateContent(noteId, draft.title.value, bodyMarkdown = "", bodyHtml = draft.html.value)
    }
    result.getOrThrow()
    // Ссылки — часть содержимого заметки; без них викилинки мертвы.
    repo.setOutgoingLinks(noteId, NoteContentMapper.outgoingLinkUrls(draft.html.value)).getOrThrow()
    Either.Right(Unit)
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Either.Left(AppError.Persistence(e.toMessage(), code = "notes.editor.persist_failed"))
}
```

и в конструкторе:

```kotlin
autosave = { draft ->
    // Незатронутый черновик — ещё не заметка: запись здесь оставила бы пустую
    // строку тому, кто открыл редактор и ушёл.
    if (draft.isNew && !draft.isDirty) return@autosave
    autosaveCtx.writeNote(draft).getOrThrow()   // Left → исключение → SaveFailed
},
```

```kotlin
override suspend fun persist(draft: Editing): Either<AppError, Unit> = autosaveContext.writeNote(draft)

override suspend fun onAutosaved(current: Editing) {
    // Ничего не записано — черновик остаётся «новым» до первого сохранения.
    if (current.isNew && !current.isDirty) return
    updateDraft { it.copy(isDirty = false, isNew = false) }
}
```

Что это даёт сверх фикса бага: ошибка автосейва больше не молчит (бросается → базовый класс →
`onAutosaveError` → `SaveFailed`), ссылки пишутся при каждом сохранении, а не только при
явном Save, и «новая заметка» определяется **одним** местом.

### 5.4 Черновик — носитель привязки

`Editing` (`feature/notes/Ids.kt:167`) получает одно поле:

```kotlin
data class Editing(
    val id: String,
    val title: Revisioned<String>,
    val html: Revisioned<String>,
    val isDirty: Boolean = false,
    /** True until the note is persisted for the first time (create-on-first-save). */
    val isNew: Boolean = false,
    /** The task this note belongs to, when it was opened from a task's logbook. */
    val taskId: TaskId? = null,
) : EditorState
```

Привязка в черновике, а не в отдельном поле VM: черновик — это состояние редактора, и writer
читает именно его; один источник истины вместо двух.

`createNoteForTask(taskId)` становится без побочных эффектов:

```kotlin
/** Opens an empty note that belongs to [taskId]. Nothing is written yet. */
fun createNoteForTask(taskId: TaskId) {
    createNote()
    updateDraft { it.copy(taskId = taskId) }
}
```

Параметр `preExistingId` у `createNote` становится мёртвым → удаляется. Поле `cachedNote`
(записывается в трёх местах, не читается ни разу) → удаляется. KDoc `:56-59`, `:200-206`,
`:225-230` → переписываются под фактический механизм.

### 5.5 Почему «незатронутый черновик не пишется» — это нужно, а не опция

Автосейв срабатывает не по нажатию кнопки, а по **изменению черновика**: базовый класс
собирает `draftState.current.drop(1).debounce(500)`, а `open(draft)` — это тоже изменение
черновика. Значит открытие редактора само по себе через 500 мс создаёт заметку (пустую).
Без явного правила «незатронутый черновик не записывается» решение пользователя №2 («пока не
сохранил — заметки нет») не выполняется: в логбуке появилась бы пустая «(untitled)»-строка.

Побочный эффект правила — **тот же класс дефекта, что и исходный баг**: мусорная строка от
незавершённого действия. Правило применяется ко всем новым заметкам в редакторе (логбук,
обычная новая заметка), а не только к логбуку: одна формулировка вместо двух. Влияние
 ограничено: заметки создаются quick-add'ом (с заголовком) — существующие флоу
(`Maestro/flows/notes/create-note.yaml`) и desktop-тесты (`NotesFlowTest`,
`NoteEditorFieldFlowTest`) не зависят от «пустая заметка появляется при открытии редактора»;
юнит-тестов с такой зависимостью в `shared/src/jvmTest` нет (проверено).

---

## 6. Читаемость и индустриальные практики

### 6.1 Оценка читаемости предложенного кода

| Аспект | Было | Стало | Вердикт |
|---|---|---|---|
| Мест записи в редакторе | 3, расходящиеся (автосейв / persist / onAutosaved) | 1 (`writeNote`) + два тонких вызова | **лучше** — расхождение, породившее баг, невозможно |
| Имя метода vs семантика | `createWithContent`, который на деле перезаписывает строку | `createWithContent`/`createForTask` — «create» честно означает создание; правка содержимого — `updateContent` | **лучше** |
| Инвариант «структура не трогается» | не был сформулирован; защищался случайным `UPDATE` | следует из выбора примитивы (create один раз → edit точечный) | **лучше** — инвариант в структуре, а не в дисциплине |
| Состояние | `cachedNote` (write-only) + `preExistingId` (мёртвый) + `pendingTaskId` (в VM) | `Editing.taskId` — одно поле в одном месте | **лучше** |
| Документация | KDoc описывал несуществующий `buildPersistedNote` | KDoc описывает реальный механизм | **лучше** — именно ложный KDoc пропустил баг |
| Ошибка автосейва | логируется и теряется | `SaveFailed` пользователю | **лучше** |

### 6.2 Сравнение с индустриальными практиками

| Практика | Как выглядит в этом плане |
|---|---|
| **Draft-first**: сущность появляется в хранилище, когда пользователь её сохранил (Google Docs, Notion, Mail drafts) | ✔ заметка создаётся на первом сохранении, а не при открытии редактора |
| **Partial update**: UPDATE перечисляет только свои колонки (Postgres `ON CONFLICT DO UPDATE SET`, Hibernate `@DynamicUpdate`, EF Core) | ✔ `updateContentForUser` меняет только колонки содержимого; `task_id` в SET-списке отсутствует |
| **Make illegal states unrepresentable**: инвариант в типе/SQL, а не в соглашении | ✔ «создать поверх существующей строки» невозможно: create-примитива вызывается ровно один раз, для нового id |
| **Caller-owned identity**: id сущности создаёт вызывающий до вставки (JPA `@GeneratedValue` до `persist`) | ✔ `createForTask(id = …)` — id черновика, навигации и строки совпадают |
| **Single write path**: один writer на состояние | ✔ `writeNote` обслуживает автосейв и Save |
| **No silent failure**: ошибка сохранения видна пользователю | ✔ бросок → `onAutosaveError` → `SaveFailed` |
| **Honest naming**: имя метода описывает семантику | ✔ `create*` = создание, `updateContent` = правка содержимого |
| **Real-device E2E**: сценарий пользователя на настоящем устройстве | ✔ `TASK-LOG-01` на подключённом телефоне |
| **Regression test pins the invariant, not the implementation** | ✔ T1–T3 проверяют «привязка выживает», а не «какой метод вызван» |

### 6.3 Что в плане осталось «вкусным»

Честно: реализация фиксирует **три** вещи в редакторе заметок (единый writer, пропуск
незатронутого черновика, видимая ошибка автосейва), а не одну. Это осознанно: все три —
стороны одного инварианта «редактор создаёт заметку ровно один раз и только с тем, что
пользователь написал». Разбивать их нельзя: без единого writer'а баг вернётся, а без пропуска
незатронутого черновика решение пользователя №2 не выполняется.

---

## 7. Что изменилось после self-review (гриль плана)

| # | Было в плане v1 | Стало | Почему |
|---|---|---|---|
| 1 | D-03: read-modify-write слияние структурных полей внутри `createWithContent` | **выброшено** | Противоречит домашней модели (`create` = полная строка) и заводит список полей в Kotlin — ловушка维护: добавили колонку в `NoteEntity` → забыли в списке → баг вернулся |
| 2 | `pendingTaskId` как поле VM | `Editing.taskId` в черновике | Один источник истины; writer читает черновик |
| 3 | Риск «привязка не переживёт пересоздание VM» | **риск снят** | У редактора заметок нет draft-store (`restore = { null }`, `NoteEditor.kt:125`) — восстанавливать нечего; факт вместо допущения |
| 4 | «Create-on-save» считалось достаточным для решения №2 | Добавлено правило «незатронутый черновик не пишется» + гард в `onAutosaved` | Автосейв срабатывает через 500 мс после **открытия** редактора (debounce по изменению черновика) — без правила пустая заметка создавалась бы без ввода текста |
| 5 | «Один общий метод записи» (общее слово) | Уточнено: в редакторе было **две** копии ветки create/update, автосейв не писал `outgoing_links`, `launch` делал «успех до записи», ошибка автосейва терялась | Точная находка; объединение чинит и дублирование, и «успех до записи» |
| 6 | `createNote(preExistingId)` не упомянут | Параметр удаляется (после D-01 не остаётся вызывающих) | Чистота, минус мёртвый параметр |
| 7 | Список KDoc-правок | Расширен: `:56-59` (обещает `buildPersistedNote`), `:200-206` («saved to the DB immediately»), `:225-230` | Именно эти KDoc'и оправдывали баг |
| 8 | Скиллы не указаны | §4 «Скиллы, которые нужны в задаче» + список явно ненужных | Прямая просьба пользователя |
| 9 | Сравнения с индустрией не было | §6 «Читаемость и индустриальные практики» | Прямая просьба пользователя |
| 10 | Диагноз: «баг в записи» | Диагноз sharpened: регрессия внесена фичей логбука (ADR `2026-10-01` обещал `createForTask` **on save**, реализация сделала **on open**); KDoc `Editing.isNew` уже описывал create-on-first-save | Точность: фикс — восстановление задокументированного намерения, а не новое изобретение |

---

## 8. Конституция

| Принцип | Статус | Доказательство |
|---|---|---|
| I. KMP Architecture | PASS | Правка в `shared/src/commonMain`; новых `expect`/`actual` нет; логбук Android-only по существу |
| II. ADR Before Code | PASS с обязательством | ADR `docs/decisions/2026-10-25-task-logbook-note-attachment.md`: контракт порта, примитивы записи, «создание на первом сохранении», отвергнутое слияние. Рождается `status: open` |
| III. Validate Early and Often | PASS | `singularity-adr validate` после ADR |
| IV. Immutable Historical Record | PASS | ADR `2026-09-22`, `2026-10-01`, `2026-10-02` не правятся — новый ADR их цитирует |
| V. CI Gates | PASS | Ветка (если будет пушиться) — без `--no-verify` |
| VI. MVI ViewModels | PASS | `NoteEditor` остаётся MVI-VM; новых публичных Intent нет; `runBlocking` не появляется |
| VII. Test Fakes | PASS | `FakeNotesRepository` зеркалит контракт; дебаунс — виртуальное время `runTest` |
| VIII. OpenSpec Workflow | PASS с решением | OpenSpec-изменение **не** заводим: конституция относит bug fixes к случаям без него; гейты проверяют только существующие `openspec/changes/` (D-12) |

**Pre-Phase-0 вердикт: PASS.** Complexity Tracking пуста. Пост-дизайн: контракт между слоями
изменился (сигнатура порта) — покрыт контрактом `contracts/note-attachment-write.md` и
репозиторным тестом T1; новых сквозных concerns нет. **Post-design вердикт: PASS.**

---

## 9. Структура проекта

```text
specs/015-fix-task-logbook/
├── spec.md                  # спецификация (/speckit-specify) — §0
├── plan.md                  # этот документ (/speckit-plan)
├── research.md              # Phase 0 — 12 решений D-01…D-12
├── data-model.md            # Phase 1
├── quickstart.md            # Phase 1
├── contracts/
│   ├── note-attachment-write.md   # контракт записи прикреплённой заметки
│   └── maestro-logbook-flow.md    # контракт E2E-флоу и сценария
├── checklists/requirements.md     # чек-лист качества спеки
└── tasks.md                 # Phase 2 (/speckit-tasks) — не в этой команде

shared/src/commonMain/kotlin/com/singularity/todo/
├── feature/notes/
│   ├── domain/port/NotesRepository.kt        # CHANGED: createForTask(id, taskId, …) + KDoc watchForTask
│   ├── data/NotesRepositoryImpl.kt           # CHANGED: createForTask пишет переданный id; общая createInternal
│   ├── presentation/viewmodel/NoteEditor.kt  # CHANGED: writeNote, пропуск незатронутого черновика,
│   │                                         #           Editing.taskId, удаление cachedNote и preExistingId
│   ├── Ids.kt                                # CHANGED: Editing.taskId + KDoc Note.taskId
│   └── presentation/screen/NoteEditorScreen.kt   # CHECKED: вызов createNoteForTask остаётся
├── feature/tasks/presentation/components/detail/LogbookSection.kt  # CHANGED: 4 testTag'а
├── core/ui/TestTags.kt                       # CHANGED: LOGBOOK_* + logbookNoteByTitle
└── test/fakes/FakeRepositories.kt            # CHANGED: фейк зеркалит контракт

shared/src/jvmTest/kotlin/com/singularity/todo/
├── core/ui/TagsMd.kt                         # CHANGED: правило "LOGBOOK_" → Tasks + dynamicMeta
└── feature/notes/NotesRepositoryLogbookAttachmentTest.kt  # NEW: T1 (реальный SQLite, slow)

shared/src/commonTest/kotlin/com/singularity/todo/
├── feature/notes/presentation/viewmodel/NoteEditorLogbookAttachmentTest.kt  # NEW: T2 (fast)
└── feature/tasks/presentation/viewmodel/slot/TaskLogbookCollectorTest.kt  # NEW: T3 (fast)

Maestro/
├── flows/tasks/logbook/TASK-LOG-01.yaml     # NEW: E2E-флоу «заметка остаётся прикреплённой»
├── flows/tasks/logbook/TASK-LOG-02.yaml     # NEW: E2E-флоу «заброшенный редактор не оставляет заметки»
└── TAGS.md                                   # CHANGED: перегенерированный золотой раздел

config/docs/
├── maestro-flows.txt                         # CHANGED: путь новых флоу
└── maestro-element-kinds.txt                 # CHANGED: новые id и kind'ы
scripts/src/traceability/scenarios/tasks/logbook/TASK-LOG-01.yaml  # NEW: сценарная спека
scripts/src/traceability/scenarios/tasks/logbook/TASK-LOG-02.yaml  # NEW: сценарная спека
docs/testing/coverage-matrix.md               # CHANGED: перегенерирован
docs/decisions/2026-10-25-task-logbook-note-attachment.md         # NEW: ADR (status: open)
```

**Structure decision**: разметка KMP не меняется. Новых экранов, use case и пакетов нет.
Структурное правило фичи: **структурная привязка `notes.task_id` — единственный источник
истины; привязка пишется один раз, при создании заметки, и ни один путь записи содержимого
её не трогает.**

---

## §0. Шаг 0 — спека (`/speckit-specify`)

Каталог `specs/015-fix-task-logbook/` (нумерация sequential, максимум сейчас `014`).
`.specify/feature.json` → `{"feature_directory": "specs/015-fix-task-logbook"}` (в `.gitignore`).
Хуков нет: `before_specify`/`after_specify` в `.specify/extensions.yml` не зарегистрированы.

**`specs/015-fix-task-logbook/spec.md`**:

```markdown
# Feature Specification: Task Logbook Attachment

**Feature Branch**: `015-fix-task-logbook`
**Created**: 2026-10-25
**Status**: Draft

**Input**: User description: "Почини logbook. Сейчас он как будто вообще не работает. Напиши/обнови на это тесты в Maestro, прогони и на подключенном телефоне."

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Write a note from the task logbook (Priority: P1)

A user opens a task, finds the Logbook section, taps "+", writes a note and leaves.
Coming back to the task, the note is there, with the title they wrote.

**Why this priority**: The logbook is the only place a user can attach a note to a task.
Today the attachment silently disappears on the first save, so the feature is not usable
at all — every other scenario is downstream of it.

**Independent Test**: Create one note from the logbook, leave, reopen the task.

**Acceptance Scenarios**:

1. **Given** a task with an empty logbook, **When** the user taps "+", writes a title
   and saves, **Then** the logbook shows that note under the task's title.
2. **Given** a task with an empty logbook, **When** the user taps "+" and leaves without
   writing anything, **Then** the logbook is still empty — an abandoned note leaves
   nothing behind.
3. **Given** a note attached to a task, **When** the user edits it again later, **Then**
   the note is still attached and the logbook still shows it.

### User Story 2 - Trust the logbook's contents (Priority: P2)

A user reads the logbook of one task and expects to see exactly the notes that belong to
that task.

**Why this priority**: A logbook that shows notes from another task, or hides notes the
user attached, is worse than no logbook — it is confidently wrong.

**Independent Test**: Attach a note to one task, look at another task's logbook.

**Acceptance Scenarios**:

1. **Given** a note attached to task A, **When** the user opens task B's logbook, **Then**
   task B's logbook does not show that note.
2. **Given** a note whose body merely links to a task, **When** the user opens that task's
   logbook, **Then** the note is not in the logbook — it stays in the task's "Linked" card.

### Edge Cases

- The task was deleted elsewhere: the note write fails visibly; no half-written row is left.
- The app is killed while the editor is open: nothing was written, so nothing is left behind.
- A note with an empty title: it is attached and visible, titled by its content.

## Scenario Matrix

```yaml
scenarios:
  - id: TASK-LOG-01
    title: A note written from the task logbook stays attached to the task
    area: feature.tasks
    priority: P1
    status: confirmed
    targets: [android]
    group: logbook
```

## Requirements

### Functional Requirements

- FR-001: A note written from a task's logbook is attached to that task and stays attached
  across every later edit of that note.
- FR-002: Opening the logbook's "+" action creates nothing until the user saves.
- FR-003: The first save of a logbook note creates the note already attached to the task.
- FR-004: The logbook of a task shows exactly the notes attached to that task and nothing else.
- FR-005: A note that only links to a task (a `task://` link in its body) does not appear in
  that task's logbook.
- FR-006: The attachment survives the app being closed and reopened.
- FR-007: A failed write of a logbook note is reported to the user and leaves no partially
  written note behind.

### Success Criteria

- SC-001: A note written from the logbook is visible in that task's logbook every time the
  user returns to the task, in 100% of runs.
- SC-002: Opening the logbook's "+" and leaving without writing produces zero notes in 100%
  of runs.
- SC-003: No note from another task ever appears in a task's logbook.
- SC-004: An end-to-end flow on a physical Android device creates a note from the logbook
  and observes it in the logbook, passing on every run.

### Assumptions

- The logbook keeps its current shape (a section on the task detail listing notes and time
  entries) and gains no new user-facing controls.
- The task detail screen's Logbook section is Android-only in this build; the desktop build
  renders that slot empty and is out of scope.

## Out of Scope

- Showing notes that merely link to a task in the logbook — they stay in "Linked".
- Any change to the logbook's visual design, ordering or columns.
- Restoring attachments that older builds already lost: a note whose `task_id` was cleared is
  an ordinary note from now on.
- Bringing the logbook to the desktop build.

## Risks

- A note that lost its attachment in an earlier build cannot be recognised as a former
  logbook note, so the fix does not restore old attachments.
```

**`specs/015-fix-task-logbook/checklists/requirements.md`** — чек-лист из скилла
`/speckit-specify` (16 пунктов), все отмечены; в Notes — что закрыто решениями пользователя
(состав логбука, пустая заметка).

---

## Phase 0 — Research (`research.md`)

**D-01 — Прикреплённая заметка создаётся на первом сохранении.**
Decision: «+» открывает редактор без записи в БД; заметка создаётся первым сохранением, уже с
`task_id`. Rationale: пустая заметка не должна появляться (решение пользователя №2); исчезает
расхождение между «создали строку» и «сохранили содержимое», из которого вырос баг.
Alternatives: (а) создавать при открытии и чинить только запись — мусорные «(untitled)»-строки;
(б) создавать при открытии и удалять при выходе без текста — лишний путь удаления с гонкой.

**D-02 — id заметки принадлежит вызывающему.**
Decision: `createForTask(id: NoteId, taskId: TaskId, …)`; репозиторий пишет переданный id.
Rationale: у черновика уже есть id (`idGen.next()`), он же уходит в бэк-стек и в последующие
`UPDATE`; при расхождении id точечный UPDATE не найдёт строку (`updateContent` требует
`rows > 0`). Это же требование домашней примитивы `create` («ID generated by caller»).
Alternatives: (а) редактор подхватывает id, возвращённый репозиторием, — осиротевшая запись
черновик-хранилища под старым id; (б) новый порт-метод `createForTaskWithId` — дублирование.

**D-03 — Инвариант живёт в выборе примитивы, а не в списке полей.**
Decision: привязка пишется один раз, при создании; все последующие сохранения — точечный
UPDATE, перечисляющий только колонки содержимого. Слияние структурных полей в Kotlin **не**
вводится.
Rationale: домашняя модель (`singularity-todo-write-pipeline`) делит запись на `create`
(полная строка) и `edit` (read-modify-write); баг — это `create`, применённый к существующей
строке. Список полей в Kotlin — ловушка: новая колонка `NoteEntity` забудется в списке, и баг
вернётся. Alternatives: (а) read-modify-write слияние в `createWithContent` — отвергнуто (см.
таблицу §7); (б) DAO-уровень `ON CONFLICT DO UPDATE SET` — лишний сырой SQL и вопрос
совместимости SQLite на API 26+.

**D-04 — Один writer на оба пути сохранения.**
Decision: `AutosaveContext.writeNote(draft)` обслуживает и автосейв, и `persist`.
Rationale: расхождение двух копий ветки create/update и есть тот класс дефекта, который чиним;
плюс автосейв не писал `outgoing_links` и «успевал» до записи. Alternatives: править обе копии —
регрессия вернётся при следующей правке.

**D-05 — Логбук = только структурно прикреплённые заметки.**
Decision: в логбуке только заметки с `notes.task_id`; union по `outgoing_links` не делаем; KDoc
`watchForTask` переписывается. Rationale: решение пользователя №1; `task_id` — источник
истины (ADR `2026-10-01`), ссылки уже показываются в «Связанном».
Alternatives: union `task_id ∪ outgoing_links` — размывает семантику, требует правки DAO и его
тестов, и меняет поведение seeded-фикстур (`seed.yaml … linkTo=` пишет только `outgoing_links`).

**D-06 — Точечный UPDATE вместо полной перезаписи строки.**
Decision: правка существующей заметки идёт через `updateContent` (ownership-scoped UPDATE,
`Daos.kt:647-659`). Вариант «собрать полную заметку и записать `upsert`» (KDoc-обещание
`buildPersistedNote`) отвергнут. Rationale: `cachedNote` — устаревший снимок; полная
перезапись вернула бы в БД поля, уже обновлённые rewrite-watcher'ом, и затерела бы
sync-метаданные (`hlc`, `server_version`). Alternatives: `repo.update(mergedNote)` — тот же
риск; «обновлять `cachedNote` перед каждой записью» — та же гонка, сложнее.

**D-07 — Убрать мёртвое состояние и починить документацию.**
Decision: удалить `cachedNote` (`NoteEditor.kt:149` — пишется в трёх местах, не читается) и
параметр `preExistingId`; переписать KDoc `:56-59` (обещает несуществующий `buildPersistedNote`),
`:200-206` («saved to the DB immediately»), `:225-230`; поправить KDoc `Note.taskId`
(`Ids.kt:111-118` — обещает запись `task://`-токена, чего уже нет после ADR `2026-10-02`).
Rationale: расхождение «документация ↔ код» пропустило баг в релиз; оставлять его — значит
оставлять мину на следующий вызов. Alternatives: оставить «на будущее» — оно уже один раз
солгало.

**D-08 — Незатронутый черновик не записывается.**
Decision: автосейв пропускает черновик, который `isNew && !isDirty`; `onAutosaved` в этом
случае не снимает флаги. Rationale: автосейв — debounce по *изменениям* черновика, а `open(draft)`
— тоже изменение, поэтому открытие редактора создавало бы пустую заметку через 500 мс; решение
пользователя №2 требует явного правила. Alternatives: (а) править базовый `DraftMviViewModel`
(фильтровать автосейв по `isDirty` для всех редакторов) — расширяет blast radius на редактор
задач; (б) удалять заметку при выходе — пользователь отверг этот вариант в опросе.

**D-09 — Четыре testTag'а, категория «Tasks» в золотом каталоге.**
Decision: `LOGBOOK_SECTION`, `LOGBOOK_EMPTY`, `LOGBOOK_ADD_NOTE_BUTTON`, динамический
`logbookNoteByTitle(title)`; в `TagsMd.kt` — правило `"LOGBOOK_" → "Tasks"` и строка
`dynamicMeta`; `Maestro/TAGS.md` перегенерируется (`./gw :shared:jvmTest -PupdateGoldens=true`).
Rationale: без тегов флоу не может дождаться раздела и адресовать строку; «Tasks» вместо новой
секции «Logbook» — чтобы не трогать `staticSubsections`/`dynamicSubsections`.
Alternatives: адресовать по тексту — хрупко (локализация, дубли строк) и противоречит
конвенции «контролы — по id».

**D-10 — E2E-флоу на реальном устройстве, сценарий `TASK-LOG-01`.**
Decision: `Maestro/flows/tasks/19-task-logbook-note.yaml`, теги `regression`, `tasks`,
`scenario:TASK-LOG-01`; `targets: [android]`; фикстура — штатный `helpers/seed-task.yaml`
(«Buy milk»), заметка создаётся **из логбука**. Rationale: путь «создать из логбука» —
единственный сломанный; засеять готовую заметку значило бы протестировать не то (seed-linked
заметка пишет только `outgoing_links` — см. D-05). Alternatives: desktop-tier Compose-тест того
же пути — дешевле, но не проверяет навигацию между графами.

**D-11 — ADR обязателен: меняется контракт слоя данных.**
Decision: `docs/decisions/2026-10-25-task-logbook-note-attachment.md`, `status: open`, разделы
Context / Decision / Rationale / Consequences / Links; после — `./scripts/refresh-decisions-digest.sh`.
Rationale: конституция требует ADR при изменении паттерна слоя данных; меняются сигнатура порта
и семантика записи, а решение «почему не полная перезапись строки» иначе нигде не записано.
Alternatives: обойтись как «bug fix» — не подходит: поведение меняется.

**D-12 — OpenSpec-изменение не заводим.**
Decision: работа живёт в `specs/015-fix-task-logbook/` + ADR; каталога `openspec/changes/<slug>/`
не создаём. Rationale: конституция относит bug fixes к случаям без OpenSpec; гейты
(`check-issue-registry.py`, `check-review-verdicts.py`) проверяют только существующие каталоги.
Alternatives: завести OpenSpec «на всякий случай» — лишняя церемония и ревью-вердикт, который
никто не просил.

---

## Phase 1 — Design & Contracts

### 1.1 `data-model.md`

**`Note` (существующая; меняется контракт создания)**

| Поле | Тип | Правило |
|---|---|---|
| `id` | `NoteId` | назначается вызывающим (черновиком); `createForTask` обязан записать именно его |
| `userId` | `UserId` | владелец; чтение логбука фильтруется по тому же uid |
| `title` | `String` | может быть пустым |
| `taskId` | `TaskId?` | **структурная привязка**; пишется один раз, при создании; `null` — заметка не прикреплена |
| `createdAt`/`updatedAt` | `Instant` | `createdAt` не переписывается сохранением |
| `kind`, `isPinned`, `pinnedAt`, `color`, `sortOrder`, `parentNoteId`, `outgoingLinks` | — | структурные поля: их не трогает точечный UPDATE |
| `outgoingLinks` | `String` (JSON) | wikilinks; `task://`-токен сюда **не** пишется (ADR `2026-10-02`) |

**`Editing` (черновик редактора) — новое поле**

| Поле | Тип | Правило |
|---|---|---|
| `id` | `String` | id заметки; до первого сохранения заметки в БД нет |
| `title`, `html` | `Revisioned<String>` | содержимое |
| `isDirty` | `Boolean` | пользователь что-то менял |
| `isNew` | `Boolean` | заметка ещё не в БД (create-on-first-save) |
| `taskId` | `TaskId?` | **новое**: привязка, заданная при открытии из логбука; переносится в заметку при создании |

**Состояния записи логбук-заметки**

```text
   openLogbookEditor(taskId)
            │
            ▼
     [NOT_PERSISTED] ──save/автосейв после правки──▶ createForTask(id, taskId, …) ──▶ [ATTACHED]
            │                                              │                            │
            │                                              └── ошибка ──▶ [FAILED]     │ правка
            └── выход без сохранения ──▶ (нет состояния: ничего не создано)            ▼
                                                                                                 [ATTACHED]
```

Инварианты:
- `NOT_PERSISTED` → в БД нет ни одной строки; логбук пуст;
- переход в `ATTACHED` возможен **только** через `createForTask` (запись, несущая `task_id`);
- из `ATTACHED` сохранения идут через `updateContent` — `task_id` не меняется;
- `FAILED` не оставляет строки (запись — в одной транзакции `unitOfWork.write { }`).

**`LogbookEntry` / `TaskLogbookState`** (без изменений): `NoteEntry` | `TimeEntryRow`;
`Empty` / `Loaded(entries)`; порядок по дате убыв.; источники — `notesRepo.watchForTask(task.id)`
и `timeTrackingRepo.watchEntries(task.id)`, оба по текущему пользователю.

### 1.2 `contracts/note-attachment-write.md`

Форма сигнатуры порта; гарантии; вызывающие; отвергнутые альтернативы. Гарантии:
1. `createForTask(id, taskId, …)` создаёт заметку **с** `task_id = taskId` и **с** `id = id`.
2. Ни один путь записи содержимого не меняет `task_id` существующей строки.
3. `updateContent` меняет только `title`, `body_markdown`, `body_html`, `word_count`,
   `char_count`, `updated_at`.
4. Ошибка записи → `Result.failure` / `Either.Left`; строки не остаётся; ошибка видна
   пользователю (`SaveFailed`).
5. Владелец заметки — текущий пользователь; чтение логбука изолировано по uid.
6. Заметка, прикреплённая к задаче, **не переприкрепляется** другой задачей: смена привязки —
   отдельная операция (вне объёма).

### 1.3 `contracts/maestro-logbook-flow.md`

Что флоу доказывает; какие элементы адресуются (по id) и почему; что считается провалом;
требования к окружению (устройство, APK из этого же дерева, сброс данных); артефакты прогона.

### 1.4 `quickstart.md` — сценарии валидации (без кода реализации)

- **Q1. Привязка выживает (T1).** `./gw :shared:jvmTest --tests "*NotesRepositoryLogbookAttachmentTest*"`
  → привязка переживает точечный UPDATE; заметка чужой задачи не появляется.
- **Q2. Поведение редактора (T2).** `--tests "*NoteEditorLogbookAttachmentTest*"` → до сохранения
  заметок нет; после — заметка прикреплена; после второй правки привязка на месте.
- **Q3. Чтение логбука (T3).** `--tests "*TaskLogbookCollectorTest*"` → пусто / прикреплённая /
  чужая / отмена.
- **Q4. E2E на телефоне.** `./gw :androidApp:installDebug -Pandroid.device=0O65321F32204DD5`,
  затем `maestro test Maestro/flows/tasks/19-task-logbook-note.yaml` → флоу зелёный, скриншот
  `tasks/logbook-note` показывает заметку в карточке логбука.
- **Q5. Регрессия до фикса.** T1–T3 на исходном `HEAD` обязаны падать (иначе они не pinsят баг).
- **Q6. Гейты.** `SKIP_ADB=1 ./check.sh` + перечень §12.

---

## 11. Тесты и критерии приёмки

### T1 — репозиторный (реальный SQLite, тег `slow`)
`shared/src/jvmTest/kotlin/com/singularity/todo/feature/notes/NotesRepositoryLogbookAttachmentTest.kt`
(шаблон — `NotesRepositorySyncTest.kt`: `createSqlDriver()` + временный файл; **нужно завести
строку задачи**, иначе FK `notes.task_id → tasks(id)` не даст вставить заметку):
1. `createForTask(id, taskId, "План", …)` → `watchForTask(taskId)` возвращает ровно эту заметку,
   `taskId` задан;
2. точечный UPDATE-путь (`updateContent`) → заметка **остаётся** в `watchForTask`;
3. `createWithContent` поверх существующей строки → привязка **теряется**: это
   whole-row write, и он же — механизм бага. Инвариант держится на выборе
   примитивы (create ровно один раз → дальше точечный UPDATE), а не на том, что
   create безопасен поверх существующей строки;
4. заметка без привязки в `watchForTask` не появляется; заметка чужой задачи — тоже.

### T2 — VM-тест редактора (тег `fast`)
`shared/src/commonTest/kotlin/com/singularity/todo/feature/notes/presentation/viewmodel/NoteEditorLogbookAttachmentTest.kt`
(харнесс — по образцу `NoteEditorTest.kt`, `FakeNotesRepository`):
1. после `createNoteForTask(taskId)` в репозитории **нет** заметок (решение №2);
2. после правки заголовка и прогона автосейва (`advanceTimeBy` > дебаунса + `runCurrent`) в
   репозитории появляется заметка с `taskId == taskId` и набранным заголовком;
3. после второй правки (ветка UPDATE) `taskId` сохраняется;
4. `onAutosaved` не снимает `isNew` для незатронутого черновика.

### T3 — коллектор логбука (тег `fast`)
`shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/presentation/viewmodel/slot/TaskLogbookCollectorTest.kt`
(файла в дереве нет): пусто → `Empty`; прикреплённая заметка → `Loaded`; чужая задача → не
попадает; заметка только со ссылкой на задачу → не попадает.

### Критерии приёмки
- **AC-1.** Заметка из логбука после первого сохранения видна в логбуке с набранным заголовком.
- **AC-2.** До первого сохранения в логбуке нет ни одной строки.
- **AC-3.** Привязка переживает правки, возврат в задачу, перезапуск приложения.
- **AC-4.** В логбуке нет чужих заметок; заметка только со ссылкой в логбук не попадает.
- **AC-5.** T1–T3 падают на текущем коде и проходят после фикса.
- **AC-6.** Флоу `tasks-logbook-note` зелёный на подключённом телефоне.
- **AC-7.** Все гейты репозитория зелёные.

---

## 12. Гейты и команды

1. `./gw :shared:compileKotlinJvm` — сразу после связной правки кода.
2. `./gw :shared:jvmTest` — юнит-тесты (T1–T3).
3. `just lint-changed` (detekt по изменённым файлам); при сомнениях — `just lint`.
4. Точечные гейты: `python3 scripts/check-maestro-flow-catalogue.py`,
   `scripts/check-test-tags-catalog.py`, `scripts/check-ui-wait-budgets.py`,
   `scripts/ci/validate-maestro-selectors.py`, `scripts/check-maestro-keyboard-commands.py`,
   `scripts/check-maestro-screen-geometry.py`, `python3 -m traceability validate`,
   `just trace-coverage`, `just trace-coverage-check`,
   `python3 scripts/check-traceability-ratchet.py`, `python3 scripts/check-adr-references.py`
   (+ `singularity-adr validate`, если CLI доступен).
5. Полный локальный цикл: `SKIP_ADB=1 ./check.sh`.
6. Сьют гейтов: `python3 scripts/run-gate-tests.py` (код 0 — зелёная).
7. Прогон Maestro на устройстве — после установки APK из этого же дерева.

**Maestro на телефоне.** Основной путь: `just tests::gate-maestro regression 0O65321F32204DD5`
(рецепт сам ставит APK и зовёт `run-maestro`). Fallback, если `run-maestro` не на PATH (на
текущей машине его нет): ручной `maestro test Maestro/flows/tasks/19-task-logbook-note.yaml`
после сброса данных deep link'ом `adb shell am start -a android.intent.action.VIEW -d
"todo-debug://seed?reset=content"`; либо CI-раннер `python3 scripts/run-maestro-e2e.py --apk
<apk> --suite full --shard 1 --shards 1 --serial 0O65321F32204DD5`.

---

## 13. Риски

1. **Навигация после `back`** из редактора может вернуть и на список задач, и на оставшийся
   экран деталей — флоу терпим (`when:`), фактическое поведение подтверждается прогоном.
2. **`run-maestro` не на PATH** — предусмотрен ручной `maestro test` + сброс данных.
3. **Debounce 500 мс** — флоу полагается на явный Save; в T2 тайминг — виртуальное время.
4. **Правило «незатронутый черновик не пишется» меняет поведение и обычных новых заметок**
   (пустая заметка больше не появляется в списке заметок при открытии редактора). Существующие
   флоу и desktop-тесты не зависят от старого поведения (проверено), но это единственное
   изменение за пределами логбука — фиксируется в ADR как побочный эффект.
5. **FK `notes.task_id → tasks(id)`** при `PRAGMA foreign_keys = ON` — T1 обязан завести реальную
   строку задачи, иначе тест упадёт на FK, а не на сути.
6. **Исторические привязки** не восстанавливаются (зафиксировано в `spec.md → Out of Scope`).

**Открытые решения (без дополнительных вопросов):** категория тегов логбука в `Maestro/TAGS.md`
— «Tasks»; коммит и push не выполняются без отдельной команды; ADR рождается в статусе `open`.
