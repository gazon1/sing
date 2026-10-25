# Implementation Plan: Pull-to-refresh — синхронизация и перенос завершённого в архив

**Branch**: `015-pull-refresh-sync-archive` | **Date**: 2026-10-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/015-pull-refresh-sync-archive/spec.md`

## Summary

Один жест на каждом экране, который показывает данные пользователя: потянуть список
вниз — и приложение переносит все завершённые задачи в архив, синхронизируется с
сервером и сообщает, что произошло.

Техническая опора — **один владелец запуска**. Новый `RefreshCoordinator` в
`core/refresh/` построен по образцу уже существующего `SyncCoordinator`: conflating-канал,
один потребитель, `suspend fun request(): RefreshOutcome`. Все восемнадцать экранов
приходят к нему через один общий composable, который поднимает общий ViewModel
обновления и показывает индикатор. Ни один из восемнадцати существующих ViewModel
экранов не меняется: обновление — чужая подсистема, и её состояние в списке задач
месту нет.

Два шага одного запуска независимы: сбой синхронизации не отменяет перенос в архив
(и наоборот). Шаг архивирования идёт **первым** — потому что он кладёт архивированные
задачи в очередь отправки, и тогда состояние архива уходит на сервер в рамках того же
жеста, а не при следующей плановой синхронизации.

Порядок работы: общее ядро → один экран как образец → остальные экраны.

## Technical Context

**Language/Version**: Kotlin 2.4 (Multiplatform), JVM toolchain 21.

**Primary Dependencies**: Compose Multiplatform 1.12.0 (`org.jetbrains.compose.material3:material3:1.12.0-alpha03`) · Koin 4.x · Room · Nav3 · kotlinx.coroutines · kotlinx.serialization. API жеста — `androidx.compose.material3.pulltorefresh` (`PullToRefreshBox`, `PullToRefreshDefaults.Indicator`); наличие проверено по содержимому артефакта в кэше Gradle.

**Storage**: Room поверх SQLite. **Схема не меняется** — перенос работает с уже существующими `completed_at` и `archived_at`, синхронизация — с уже существующей очередью отправки. Миграций нет, экспорт схемы не перевыпускается.

**Testing**: `:shared:jvmTest` (внутри выполняется `commonTest`) и `:desktopApp:test` для тестов настольного интерфейса; fakes вместо моков; виртуальное время через `advanceUntilIdle()`; теги `fast`/`slow` с под-тегом из закрытого словаря.

**Target Platform**: Android и настольное приложение (JVM). iOS в проекте нет.

**Project Type**: KMP-приложение (мобильное + настольное).

**Performance Goals**: отчёт об обновлении появляется не позднее чем через 3 секунды после жеста для профиля до 500 задач; прокрутка во время обновления не блокируется; одно обновление — один цикл синхронизации.

**Constraints**: ни `stateIn`, ни `viewModelScope`, ни `runBlocking` в production-коде · ядро не берёт порты фич · существующие ViewModel экранов не меняются · шаг архивирования идёт раньше шага синхронизации · жест требует прокручиваемого содержимого · каждое обновление показывает отчёт.

**Scale/Scope**: 18 экранов в охвате · 1 новый пакет в ядре (`core/refresh/`) · 1 новый модуль DI · 19 функциональных требований · 1 изменение в порте архива.

## Constitution Check

*GATE: должен пройти до Phase 0. Перепроверен после Phase 1 — вторая таблица ниже.*

| Принцип | Требование | Статус | Доказательство |
|---|---|---|---|
| I. KMP Architecture — Android + JVM Desktop только | Фича работает на обеих платформах | PASS | Всё новое — в `shared/src/commonMain`; ни одного `expect`/`actual`; единственный шов проекта (`platformModule()`) не тронут. |
| II. ADR Before Code | Решениям дольше недели работы нужен ADR | PASS с обязательством | Фича вводит сквозную заботу (владелец обновления на всех экранах) и новый порт в ядре — это критерии «нужен ADR». Запланирован один ADR: владелец обновления, порядок «сначала архив», порт, который объявляет ядро, и разворот того, что архивирование — действие одного экрана. |
| III. Validate Early and Often | `singularity-adr validate` после правки ADR | PASS | Запускается сразу после написания ADR, до закрытия задач. |
| IV. Immutable Historical Record | Принятый ADR не редактируется | PASS | ADR новый; существующие не трогаются. ADR `2026-10-06-…` о движке без репозиториев не меняется — новая работа его соблюдает, а не отменяет. |
| V. CI Gates | ADR + openspec валидация до слияния | PASS | Оба запускаются; ветка пушится без `--no-verify`. |
| VI. MVI ViewModels | `StateFlow<SealedUiState>` + sealed Intent + внедрённый scope; без `stateIn`, `viewModelScope`, `runBlocking` | PASS | Новый `RefreshViewModel` наследует `MviViewModel`; scope внедряется; результат — одноразовое событие, состояние — `Idle`/`Refreshing`. |
| VII. Test Fakes | Fakes вместо моков; виртуальное время | PASS | Подменяются архиватор и фасад синхронизации; ни одного мока, ни одного реального `delay(N)`. |
| VIII. OpenSpec Workflow | Поведенческие изменения идут через proposal → specs → adr → tasks | PASS с обязательством | Один openspec-чандж на фичу; запись в реестре `.specify/issues/<slug>.yaml` создаётся сразу после создания ченджа. |

**Вердикт до Phase 0: PASS.** Необоснованных нарушений нет, поэтому `Complexity Tracking`
остаётся пустым.

### Перепроверка после проектирования

| Вопрос | Ответ |
|---|---|
| Дизайн ввёл новую сквозную заботу? | Да — владелец обновления. Он ограничен одним пакетом `core/refresh/` и не добавляет портов, которые пришлось бы реализовывать вне его. |
| Дизайн изменил контракт между слоями? | Да — ядро объявляет собственный узкий порт вместо того, чтобы взять порт фичи (D-11). Уровень зависимости не изменился: фича → ядро. |
| Нужно ли исключение из принципа? | Нет. |
| **Вердикт после проектирования** | **PASS** — без изменений. |

## Project Structure

### Документация (эта фича)

```text
specs/015-pull-refresh-sync-archive/
├── spec.md                        # спецификация (/speckit-specify)
├── plan.md                        # этот файл (/speckit-plan)
├── research.md                    # Phase 0 — 12 проверенных решений
├── data-model.md                  # Phase 1 — новые типы и инварианты
├── quickstart.md                  # Phase 1 — сценарии проверки
├── contracts/
│   ├── refreshable-content.md     # контракт жеста: что получает пользователь, что даёт экран
│   └── refresh-outcome.md         # контракт результата: словарь исходов и сообщения
├── checklists/
│   └── requirements.md            # чеклист качества спецификации (/speckit-specify)
└── tasks.md                       # Phase 2 (/speckit-tasks) — НЕ создаётся этой командой
```

### Исходный код (корень репозитория)

```text
shared/src/commonMain/kotlin/com/singularity/todo/
├── core/refresh/                          # НОВЫЙ пакет — вся новая логика обновления
│   ├── RefreshOutcome.kt                  # НОВЫЙ: archived: Result<Int> + sync: SyncOutcome
│   ├── RefreshUiState.kt                  # НОВЫЙ: Idle | Refreshing
│   ├── RefreshUiEvent.kt                  # НОВЫЙ: Finished(outcome) — одноразовое событие
│   ├── CompletedTaskArchiver.kt           # НОВЫЙ: узкий порт, который объявляет ядро (D-11)
│   ├── RefreshCoordinator.kt              # НОВЫЙ: единственный владелец запуска (D-01…D-04)
│   ├── RefreshViewModel.kt                # НОВЫЙ: общий MVI-VM, по одному на экран (D-09)
│   └── RefreshableContent.kt              # НОВЫЙ: общий composable — жест, индикатор, отчёт (D-06, D-07)
│
├── core/di/
│   ├── RefreshDiModule.kt                 # НОВЫЙ: fun refreshModule(): Module — привязки
│   └── Modules.kt                         # ИЗМЕНЁН: add(refreshModule()) в domainModule()
│
├── feature/archive/domain/port/
│   └── ArchiveRepository.kt               # ИЗМЕНЁН: наследует CompletedTaskArchiver (D-11)
│
└── feature/                              # ИЗМЕНЕНЫ: восемнадцать экранов — обёртка контейнера
    ├── agenda/presentation/screen/
    │   ├── AgendaContent.kt               # список повестки (Inbox/Today/Upcoming — один экран)
    │   ├── SavedAgendaListScreen.kt       # список сохранённых представлений
    │   └── SavedAgendaContent.kt          # результаты сохранённого представления
    ├── calendar/presentation/
    │   └── screen/CalendarScreen.kt       # календарь: якорь — сетка часов TimeGridView
    ├── pomodoro/PomodoroScreen.kt         # помодоро: вертикальной прокрутки не было — получает свою
    ├── projects/presentation/screen/
    │   ├── ProjectsScreen.kt              # вкладка «Планы»
    │   └── ProjectDetailBody.kt           # детальный проект
    ├── tasks/presentation/screen/
    │   └── TaskDetailContent.kt           # детальная задача: своя прокрутка уже есть в каркасе редактора
    ├── notes/presentation/screen/
    │   ├── NotesListScreen.kt             # список заметок
    │   └── NotePreviewScreen.kt           # заметка на просмотре
    ├── statistics/StatisticsScreen.kt     # статистика
    ├── search/SearchScreen.kt             # поиск
    ├── ai/chat/ChatScreen.kt              # ИИ-чат
    ├── ai/usage/AiUsageScreen.kt          # вне охвата — остаётся как есть
    ├── proposals/presentation/ProposalsScreen.kt
    ├── profile/ProfileSwitcherScreen.kt   # профили
    ├── archive/ArchiveScreen.kt           # архив (прежняя кнопка сохраняется — FR-019)
    └── tags/
        ├── TagsScreen.kt
        └── presentation/screen/TagGroupsScreen.kt

shared/src/jvmTest/kotlin/com/singularity/todo/
├── core/refresh/
│   ├── RefreshCoordinatorTest.kt          # НОВЫЙ: порядок шагов, слияние запросов, сбой сети
│   ├── RefreshViewModelTest.kt            # НОВЫЙ: состояние, событие, отчёт переживает уход
│   └── RefreshOutcomeMessageTest.kt      # НОВЫЙ: разбор результата в сообщение
└── ...

desktopApp/src/jvmTest/kotlin/com/singularity/todo/
└── core/refresh/
    └── RefreshableContentTest.kt          # НОВЫЙ: жест доходит до координатора; отчёт виден
```

**Решение по структуре.** Новая логика лежит в одном новом пакете `core/refresh/`, а
восемнадцать экранов получают по одной обёртке. Альтернативы и почему они отклонены —
в `research.md` (D-01, D-06, D-09, D-11).

### Что НЕ меняется

| Объект | Почему |
|---|---|
| Схема Room, экспорт схемы, миграции | Новых полей нет |
| `SyncOutcome` | Словарь исходов уже различает нужные случаи |
| Реализация архива | Уже делает нужное, включая постановку в очередь отправки |
| Восемнадцать существующих ViewModel экранов | Обновление — чужая подсистема (D-09) |
| Плановая синхронизация и её интервал | Обновление добавляется к ней |
| Настройки экранов, экраны вне охвата | По спецификации |

## Порядок работы

1. **Ядро** — `core/refresh/`: типы, порт, координатор, ViewModel, контейнер, модуль DI.
   Проверяется модульными тестами: порядок шагов, слияние запросов, сбой сети, отчёт.
2. **Один экран как образец** — повестка (`AgendaContent`). Проверяется тестом
   настольного интерфейса: жест доходит до координатора и отчёт появляется.
3. **Остальные экраны** — семнадцать обёрток. Проверяется сканером
   `scripts/find-unwired-surfaces.py`: контейнер вызывается из каждого экрана охвата.
4. **Экран без прокрутки** — вертикальную прокрутку получает помодоро, на своём
   корне; детальная задача уже имеет её в каркасе редактора. Проверяется отдельно,
   что содержимое не «прыгает».

## Наблюдения о гейтах (вне объёма этой фичи)

Два гейта на Konsist сканируют **ноль файлов** и потому всегда зелёные. Причина одна:
`KoFileDeclaration.name` в Konsist — имя файла **без** расширения, а фильтры ждут
расширения.

- `DiFacadeTest.kt:141` — `.filter { it.name.endsWith("DiModule.kt") }` не находит
  ничего, поэтому проверка «per-domain DiModule files declare no bindings» ничего не
  проверяет. Косвенное доказательство: `DiFacadeTest.kt:76` находит ровно один файл
  по условию `it.name == "Modules"` — то есть имя без расширения.
- `SingleOfKotlinDefaultsTest.kt:174` — `.filter { it.name.endsWith(".kt") }` с той же
  ошибкой; проверка «у `singleOf`/`factoryOf`/`viewModelOf` нет аргументов по
  умолчанию» не проверяет ничего.

Оба гейта — не часть этой фичи, и починка требует отдельного решения: правило
«per-domain DiModule files declare no bindings» в его нынешнем виде запретило бы
существующий в проекте способ (`single { }` внутри `fun xxxModule() = module { }`),
который и предписывает `AGENTS.md`. **Размещение новых привязок в этой фиче следует
`AGENTS.md`, а не мёртвому гейту:** отдельный файл `core/di/RefreshDiModule.kt` с
`fun refreshModule(): Module = module { … }` и одна строка `add(refreshModule())`
в `domainModule()`.

## Complexity Tracking

> Заполняется **только** если в Constitution Check есть нарушения, которые пришлось бы
> оправдывать. Нарушений нет — таблица пуста.
