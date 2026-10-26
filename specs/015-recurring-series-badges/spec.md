# Feature Specification: Repeat: материализация будущих вхождений и бейджи в строке списка

**Feature Branch**: `gazon1/snook`

**Created**: 2026-10-25

**Status**: Draft

**Input**: User description: "Repeat в задачах непонятно работает — я не вижу будущих задач и новых задач в upcoming при выполнении текущей задачи и нет бейджа в карточке задачи, что эта задача повторяема. Добавь ещё бейдж проекта в карточку задачи."

## Problem

«Повтор» в приложении существует как правило, но как функция он не читается. Три независимых дефекта стоят между пользователем и тем, что он ожидает увидеть.

1. **Выполнение повторяющейся задачи не оставляет видимого будущего.** Повтор сегодня работает как «переезд»: ряд закрывается и одновременно переносится на следующую дату. Но перенос меняет только дату — закрытое состояние остаётся закрытым. В итоге ряд существует с датой в будущем и при этом закрыт: из «Сегодня» он исчезает, а в «Upcoming» возвращается уже перечёркнутым и закрытым. Пользователь закрывает «Купить хлеб — каждую неделю» и не видит ничего нового: следующего вхождения как открытой задачи не существует ни в одном списке.

2. **Значок повтора в строке списка никогда не появляется.** Строка задачи умеет рисовать значок повтора, но признак «эта задача повторяется» никогда не доходит до строки: при сборке строки он подставляется константой «нет». Это не «редкий случай» — это «не бывает никогда».

3. **Проект в строке списка не показан вообще.** Строка задачи умеет показывать проект, но имя проекта до неё тоже не доходит: при сборке строки подставляется пустое значение. Пользователь не может ни прочитать, ни различить цветом, к какому проекту относится строка. Просьба «добавь бейдж проекта» — это и есть отсутствующая половина того, что строка уже умеет.

Первые два дефекта — это одно и то же решение, сказанное двумя сторонами: повторяющаяся работа не представлена в приложении как **набор вхождений**, а как **одна задача, которая ездит по календарю**. Пока представление — «одна ездящая задача», у пользователя нет будущего, которое можно увидеть, и нет признака, что задача повторяется.

## Terminology

| Term | Meaning |
|---|---|
| **Вхождение (occurrence)** | Одно датированное исполнение повторяющейся задачи. Является обычной задачей со своей датой. |
| **Серия (series)** | Набор вхождений одного правила, объединённых общим идентификатором серии. |
| **Вид серии** | Серия бывает двух видов: **выбранных дат** (даты выбрал пользователь) и **повторяющаяся** (даты суть вхождения правила). Вид определяется тем, откуда взялись даты, и не смешивается: в повторяющейся серии нет выбранных вручную дат, в серии выбранных дат нет правила. |
| **Правило (rule)** | Само правило повтора: периодичность, база отсчёта, условие окончания. |
| **Горизонт (horizon)** | Насколько далеко вперёд серия держит материализованные вхождения. |
| **Текущее вхождение** | Самое раннее незакрытое вхождение серии — то, что пользователь делает следующим. |
| **Бейдж повтора** | Компактный значок «↻» в строке списка, означающий, что задача повторяется. |
| **Бейдж проекта** | Компактный значок с именем и цветом проекта в строке списка. |

## Delivery scope

Решение принято совместно с пользователем и разбито на три независимо поставляемые части.

| Part | Content | Depends on |
|---|---|---|
| **A — Материализация серии** | Будущие вхождения становятся настоящими задачами и видны в Upcoming; завершение вхождения не отменяет будущее. | — |
| **B — Бейдж повтора** | Строка списка показывает, что задача повторяется. | — |
| **C — Бейдж проекта** | Строка списка показывает проект задачи — именем и цветом. | — |

Части B и C независимы от A и могут быть поставлены первыми: они устраняют «невидимость признаков» независимо от того, как представлено будущее.

## Out of scope

- **Общий слот бейджа строки.** В повестке есть рассчитанный, но никогда не отрисованный бейдж секции (`Просрочено`, `Закреплено`, `Выполнено`, `Без даты`). Заполнение этого слота — отдельная работа; здесь трогаются только бейдж повтора и бейдж проекта.
- **Экран деталей задачи.** Бейджи живут в строке списка; редактируемые строки атрибутов на экране задачи остаются как есть.
- **Полная политика экспорта повторяющейся серии в календарь.** Нужен ли событию правило повтора вместо набора событий — отдельное решение. Здесь закреплено только FR-042: экспорт следует действующему правилу «одна строка — одно событие», и повторяющаяся серия не получает особого обращения.
- **Набор правил повтора** — язык правил, единицы периодичности, «с недели», «в день недели», «каждый N-й». Не меняется.
- **Платформы.** iOS в проекте нет; решение ограничено Android и Desktop.
- **Синхронизация с облаком.** Правила синхронизации существующих задач не меняются; требования касаются только локальной модели и вида.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - Я вижу будущее повторяющейся задачи (Priority: P1)

У меня есть задача, которая повторяется каждую неделю. Я хочу открыть «Upcoming» и увидеть не только сегодняшнее вхождение, но и следующие — датированные, открытые, с тем же названием и тем же проектом. Я хочу, чтобы после выполнения сегодняшнего вхождения следующие не исчезали, а оставались на месте.

**Why this priority**: Это ядро жалобы: «не вижу будущих задач и новых задач в Upcoming». Пока представление — одна задача, которая переезжает, будущего не существует в принципе, и никакой бейдж этого не исправит.

**Independent Test**: Создать задачу с недельным правилом и датой сегодня; открыть Upcoming и убедиться, что в нём есть открытые вхождения с будущими датами и тем же названием. Затем закрыть сегодняшнее вхождение и убедиться, что будущие вхождения остались на месте и открыты. Проверка не зависит от частей B и C.

**Acceptance Scenarios**:

1. **Given** задача с недельным правилом и датой сегодня, **When** правило сохранено, **Then** в Upcoming появляется открытое вхождение с датой через неделю, с тем же названием, проектом и признаком повтора.
2. **Given** серия с открытыми вхождениями в будущем, **When** пользователь закрывает сегодняшнее вхождение, **Then** это вхождение остаётся закрытым на своей дате, а будущие вхождения серии остаются открытыми и видимыми в Upcoming.
3. **Given** серия, все открытые вхождения которой закрыты, **When** закрывается последнее из них, **Then** серия получает новое вхождение на дату по правилу, и число открытых будущих вхождений серии MUST NOT уменьшиться.
4. **Given** задача без правила повтора, **When** она создана, **Then** она остаётся одной задачей и серией не становится.
5. **Given** вхождение в будущем, **When** пользователь открывает его и меняет название, **Then** новое название расходится по всей серии, а не только по этому вхождению.
6. **Given** серия, **When** правило изменено на другое, **Then** будущие вхождения пересчитаны по новому правилу, а уже закрытые вхождения остаются в истории как закрытые.
7. **Given** вхождение, у которого есть незакрытая предпосылка, **When** пользователь пытается его закрыть, **Then** закрытие отклоняется, и серия остаётся ровно такой же, какой была до попытки.

---

### User Story 2 - Я вижу в строке, что задача повторяется (Priority: P2)

Я листаю список задач. Я хочу по одной строке понять, какие задачи повторяются, а какие — разовые, ещё до того, как открою задачу. Мне не нужно открывать каждую задачу, чтобы это выяснить.

**Why this priority**: Признак повтора в строке — самая дешёвая часть: она не зависит от модели серий и устраняет половину «непонятности» сразу. Но она не работает, пока будущее нельзя увидеть, поэтому идёт после части A.

**Independent Test**: Собрать список, где одна задача повторяется, а другая — нет, и убедиться, что у первой строки есть бейдж повтора, а у второй — нет; что бейдж есть у каждого вхождения серии; и что у бейджа есть текстовое описание для программ чтения с экрана.

**Acceptance Scenarios**:

1. **Given** строка задачи, у которой есть правило повтора, **When** список отрисован, **Then** строка несёт бейдж повтора.
2. **Given** строка задачи без правила повтора, **When** список отрисован, **Then** бейдж повтора отсутствует.
3. **Given** серия из нескольких вхождений, **When** отрисован любой её элемент, **Then** каждый элемент серии несёт бейдж повтора.
4. **Given** бейдж повтора, **When** он отрисован, **Then** он визуально отличается от даты, бейджа проекта и признака заблокированности.
5. **Given** бейдж повтора, **When** приложение отдаёт строку средствам доступности, **Then** у него есть текстовое описание, называющее повторяемость задачи.
6. **Given** строка списка, **When** она собрана, **Then** признак повтора выводится из данных самой задачи, а не из флага, проставленного в другом месте.

---

### User Story 3 - Я вижу в строке, к какому проекту относится задача (Priority: P2)

У меня есть несколько проектов. Я хочу в списке видеть, к какому проекту относится строка, и различать проекты не только по имени, но и по цвету — чтобы взгляд на список сразу давал карту моей работы.

**Why this priority**: Проект — это второй отсутствующий признак строки, и он же единственный, который даёт цветовую разметку. Вместе с бейджем повтора строка становится читаемой без открытия задачи.

**Independent Test**: Собрать список с задачами из двух проектов с разными цветами и задачей без проекта; убедиться, что у первой пары строк бейдж проекта с именем и цветом проекта, у третьей — бейджа нет, и что имя проекта больше не дублируется отдельным текстом в строке.

**Acceptance Scenarios**:

1. **Given** строка задачи, у которой есть проект, **When** список отрисован, **Then** строка несёт бейдж проекта с именем проекта.
2. **Given** проект с собственным цветом, **When** отрисован бейдж проекта, **Then** бейдж использует цвет этого проекта.
3. **Given** строка задачи без проекта, **When** список отрисован, **Then** бейдж проекта отсутствует.
4. **Given** бейдж проекта, **When** список отрисован, **Then** имя проекта не дублируется отдельным текстом в строке.
5. **Given** длинное имя проекта, **When** оно не помещается в строку, **Then** оно сокращается многоточием и не вытесняет дату из строки.
6. **Given** бейдж проекта, **When** список отрисован в тёмной теме, **Then** бейдж остаётся читаемым.
7. **Given** список задач, **When** список собирается, **Then** имя и цвет проекта вычисляются один раз на список, а не ищутся отдельно для каждой строки.

---

### Edge Cases

- **Правило без будущих дат.** Правило с условием окончания «до сегодня» не даёт ни одного будущего вхождения — серия остаётся без будущего, и это не ошибка.
- **Правило, которое даёт больше дат, чем помещается в горизонт.** Ежедневное правило внутри двухнедельного горизонта даёт четырнадцать дат, а серия держит не больше десяти — лишние даты просто не материализуются, и набор остаётся предсказуемым.
- **Пропущенные вхождения.** Вхождения, дата которых прошла, пока пользователь не закрывал задачу, материализуются как закрытые — историю нельзя переписывать задним числом.
- **Дважды применённое правило.** Повторная попытка материализовать те же даты не создаёт второй раз те же строки.
- **Вхождение, закрытое не по порядку.** Пользователь закрывает вхождение из будущего — остальные вхождения серии не меняются.
- **Серия, у которой правило сняли.** Существующие вхождения остаются, но серия перестаёт расти.
- **Удаление одного вхождения.** Удаляется только это вхождение; остальные вхождения серии остаются.
- **Пустой проект.** Проект без цвета получает нейтральный бейдж — отсутствие цвета не должно ронять строку.
- **Задача без даты.** Строка без даты по-прежнему показывает бейдж проекта и бейдж повтора, если они есть.

## Requirements *(mandatory)*

### Part A — Материализация серии

- **FR-001**: Задача с правилом повтора MUST становиться частью серии — ряд, несущий правило, MUST NOT существовать в одиночку.
- **FR-002**: Будущие вхождения правила MUST существовать как отдельные задачи со своими датами — следующее вхождение MUST быть отдельной строкой, а не переездом той же строки.
- **FR-003**: Материализованное вхождение MUST наследовать работу серии: название, проект, теги, приоритет и цвет.
- **FR-004**: Закрытие вхождения MUST NOT менять его дату — вхождение остаётся на своей дате в закрытом состоянии.
- **FR-005**: Число открытых будущих вхождений серии MUST NOT уменьшаться по мере закрытия вхождений — после закрытия вхождения серия MUST держать не меньше будущих дат, чем до него.
- **FR-006**: Набор будущих дат серии MUST быть однозначно определён правилом и положением серии — одинаковое правило при одинаковом положении MUST давать одинаковые даты, и повторная материализация MUST NOT создавать дубликаты.
- **FR-007**: Горизонт MUST составлять 14 дней от даты текущего вхождения серии, а не от текущего дня.
- **FR-008**: Серия MUST NOT держать больше 10 будущих вхождений одновременно.
- **FR-009**: Серия MUST NOT держать больше 30 вхождений в сумме; при достижении предела самое старое закрытое вхождение MUST покинуть серию, оставаясь обычной закрытой задачей, и MUST потерять правило вместе с серией (FR-049).
- **FR-010**: Вхождения за пределами горизонта MUST NOT материализоваться — кроме первой даты правила за горизонтом, которая MUST материализоваться, пока это позволяет предел в 10 будущих вхождений и правило не исчерпано.
- **FR-011**: Когда следующая дата по правилу выходит за условие окончания правила, серия MUST переставать расти.
- **FR-012**: Отказ закрытия (открытая предпосылка) MUST оставлять серию ровно такой, какой она была.
- **FR-013**: Материализация MUST быть атомарной — частично построенная серия MUST NOT оставаться в данных.
- **FR-014**: Пропущенные вхождения MUST материализоваться как закрытые с прошедшими датами, и MUST NOT материализоваться повторно при следующей попытке.
- **FR-015**: Изменение правила MUST пересчитывать будущие вхождения: даты, которых новое правило не даёт, MUST исчезать; даты, которые новое правило даёт, MUST появляться. Уже закрытые вхождения MUST сохраняться.
- **FR-016**: Изменение свойств работы MUST применяться ко всем вхождениям серии.
- **FR-017**: Снятие правила MUST останавливать рост серии, оставляя существующие вхождения на месте.
- **FR-018**: Каждое вхождение MUST закрываться независимо — закрытие будущего вхождения MUST NOT закрывать более ранние.
- **FR-019**: Вхождения серии MUST быть видны пользователю — экран задачи MUST показывать даты серии.
- **FR-020**: Материализация MUST NOT превышать существующие ограничения серий и догоняющих вхождений.
- **FR-043**: Положение серии MUST определяться её самым ранним открытым вхождением; если открытых вхождений нет — самым поздним вхождением серии. При закрытии положением MUST считаться то вхождение, которое закрывается. Дата, от которой отсчитывается правило, MUST быть датой вхождения для правил «от даты» и «догоняющих» и датой закрытия — для правил «от закрытия».
- **FR-044**: Набор будущих вхождений MUST включать первую дату правила за горизонтом, если предел в 10 ещё не выбран — иначе правило с редким периодом (раз в месяц, раз в год) не имело бы следующего вхождения.
- **FR-045**: Закрытое вхождение MUST оставаться в серии до тех пор, пока серия не достигнет предела в 30 вхождений — если только вхождение не покинет серию по инициативе пользователя (FR-049)..
- **FR-046**: Дата, от которой отсчитывается правило, MUST определяться базой: дата вхождения для правил «от даты» и «догоняющих», дата закрытия — для правил «от закрытия».
- **FR-047**: Когда правило исчерпано, вхождение MUST закрываться, а серия MUST переставать расти; вхождение MUST NOT архивироваться, а оставаться закрытой задачей.
- **FR-048**: Правило повтора MUST NOT сочетаться с выбранными вручную датами — в таком случае правило отбрасывается, и пользователь MUST быть об этом оповещён.
- **FR-049**: Вхождение, покидающее повторяющуюся серию, MUST терять правило вместе с идентификатором серии — оставшаяся отдельной задача MUST NOT нести бейдж повтора; остальные вхождения серии MUST сохранить правило, и серия MUST продолжиться с них. Это правило распространяется на оба пути выхода из серии — на действия пользователя и на вращение при достижении предела (FR-009). Если после выхода в серии не остаётся ни одного вхождения, ряд, оставшийся без серии, MUST ALSO потерять правило: ряд, несущий правило и не принадлежащий серии, не должен появляться (FR-001).
- **FR-050**: Объединение двух серий MUST NOT смешивать виды серий: повторяющаяся серия MUST NOT объединяться с серией выбранных дат, и две повторяющиеся серии с разными правилами MUST NOT объединяться; отказ MUST называть причину.

### Part B — Бейдж повтора в строке списка

- **FR-021**: Строка задачи, принадлежащей повторяющейся серии, MUST нести бейдж повтора.
- **FR-022**: Признак повтора MUST выводиться из данных самой задачи при сборке строки — сборщик строки MUST NOT подставлять постоянное значение.
- **FR-023**: Бейдж повтора MUST быть значковым и MUST иметь текстовое описание, называющее повторяемость задачи.
- **FR-024**: Каждое вхождение повторяющейся серии MUST нести бейдж повтора.
- **FR-025**: Строка задачи без правила MUST NOT нести бейдж повтора.
- **FR-026**: Бейдж повтора MUST визуально отличаться от даты, бейджа проекта и признака заблокированности.

### Part C — Бейдж проекта в строке списка

- **FR-027**: Строка задачи, принадлежащей проекту, MUST нести бейдж проекта с именем проекта.
- **FR-028**: Бейдж проекта MUST использовать цвет проекта.
- **FR-029**: Строка задачи без проекта MUST NOT нести бейдж проекта.
- **FR-030**: Имя проекта MUST NOT дублироваться отдельным текстом в строке.
- **FR-031**: Имя проекта MUST сокращаться многоточием, когда не помещается, и MUST NOT вытеснять дату из строки.
- **FR-032**: Бейдж проекта MUST оставаться читаемым в светлой и тёмной темах.
- **FR-033**: Имя и цвет проекта MUST вычисляться один раз на сборку списка — сборщик MUST NOT обращаться к проекту для каждой строки отдельно.

### Part D — Данные, созданные до изменения

- **FR-034**: Задачи, уже несущие правило повтора, MUST быть приведены к сериям с материализованными будущими вхождениями — при ближайшем запуске приложения с активным этим профилем.
- **FR-035**: Приведение MUST выполняться переносом данных, а не уничтожением базы — все данные пользователя MUST сохраниться.
- **FR-036**: Ряд, несущий правило повтора, MUST быть переоткрыт только тогда, когда у серии нет открытого вхождения — закрытое вхождение с прошедшей датой MUST оставаться закрытым.
- **FR-037**: Приведение MUST быть идемпотентным — повторный запуск MUST NOT создавать дублирующиеся вхождения.
- **FR-038**: Перенос данных MUST NOT требовать изменения схемы хранения.

### Part E — Поведение списков и календаря

- **FR-039**: Материализованные будущие вхождения MUST появляться в Upcoming в секции той даты, к которой они относятся.
- **FR-040**: Материализованное вхождение MUST вести себя как обычная задача — его можно открыть, изменить, закрыть и удалить.
- **FR-041**: Upcoming MUST NOT показывать закрытое вхождение как открытую будущую задачу.
- **FR-042**: Экспорт в календарь MUST следовать действующему правилу «одна строка — одно событие»: каждое открытое вхождение MUST экспортироваться отдельным событием, а повторяющиеся серии MUST NOT получать особого обращения.

### Key Entities

- **Вхождение** — задача со своей датой, являющаяся одним исполнением правила. Атрибуты: дата, состояние, идентификатор серии, правило.
- **Серия** — набор вхождений одного правила. Атрибуты: правило, горизонт, состав вхождений, текущее вхождение.
- **Правило** — правило повтора: периодичность, база отсчёта, условие окончания (существует).
- **Бейдж строки** — компактный признак строки: бейдж повтора и бейдж проекта.

### Success Criteria *(mandatory)*

- **SC-001**: После сохранения недельного правила на задаче с датой сегодня в Upcoming появляется хотя бы одно открытое будущее вхождение — без дополнительных действий пользователя.
- **SC-002**: Закрытие вхождения не уменьшает число открытых будущих вхождений серии: до и после закрытия их поровну.
- **SC-003**: Доля строк списка с правилом повтора, имеющих бейдж повтора, — 100 % (сейчас 0 %).
- **SC-004**: Доля строк списка, принадлежащих проекту, имеющих бейдж проекта с цветом проекта, — 100 % (сейчас 0 %).
- **SC-005**: Пользователь различает проект и повторяемость строки, не открывая задачу и не читая текст строки.
- **SC-006**: Ни одна серия не держит больше 10 будущих вхождений и больше 30 вхождений в сумме.
- **SC-007**: Отклонённое закрытие оставляет серию побайтно равной прежней.
- **SC-008**: Upcoming не показывает закрытые вхождения как открытые будущие задачи.
- **SC-009**: После обновления существующая повторяющаяся задача видна как серия с будущими вхождениями, и ни одно поле пользователя не потеряно.
- **SC-010**: Число событий календаря на одну повторяющуюся серию равно числу её открытых вхождений — то же правило, что и для серии с выбранными датами.

### Measurable Outcomes

- Будущие вхождения видны в Upcoming сразу после сохранения правила, а не после первого закрытия.
- Закрытие вхождения обратимо: его можно открыть заново, и серия не рассыпается.
- Приведение старых данных не меняет ни одного пользовательского поля.
- Бейджи читаются на маленьком экране: строка не растёт в высоту из-за бейджей.

## Assumptions

- Горизонт в 14 дней совпадает с собственной дальностью Upcoming (Просрочено → Сегодня → Завтра → На этой неделе → На следующей неделе): то, что пользователь листает, существует как задача. Горизонт отсчитывается от текущего вхождения серии, а не от сегодняшнего дня, — иначе набор вхождений менялся бы сам по себе и требовал бы ежедневной фоновой записи.
- Предел в 10 будущих вхождений совпадает с существующим пределом догоняющих вхождений; предел в 30 вхождений — с существующим пределом серии.
- «Карточка задачи» в формулировке пользователя — строка задачи в списке; бейджи повтора и проекта живут только там, а экран задачи сохраняет свои редактируемые строки атрибутов.
- Существующий признак «серия задач» и существующий календарь серии переиспользуются: материализованные вхождения — это те же серии, а не новый вид сущности.
- Хранение уже умеет хранить идентификатор серии у задачи и правило повтора у задачи, поэтому перенос данных не требует изменения схемы.
- Тексты бейджей и описания для доступности следуют принятому в приложении языку интерфейса.

## Evidence — где находятся описанные дефекты

Раздел справочный: он фиксирует проверенные факты, на которых стоят требования, и не является частью требований.

| Дефект | Место | Факт |
|---|---|---|
| Закрытое состояние не сбрасывается при переносе на следующую дату | `shared/src/commonMain/kotlin/com/singularity/todo/core/database/Daos.kt` (обновление даты) | Обновление даты пишет только дату и время изменения; закрытое состояние не затрагивается. |
| Перенос при закрытии — «переезд», а не новое вхождение | `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/domain/usecase/CompleteRecurringTaskUseCase.kt` | Закрытие и перенос даты выполняются как две последовательные записи одного и того же ряда. |
| Признак повтора подставляется константой | `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/AgendaContent.kt` (сборка строки) | В строку задачи передаётся постоянное «задача не повторяется». |
| Проект в строке списка не передаётся | то же место | В строку задачи передаётся пустое значение проекта; имя проекта до строки не доходит. |
| Признак повтора в модели строки не заполняется | `shared/src/commonMain/kotlin/com/singularity/todo/feature/tasks/presentation/model/TaskUi.kt` | Модель строки имеет поле признака повтора, но приведение задачи к модели строки его не заполняет. |
| Бейдж секции рассчитывается, но не рисуется | `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/screen/AgendaContent.kt` | Рассчитанный признак строки не передаётся в компонент строки. |
| Список задач не имеет доступа к проектам | `shared/src/commonMain/kotlin/com/singularity/todo/feature/agenda/presentation/viewmodel/AgendaDeps.kt` | Зависимости списка не содержат доступа к проектам — бейдж проекта требует его добавить. |
| Существующий тест закрепляет дефект | `shared/src/commonTest/kotlin/com/singularity/todo/feature/tasks/domain/CompleteRecurringTaskUseCaseTest.kt` | Тест утверждает, что перенесённая задача остаётся закрытой; ожидание подлежит замене. |

---

## Change log

### 2026-10-25 — amendments from the Phase 0 research

The planning phase checked the specification against the code it describes and found
four places where the first draft was either under-specified or contradicted existing
behaviour. The requirements were amended rather than the design bent around them; the
reasoning is in `research.md` (D1, D2, D4, D8).

| Requirement | Was | Now | Why |
|---|---|---|---|
| FR-006 | future dates determined by «the rule and the horizon» | determined by «the rule and the series' position» | The horizon is measured from the current occurrence, so the position of the series is part of the input. |
| FR-007 | horizon is 14 days **from today** | horizon is 14 days **from the current occurrence** | A horizon measured from today makes the row set change with the calendar day, which needs a daily write to notice. Anchoring at the occurrence removes the day from the rule and the scheduler with it (research D2). |
| FR-009 | a series must not hold more than 30 occurrences | …and at the ceiling the oldest closed occurrence leaves the series, remaining an ordinary closed task | For a recurring series the ceiling cannot be a refusal — a daily task would stop recurring after thirty completions. It becomes a rotation instead (research D3). |
| FR-036 | a row with a rule, closed, and dated in the future must be reopened | a row with a rule is reopened **only when the series has no open occurrence** | A closed row with a past date is legitimate history; reopening it would resurrect finished work. The old wording only named the future-dated case and left the past-dated one ambiguous (research D4). |
| FR-042 | the calendar export must not produce more than one event per series | the calendar export follows the existing «one row — one event» rule; a recurring series gets no special handling | ADR `2026-10-22-task-series-materialized-copies` already makes a series visible in the calendar as several events, and both export paths map every row to its own event. Honouring the first wording would have made the calendar disagree with the agenda (research D8). |
| SC-010 | the number of calendar events per series does not grow | the number of calendar events per recurring series equals the number of its open occurrences | Follows from FR-042. |
| FR-043…FR-045 | — | new | The three rules the research had to settle and the first draft left implicit: where the series' position comes from (FR-043), why a rare rule still gets a next occurrence (FR-044), and that a closed occurrence stays in the series until the ceiling (FR-045). |

### 2026-10-25 — second amendment: the base decides the anchor

Reading `RecurrenceSpec` while checking the plan against the code showed that the
first draft's anchor rule — "the series' position" — did not say **which date** the
rule is measured from, and the answer is not the same for every base.

| Requirement | Was | Now |
|---|---|---|
| FR-043 | the series' position is its earliest open occurrence | the position is its earliest open occurrence, **and the date the rule is measured from is the occurrence's date for a "from due date" rule and its completion date for a "from completion" rule** |

The reason it matters: `FROM_COMPLETION` **is the default base** — the picker builds it
that way (`RecurrencePickerSheet.kt:105`) and the formatter calls it "the most common
mode" (`RecurrenceFormatters.kt:42`). A design that measured every rule from the
occurrence's date would compute the wrong dates for the common case.

The consequence is stated rather than hidden: under "from completion", closing an
occurrence late moves the visible future by exactly how late the close was, because
that is what the base means. The move is a change of date on the rows that survive,
not a rebuild, so a future row's own reminder or checklist is not lost to it. Under
"from due date" the window never moves. The reasoning is in `research.md` (D10).

The amendments do not change the product the user asked for: future occurrences become
real rows, the row shows a repeat badge, the row shows a project badge. They make the
specification say precisely how the series behaves rather than approximately.

### 2026-10-25 — third amendment: what an independent review found

A second agent reviewed the design against the code and returned `fail`. The factual
base held — every cited `file:line` checked out — but four things contradicted each
other or the code, and they are fixed here and in `research.md` (D10, D11), the ADR,
the contracts and the OpenSpec change.

| Requirement | Was | Now | Why |
|---|---|---|---|
| FR-005 | the series holds *the same* future dates after a close | the series holds **no fewer** future dates after a close | Under "from completion" a late close re-plans the window, so the dates move. The count is what must not shrink (SC-002 already said so). |
| FR-010 | no occurrence past the horizon is materialised | …**except the first date past it**, which is materialised while the cap of ten future occurrences leaves room and the rule lasts | FR-010 contradicted FR-044. A yearly rule has no date inside a two-week horizon, and without this clause it would have no next occurrence at all. |
| FR-034 | legacy repeating tasks are brought into series | …**for the active profile**; another profile is repaired at the next launch with that profile active | The repair runs at launch against the active profile; the app is multi-profile and the requirement did not say so. |
| FR-046 (new) | — | new | The date the rule is measured from, per base. "From completion" measures from the close; "from due date" and the catch-up base measure from the schedule. |
| FR-047 (new) | — | new | What replaces the archiving that happens today when a rule runs out: the occurrence is **closed** and the series stops growing. Nothing is archived — a closed occurrence is already out of the active list, and archiving a row the user closed would move finished work into the trash. |
| User Story 1, scenario 3 | the series gains an occurrence as the horizon advances, without a user action | the series gains an occurrence as an occurrence is consumed | The horizon is measured from the current occurrence, not from today, so the window does not move on its own — there is no scheduler to move it. |

**A third defect surfaced.** The create screen's rule never reached the row: the create
path goes through `TaskSeriesPlanner.plan`, which sets `recurrence = null` on every row
it writes — including the single row of a one-date draft
(`TaskSeriesPlanner.kt:102`). A task created with "repeat every week" therefore came
out with no rule at all, silently. The create path is now named as a trigger of the
materialisation, and the rule-versus-picked-dates conflict has one answer: the rule
loses (FR-048), which is what the create screen already tells the user
(`RECURRENCE_DROPPED_MESSAGE`).

**A comment that reads as a promise.** The rule's own documentation calls `CATCH_UP`
"like FROM_COMPLETION" (`RecurrenceSpec.kt:27-28`), while the implementation anchors
it on the due date (`CompleteRecurringTaskUseCase.kt:97`). The implementation is right
— catch-up fills the gaps between *scheduled* occurrences — and the comment is
corrected to match it. The behaviour does not change.

### 2026-10-25 — fourth amendment: what the second review round found

A second agent reviewed the fixed design and returned `fail` again, narrowly: no
blocking finding, three important ones and eight minor. They were closed, and the
closing work was reviewed a third time.

| Requirement | Was | Now | Why |
|---|---|---|---|
| FR-010 | the first date past the horizon is materialised **always** | …**while the cap of ten future occurrences leaves room** and the rule lasts | FR-010 contradicted FR-044 and FR-008 at once: a daily rule fills the cap inside the horizon, so "always" demanded an eleventh future row that the cap forbids. |
| FR-034 | the other profiles are repaired **when they are activated** | they are repaired **at the nearest launch with that profile active** | No profile-switch trigger is designed, and the second review was right that a promise without a trigger is a promise nobody keeps. |
| FR-043 | the position is the earliest open occurrence | …and **at a close, the position is the occurrence being closed** | An implementer who re-read the series *after* the close would anchor on the newly materialised row and plan a window one period too far out. |
| FR-048 (new) | — | new | The rule-versus-picked-dates answer, which the first draft left in prose only: the rule loses, and the user is told. |

**A false claim about the code, introduced by the fix.** The design had said that the
materialisation's two writes have a fixed order, because `repo.update` triggers
`realignSeries`, which re-reads the members. That was false: the row being written is
the *detached* one, whose `seriesId` is null, and `realignSeries` returns at once for
such a row (`TaskRepositoryImpl.kt:349`). It never re-reads the members here. **Either
order is safe**, and the constraint that actually decides the shape is the one that was
already there: `createSeries` refuses a batch carrying two series ids. The claim was
repeated in five artefacts and is corrected in all five — it was the justification an
implementer would have leaned on.

**Six requirements had no scenario** — FR-013, FR-014, FR-016, FR-017, FR-018 and
FR-041. They now have one each in the OpenSpec delta.

### 2026-10-25 — fifth amendment: the upstream capability `task-series` landed

The branch was rebased onto `origin/main`, which brought a capability this change did
not know about: `openspec/specs/task-series/spec.md`, the owner's account of what a
series is. Three of its requirements contradict this design literally, and one of them
describes an operation that this design had not thought about.

| Upstream requirement | Says | This design | Resolution |
|---|---|---|---|
| REQ-TS-001 | a single date is an ordinary task and must stay one | a single date **with a rule** becomes the current occurrence of a series | The exception is stated in both: a single *picked* date stays one, and a single date *with a rule* becomes a recurring series. |
| REQ-TS-005 | a series of more than thirty dates is refused | a recurring series rotates at the ceiling | The refusal stays for a series of picked dates; the recurring series rotates. Both are stated. |
| REQ-TS-007 | a repeat rule and a set of dates must not be in force on the same task | the rule loses against picked dates (FR-048) | The prohibition is between a rule and **picked** dates. A recurring series carries the rule on every member, and its dates are the rule's own occurrences. |
| `tasks`: «One date of a series can leave it» | the occurrence keeps everything but the series id | — | The operation now has a defined answer for a recurring series: the occurrence that leaves also loses the rule (FR-049), and so does the row that stays behind when the series ends. |
| `tasks`: «Two series can be joined» | the other series' rows take the target's id and fields | a series has one kind, and the kinds never mix | The join refuses a mixed pair — a recurring series with a series of picked dates, and two recurring series whose rules differ — and names the reason (FR-050). A join of two series of the same kind is unchanged. |
| `tasks`: «A recurring close is refused while a prerequisite is open» | the series is moved to its next date | the closed occurrence stays on its date and the next occurrence is a new row | The refusal still writes nothing; the advance is by materialising, not by moving. The upstream scenario names are kept verbatim — the validator refuses a MODIFIED block that drops a scenario — and their bodies say what «moves» now means. |

**FR-049 is new.** The operation «take one occurrence out of the series» exists in the
app (`DetachTaskFromSeries.kt:47-53`) and, under materialisation, it would have left
behind a row that still carries a rule — a row that says «repeats» and never repeats.
The occurrence that leaves a recurring series therefore loses the rule with the series
id; the rest of the series keeps it. The same goes for the row that stays behind when
the series ends (`DetachTaskFromSeries.kt:52-54`): a rule on a row that stands alone is
the shape the launch repair adopts into a new series, so the detach would undo itself at
the next launch.

**FR-050 is new.** The join of two series is the one operation that could make a series
be two things at once. Under the two kinds it refuses a mixed pair and names the reason.

**The change now specifies two capabilities.** Its delta covers `tasks` (the
occurrences) and `task-series` (the model of the series itself), and the `task-series`
delta adds two requirements of its own: a series has one kind, and the kind is decided
by where its dates come from (REQ-TS-009); a recurring series carries the rule on
every member (REQ-TS-010).

The line references to `AgendaContent.kt` moved by one with the rebase and were
corrected in every artefact: the row is built at `:401`, `project = null` is at `:404`,
`isRecurring = false` is at `:408`.
