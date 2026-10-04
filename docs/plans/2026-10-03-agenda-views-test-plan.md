# Тест-план: Agenda Views

> Дата: 2026-10-03. Источник: кодовая разведка + план коллеги (原始 `docs-total.xml`).
> Статус: **черновик MR-0** — требует ревью перед MR-1.

## Карта фичи (что есть / чего нет)

### Встроенные presets — 7 фабрик, 6 определений

| Пресет | Фабрика | Секций | UI-вход |
|--------|---------|--------|---------|
| Inbox | `AgendaPresets.Inbox` | 8 (Overdue, Today, Yesterday, Tomorrow, ThisWeek, NextWeek, **ThisMonth**, NoDate) | Bottom nav / sidebar |
| Today | `AgendaPresets.Today` | 2 (Overdue, Today) | Bottom nav (default tab) |
| Upcoming | `AgendaPresets.Upcoming` | 5 (Overdue, Today, Tomorrow, ThisWeek, NextWeek) — **без ThisMonth и NoDate** | Bottom nav |
| byProject | `AgendaPresets.byProject(id)` | 1 (Projects) | ProjectDetail → «See all N tasks» |
| byTag | `AgendaPresets.byTag(id)` | 1 (Tags) | Search → tag chip → AgendaGraph(Tag) |
| byTags | `AgendaPresets.byTags(ids)` | 1 (Tags) | **Нет UI-входа** (byTag доступен, multi-tag — нет) |
| byDateRange | `AgendaPresets.byDateRange(from, to)` | 1 (DateRange) | Calendar → выбрать день |

**Источник:** `AgendaPresets.kt` (подтверждено построчно).

**`Upcoming` — важно:** секции Overdue(0,discard), Today(1,discard), Tomorrow(2,discard), ThisWeek(3), NextWeek(4).
**Нет ThisMonth / NoDate.** Задача T09 (`D+14`) на сиде **не видна** в Upcoming.

---

## Сид AGENDA_SEED

> Используется для всех уровней тестирования. Desktop: `today = 2026-10-14 (ср)`, неделя Пн–Вс.

| ID | Название | dueDate | Приоритет | Теги | Проект | Прочее |
|----|----------|---------|-----------|------|--------|--------|
| T01 | `ov-3d` | D−3 | High | work | Alpha | |
| T02 | `yesterday` | D−1 | — | — | — | |
| T03 | `today-pinned` | D | — | — | — | pinned |
| T04 | `today-done` | D | — | — | — | completed |
| T05 | `tomorrow` | D+1 | — | work, urgent | — | |
| T06 | `this-week` | D+2 (пт 16.10) | — | — | — | |
| T07 | `recurring` | D+2 | — | — | — | recurrenceRule |
| T08 | `next-week` | D+6 (вт 20.10) | — | — | — | |
| T09 | `this-month` | D+14 (28.10) | — | — | — | |
| T10 | `far-future` | D+90 (12.01.2027) | — | — | — | |
| T11 | `no-date` | — | None | — | — | |
| T12 | `blocked` | — | — | — | — | dependsOn T11 |
| T13 | `beta-nodate` | — | — | home | Beta | |
| T14 | `start-only` | — | — | — | — | startDate = D+3 |
| T15 | `sub-of-today` | D | — | — | — | parentTaskId = T03 |
| T16 | `trashed` | D | — | — | — | в корзине |
| T17 | `archived` | D | — | — | — | в архиве |
| T18 | `buy-milk` | D+1 | Low | — | — | |

**Проекты:** Alpha, Beta (дочерний Alpha).
**Теги:** work, urgent, home.
**Профили:** Personal (активный), Work (пустой).

### Ожидаемая раскладка Inbox (по коду, верифицирована)

```
Overdue    { T01, T02 }
Today      { T03, T15, T04 }
Tomorrow   { T05, T18 }
This Week  { T06, T07 }
Next Week  { T08 }
This Month { T09 }              ← catch-all, без discard
No Date    { T11, T12, T13, T14 }
```

**T10, T16, T17 — нигде не видны (корзина/архив/далеко в будущем).**
**T14 (`startDate` без `dueDate`) — в No Date.**
**T04 (completed) — в Today (AgendaEvaluator фильтрует только по selector, completed-статус не исключён по умолчанию).**

---

## Suite A — Встроенные presets

### A0. Guard-тесты (pure, `commonTest`)

- **A0-01** `AgendaPresetsCatalogTest`: все 7 фабрик перечислены; новый preset не остаётся без теста. Падает если preset без записи в матрице A1.
- **A0-02** JSON round-trip каждого preset (`StableJson`): `decode(encode(x)) == x`.
- **A0-03** Имена секций уникальны внутри definition; `order` не повторяются.
- **A0-04** Регрессия ADR 09-30: ни одна задача не встречается дважды (ключ `"${section.name}/${task.id}"`).
- **A0-05** Inbox/Upcoming: все пересекающиеся DateBucket-секции имеют `discard = true`. ThisMonth (Inbox) — без discard (catch-all). NextWeek (Upcoming) — без discard (финальный).
- **A0-06** Секция без selector → `IllegalStateException("Section 'X' has no selector…")`.
- **A0-07** Legacy JSON `{"Tag":{"id":…}}` → `Selector.Tags(setOf(id))`.
- **A0-08** Секция без `id` в JSON → `effectiveId = name.lowercase().replace(Regex("[^a-z0-9_]"), "")`.

### A1. Матрица достижимости

| Preset | Путь в UI | Desktop | Android |
|--------|-----------|---------|---------|
| Inbox | Bottom nav / sidebar «Inbox» | ✔ | ✔ |
| Today | Bottom nav / sidebar «Today» | ✔ | ✔ |
| Upcoming | Bottom nav / sidebar «Upcoming» | ✔ | ✔ |
| byProject | ProjectDetail → «See all N tasks» | ✔ | ✔ |
| byDateRange | Calendar → выбрать день | ✔ | ✔ |
| byTag | Search → tag chip | ✔ | ✔ |
| byTags | **Нет UI-входа** | ✗ | ✗ |

### A2. Inbox

- **A2-01** Холодный старт → тап «Inbox». Секции: Overdue, Today, Yesterday, Tomorrow, This Week, Next Week, This Month, No Date (по `order`). Счётчики формата `"No Date  ·  N"`.
- **A2-02** Раскладка — см. таблицу выше.
- **A2-03** Эксклюзивность: T05 только в «Tomorrow» (discard=true).
- **A2-04** Пустые секции **не отображаются** (evaluator возвращает `null` для пустых → `filterNotNull`).
- **A2-05** Yesterday: T02 (D−1) — **в Overdue**, а не в Yesterday. Overdue = `dueDate < today` (все даты до D, включая D−1). Yesterday никогда не получает задач на текущем сиде. → **дефект: Yesterday unreachable in Inbox.**
- **A2-06** T16 (корзина), T17 (архив) нигде не видны.
- **A2-07** T10 (far-future) — **нигде**: This Month простирается на 30 дней (D+1..D+30), D+90 за пределами.
- **A2-08** T04 (completed): **виден в Today** (evaluator не фильтрует completed; фильтр по completed — только через `Selector.Completed`).
- **A2-09** T14 (startDate=D+3, no dueDate): **в No Date** (RelativeBucket.NoDate = dueDate == null).
- **A2-10** T15 (subtask): отображается на уровне T03 с indent, или отдельной строкой — **зафиксировать поведение**.
- **A2-11** Пустая БД: empty-state, нет краша.
- **A2-12** Реактивность: создать задачу без даты → появляется в No Date без перезапуска.

### A3. Today

- **A3-01** Секции: Overdue, Today. **No Date отсутствует.**
- **A3-02** Раскладка: Overdue{T01, T02}, Today{T03, T15, T04}.
- **A3-03** FAB → CreateTask с `dueDate = today` (MR-13). После сохранения — задача в Today.
- **A3-04** Отметить T03 выполненной → **有待确认**: исчезает из Today или остаётся с бейджем Completed?

### A4. Upcoming

- **A4-01** 5 секций: Overdue(0,discard), Today(1,discard), Tomorrow(2,discard), ThisWeek(3), NextWeek(4). **No Date отсутствует. This Month отсутствует.**
- **A4-02** Раскладка: Tomorrow{T05, T18}, ThisWeek{T06, T07}, NextWeek{T08}. T09 (D+14) **нигде** — NextWeek оканчивается на D+13 (вс).
- **A4-03** Задача D+3 → ThisWeek без перезапуска.

### A5. Project view (byProject)

- **A5-01** ProjectDetail(Alpha) → «See all» → только Alpha (T01).
- **A5-02** Beta: только T13; дочерние задачи Alpha **не подмешиваются**.
- **A5-03** Пустой проект → empty-state.
- **A5-04** FAB в этом контексте: **скрыт** (FAB hidden state отложен, MR-13).
- **A5-05** Back → ProjectDetail, состояние сохранено.

### A6. Calendar / byDateRange

- **A6-01** Выбрать D → T03, T04, T15; T16/T17 нет.
- **A6-02** День без задач → empty-state.
- **A6-03** Preload ±7 дней.
- **A6-04** `CalendarFlowTest.every_day_of_the_month_has_an_addressable_cell` — **待确认**: тело переписано, но 4 ADR всё ещё называют его pre-existing failure. Запустить и подтвердить цвет.

### A7. byTag(s)

- **A7-01** `byTags({work})` ANY: T01, T05. `byTags({work, urgent}, matchAll=true)`: T05.
- **A7-02** Входа для byTags(multi) **нет** → тест A1 падает на этой строке.

### A8. Общее поведение любого agenda-экрана

1. Бейджи: Blocked > Pinned > Recurring > Completed > Overdue > NoDate > null.
2. Тап по строке → TaskDetail; back → позиция сохранена.
3. Сворачивание/разворачивание секции.
4. **«+» в заголовке секции: NO-OP** (`handleCreateInSection` выходит при `prefill == null`). Дефект, не ожидаемое поведение.
5. Нет краша `Key already used` (исправлено ADR 09-30).
6. Bookmark и «Save current as view» в топбаре.
7. A11y (`checkA11y = true`).

---

## Suite B — SavedAgendaList

- **B-01** Bookmark → список; back → возврат на таб.
- **B-02** Built-in preset'ы **отсутствуют** в списке.
- **B-03** Empty-state: «No saved views yet» + FAB «Create view».
- **B-04** Loading → Loaded.
- **B-05** Карточка: имя + «Updated …» (без sectionCount). `testTag = savedAgendaCard(<name>)`.
- **B-06** Порядок по **имени** (алфавитный, `compareBy { it.name.lowercase() }` в `SavedAgendaViewsRepositoryImpl`).
- **B-07** Тап → Results (не Edit). Заголовок = имя view.
- **B-08** Overflow-меню: Edit / Copy to profile / Delete.
- **B-09** Copy to profile: ProfilePicker → CopySuccess("Copied to …") → **уведомление невидимо** (null text → ResultDialog ничего не показывает).
- **B-10** Delete из списка: **без подтверждения**, сразу удаляет. ConfirmActionDialog только в редакторе.
- **B-11** Изоляция профилей: PK `(user_id, id)`.
- **B-12** Default view (Settings): **UI не реализован**, отложен (D5). Unit-тест `SettingsRepository.defaultSavedAgendaViewId` зелёный.
- **B-13** 50+ карточек: скролл без лимита.
- **B-14** Одинаковые имена: обе карточки открываются. `testTag` коллизирует — зафиксировать.
- **B-15** Ошибка delete → ShowError, список не ломается.

---

## Suite C — Создание saved view

### C.0 Входы

| Вход | Источник seed | Ожидаемое |
|------|-------------|-----------|
| (а) «Save current as view» | `SavedAgendaSeedStore.setSeed(definition)` | Редактор с секциями текущего таба, имя = название таба |
| (б) FAB на SavedAgendaList | `mode.seed = AgendaPresets.Inbox` (игнорируется: `consumeSeed` приоритетнее) | Create mode, пустой seed |
| (в) Deep link Create | — | **не поддерживается** |

### C.1 Тесты

- **C-01** Happy path (а): Inbox → BookmarkAdd → имя «My Inbox» → Save → возврат → карточка в списке.
- **C-02** Happy path (б): FAB → имя → секция Due today → Save → карточка.
- **C-03** Пустое имя: Save disabled. Пробелы — disabled.
- **C-04** Имя `"   "` → disabled. При сохранении `name.trim()` — триммится.
- **C-05** Граничные имена: 1 символ, 200 символов, эмодзи, кириллица, RTL. Сохраняется, testTag через `slug()` не ломается.
- **C-06** Add section: 7 шаблонов (Active, Completed, Due today, Overdue, No date, This week, Next week). Каждый — жёсткий `Selector`, **без параметров**. Имя секции = `selector.typeDescription`.
- **C-07** Дубликаты шаблонов: оба присутствуют, ключи не падают.
- **C-08** Удалить секцию: исчезает, `isDirty = true`.
- **C-09** Удалить все секции: Save **разрешён** (canSave: `name.isNotBlank && isDirty`); view без секций → Results с empty-state.
- **C-10** Up/Down: у первой Up disabled, у последней Down disabled. Сохраняется в Results.
- **C-11** Drag-and-drop: **нет** (dead code, ADR 09-30 заменил на кнопки).
- **C-12** Параметры селекторов: **не задаются через UI**. 7 шаблонов — фиксированные селекторы. → **functional gap, известный.**
- **C-13** isSaving: во время сохранения кнопка disabled, после — ровно одна запись.
- **C-14** Discard: dirty → back → ConfirmDiscard → Discard → выход без записи. Без изменений — диалог не показывается.
- **C-15** Системный back / Esc при dirty → ConfirmDiscard.
- **C-16** Process death: seed теряется, возврат к списку, нет краша.
- **C-17** SeedStore гигиена: (а) close без save → (б) FAB → seed от (а) не «протекает».
- **C-18** Persistence: рестарт → view на месте.
- **C-19** Профиль: Personal → создать → переключиться на Work → не видно.
- **C-20** Двойной тап Save → одна запись.

---

## Suite D — Редактирование saved view

- **D-01** Карточка → Edit → редактор с предзаполненными данными, Save disabled до изменений.
- **D-02** Переименование → Save enabled → Save → новое имя, `updatedAt` изменился, `createdAt` нет.
- **D-03** Имя возвращено к исходному → Save disabled.
- **D-04** +1 секция → Save → Results показывает новую секцию.
- **D-05** −1 секция → Save → секции нет; задачи БД не затронуты.
- **D-06** Up/Down → Save → порядок изменён в Results.
- **D-07** Комбинация: rename + add + remove + reorder → одна запись.
- **D-08** Discard: изменения → back → Discard → прежнее состояние.
- **D-09** Delete из редактора: ConfirmDelete → подтверждение → возврат на список.
- **D-10** Re-seed guard: внешняя запись не затирает in-flight данные.
- **D-11** NotFound: открыть Edit для удалённого id → auto-pop без краша.
- **D-12** Конкурентное удаление: view удалён, пока открыт Edit → pop + ShowError.
- **D-13** Legacy-данные: старый JSON → открывается и редактируется.

---

## Suite E — Просмотр результатов saved view

- **E-01** Тап по карточке → `BackTopAppBar(name)`, под ним `AgendaScreen(definition)`.
- **E-02** Набор задач = `evaluate(definition, tasks, today)`. Пустые секции дропаются.
- **E-03** Тап по задаче → TaskDetail → back → Results, позиция сохранена.
- **E-04** Реактивность: изменить задачу в другом табе → Results обновляется.
- **E-05** Пустой результат → empty-state, не спиннер.
- **E-06** Бейджи — как A8.1.
- **E-07** FAB / «+» в секции: **待确认** (FAB hidden state MR-13 отложен).
- **E-08** Свернуть/развернуть секцию.
- **E-09** Edit view → Save → Results обновлён (кеш сброшен).
- **E-10** Back stack: список → Results → TaskDetail → back → Results → back → список → back → таб.
- **E-11** Rotate/resize → состояние сохраняется.
- **E-12** 1000 задач / 20 секций: < 1 с открытие, скролл без дропа кадров.

---

## Suite F — Матрица «фильтр → задачи»

| ID | Selector | Ожидаемый результат |
|----|----------|---------------------|
| F-01 | DateBucket (9 вариантов) |см. раскладку Inbox; граница недели Пн/Вс |
| F-02 | DateRange(from, to) |включительно; from > to → пусто |
| F-03 | Statuses(Active/Completed) |T04 только в Completed |
| F-04 | Tags({work}) ANY / matchAll |T01, T05 / только T05 |
| F-05 | Projects({Alpha}) |T01; дочерние проекты — **待确认** |
| F-06 | Priorities({High}) |T01 |
| F-07 | Regexp("milk") |T18; IGNORE_CASE; `.` и др. экранируются |
| F-08 | Pinned / Overdue / Anything |T03 / T01,T02 / все активные |
| F-09 | **Проекты/заметки в agenda** |**не поддерживаются** — evaluator only Task |
| F-10 | AllOf / AnyOf / Not |AllOf(Tags(work), DateBucket(Tomorrow)) → T05 |
| F-11 | discard = true/false |без discard задача дублируется; ключи не падают |
| F-12 | Корзина/архив |не попадают |
| F-13 | Section.baseFilter |**отложен** (SQL narrowing) |
| F-14 | JSON/legacy |StableJson; Tag→Tags shim; sections без id |
| F-15 | SQL vs in-memory |Fake DAO и Room совпадают с evaluator |

---

## Suite G — Сквозные сценарии

- **G-01** Android full lifecycle (Maestro `05-full-lifecycle.yaml`): seed → Inbox → Bookmark → пустой список → FAB → имя + 2 секции → Save → карточка → Results → Edit → rename + add → Save → Results → Copy to Work → профиль → копия → Delete → пусто.
- **G-02** Desktop full lifecycle (аналогично, `SavedAgendaLifecycleFlowTest`).
- **G-03** Persistence: рестарт посреди сценария.
- **G-04** Backup/Restore: **agenda_views НЕ входят в payload** → views теряются. Дефект, известный.
- **G-05** Полночь: открыть Results в 23:59 → `today` в VM обновится в полночь (`todayFlow`). **待确认**: переоценивается ли definition? (todayFlow вызывает re-evaluation).
- **G-06** Часовой пояс / DST: границы Today/Tomorrow корректны.
- **G-07** Deeplink из уведомления: **не реализован** (D7). Тест `@Ignore`.
- **G-08** Миграция: v11→v12 (agenda_views), v12→v13 (task_reminders.view_id). Данные целы.
- **G-09** Локаль: en/ru, формат «No Date · N».
- **G-10** Тёмная/светлая тема.
- **G-11** A11y: TalkBack, Tab-порядок, Desktop: Tab/Shift+Tab/Enter/Esc.

---

## Матрица: кейс → слой → файл

| Кейс | Слой | Файл(ы) |
|------|------|---------|
| A0-*, F-* | commonTest | `shared/src/commonTest/.../agenda/AgendaEvaluatorMatrixTest.kt`, `AgendaPresetsCatalogTest.kt`, `AgendaBadgePolicyTest.kt`, `SelectorSerializerTest.kt`, `AgendaBucketingTest.kt` |
| A1, A8 | Desktop flow | `AgendaTabDefinitionFlowTest.kt`, `AgendaReachabilityFlowTest.kt` (NEW) |
| A2-A7 | Desktop flow | `AgendaTabDefinitionFlowTest.kt` (расширить), `AgendaBadgePolicyFlowTest.kt` |
| B, C, D, E | Desktop flow | `SavedViewsFlowTest.kt`, `SavedAgendaCreateFlowTest.kt`, `SavedAgendaEditFlowTest.kt` (NEW), `OpenSavedViewShowsMatchingTasksFlowTest.kt` |
| C/D VM | jvmTest | `SavedAgendaViewModelTest.kt`, `SavedAgendaListViewModelTest.kt` (NEW) |
| B/E VM | jvmTest | `SavedAgendaListViewModelTest.kt` (NEW) |
| A2-A7 | Maestro | `01-smart-lists.yaml`, `06-project-view.yaml`, `07-calendar-day.yaml`, `17-reachability.yaml` (NEW) |
| B-E | Maestro | `03-saved-views-crud.yaml`, `04-saved-view-results.yaml`, `05-full-lifecycle.yaml` (NEW), `08-copy-to-profile.yaml` (NEW), `09-discard-and-validation.yaml` (NEW) |

---

## Known-дефекты (фиксированы как ожидаемые)

| ID | Описание | Кейсы |
|----|---------|--------|
| D5 | Default saved view UI не реализован | B-12 |
| D7 | Deep link из уведомления не реализован | G-07 |
| K1 | Yesterday unreachable in Inbox (Overdue поглощает D−1) | A2-05 |
| K2 | byTags multi-tag без UI-входа. Частично закрыто 2026-10-04: редактор теперь умеет собирать мульти-теговую секцию; отдельного preset-входа (кнопка «создать view по тегам») по-прежнему нет — это продуктовое решение, не дефект | A1, A7 |
| ~~K3~~ | ~~Редактор без параметров селекторов~~ — **закрыто 2026-10-04**: `SelectorTemplate` + `MultiSelectSheet`, секции по тегу/проекту/приоритету/статусу | C-12 |
| K4 | agenda_views не в backup payload | G-04 |
| K5 | «+» в заголовке секции — no-op (prefill == null) | A8.4 |
| K6 | CopySuccess уведомление невидимо (null text) | B-09 |
| K7 | isSaving clobber: NameChanged при in-flight save сбрасывает isSaving | C-13 |
| K8 | desktop-nav-goBack-blank-screen (отдельный gate-issue) | C-01, D-02, E-09 на Desktop |

---

## Команды

```bash
# Unit / VM
./gradlew :shared:jvmTest --tests "*AgendaEvaluatorMatrixTest"
./gradlew :shared:jvmTest --tests "*SavedAgendaListViewModelTest"
./gradlew :shared:jvmTest --tests "*AgendaViewModelTest"

# Desktop flow
./gradlew :desktopApp:test --tests "*AgendaTabDefinitionFlowTest"
./gradlew :desktopApp:test --tests "*SavedAgendaCreateFlowTest"
./gradlew :desktopApp:test --tests "*SavedAgendaEditFlowTest"   # NEW
./gradlew :desktopApp:test --tests "*SavedViewsFlowTest"

# Maestro
FLOW=01-smart-lists SKIP_INSTALL=1 scripts/run-maestro.sh
FLOW=03-saved-views-crud SKIP_INSTALL=1 scripts/run-maestro.sh

# Контракты
./gradlew :shared:jvmTest -PupdateGoldens=true   # TAGS.md
./gradlew :shared:jvmTest --tests "*MaestroFlowTagsTest"
./gradlew :shared:jvmTest --tests "*TestTagsWiringTest"

# Гейты
just tcheck
./gradlew :desktopApp:test -Ptest.tags=slow   # slow-теги
```
