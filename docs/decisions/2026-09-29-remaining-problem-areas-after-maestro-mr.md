---
title: "Оставшиеся проблемные места после MR про Maestro UI-тесты"
date: 2026-09-29
status: deferred
---

# Оставшиеся проблемные места после MR про Maestro UI-тесты

## Context

По итогам MR `feat/maestro-ui-flows` (Maestro-инфраструктура + фиксы крашей,
8/8 flows зелёные) аудит выявил десять проблемных мест. Три были исправлены в
том же MR: Android DI-тест, `just setup-hooks`, актуализация DIGEST и ADR-ссылок.
Этот ADR фиксирует остальные семь — с доказательствами, влиянием и оценкой
усилий, чтобы каждый можно было взять в работу отдельно.

## Записи (исправлены в MR, здесь не описываются)

- **Android DI-граф не проверялся тестами** → исправлено:
  `AndroidKoinGraphValidationTest` в `androidHostTest` (Robolectric). Поймал бы
  Pomodoro-краш до релиза. Открытие попутно показало: `useJUnitPlatform` +
  отсутствие `junit-vintage-engine` молча пропускало все JUnit4-классы в
  `androidHostTest` — причина была не в коде теста.
- **`just setup-hooks` молча отключал hooks** → исправлено: рецепт предпочитает
  версионируемый `.githooks/`, падает громко при отсутствии hook'ов.
- **DIGEST устарел** → исправлено: пересобран, ссылки на удалённый
  `AppDestinationSerializers` помечены superseded в
  `2026-09-27-nav3-startroute-invariant.md`.

## Остальные записи

### 1. Реестр TestTags: 21 константа из 46 никогда не применена

**Доказательство:** grep по `TestTags.<CONST>` вне `TestTags.kt` — 0 вхождений
для 21 константы (`TASKS_LIST`, `TASKS_FILTER_CHIPS`, `TASKS_SEARCH_BAR`,
`TASK_EDITOR_DESCRIPTION_INPUT/DUE_DATE/DUE_TIME/REMINDER/CHECKLIST*/ATTACHMENTS*/DELETE/ERROR/NOTIFICATION_HOST`,
`TAGS_FAB`, `NOTES_FAB`, `NOTE_EDITOR_DELETE`, `NOTE_EDITOR_MARKDOWN_TOOLBAR`,
`PROJECT_DETAIL_TOP_BAR`, `DESKTOP_SIDEBAR`). Ровно это породило исходные
падения flows: константа есть — `Modifier.testTag` нет.

**Влияние:** каждый, кто пишет UI-тест, доверяет реестру и получает хрупкий или
неадресуемый селектор. Реестр actively врёт о состоянии кода.

**Предлагаемое решение:** либо дотагать компоненты по одному экрану за раз
(предпочтительно — тег появляется вместе с flow'ом на него), либо перенести
нереализованные константы в комментарий-план в KDoc объекта. Половинчатое
состояние — худший вариант.

**Оценка:** по 10–20 минут на константу вместе с flow'ом; чистка — 15 минут.

### 2. `local.properties` отсутствует в новых worktrees

**Доказательство:** `git check-ignore -v local.properties` → `.gitignore:18`;
Gradle в свежем worktree падает с `SDK location not found`.

**Влияние:** каждый новый worktree требует ручного копирования файла, о чём не
написано нигде — обнаруживается по непонятной ошибке Gradle.

**Предлагаемое решение:** задокументировать в
`singularity-todo-worktree-isolation` рядом с gradle-wrapper-советом, либо
добавить копирование в рецепт создания worktree. Сам `local.properties`
версионировать нельзя (машинно-специфичен).

**Оценка:** 10 минут.

### 3. Брошенные worktrees и ветки

**Доказательство:** `git worktree list` — 10 записей; `rebase-attempt`
(23.09), `feature/mr-1-url-links` и `feature/mr-2-plannedfor-inbox` (24.09)
не двигались ≥5 дней.

**Влияние:** каждая держит ветку и ~1–2 ГБ build-артефактов; stale-ветки
запутывают при поиске «а где чинили X».

**Предлагаемое решение:** владелец подтверждает смерженность →
`git worktree remove` + `git branch -d`. Не делать это автоматически.

**Оценка:** 10 минут + подтверждение владельца.

### 4. Deprecated `AppDestination.Inbox/Today/Upcoming` всё ещё в навигации

**Доказательство:** `AndroidNavEntries.kt:46,59` — `entry<AppDestination.Inbox>`,
`entry<AppDestination.Today>` при `@Deprecated` с заменой на
`AgendaGraph(AgendaStartRoute.X)`. Аналогично `TasksByProject` (строка 184).

**Влияние:** двойная система маршрутов — источник тонких багов класса недавнего
краша сериализаторов.

**Предлагаемое решение:** отдельный рефакторинг-MR: перевести entry-провайдер
на `AgendaGraph(...)` и удалить `@Deprecated` объекты (заодно уменьшится
`AppDestinationSerializers`-поверхность — она теперь sealed-автоматическая).

**Оценка:** 1–2 часа с прогоном flows.

### 5. `testTagsAsResourceId` требуется на каждом новом окне

**Доказательство:** root-флаг не достаёт до `ModalBottomSheet` (отдельное окно) —
пришлось протаскивать `modifier` параметром в общий `MenuBottomSheet`
(`AndroidShellNav3.kt`). Все будущие диалоги/sheets унаследуют эту ловушку.

**Влияние:** каждый новый overlay требует ручного знания о тонкости; забытый —
невидимые для автоматизации теги и красные flows без видимой причины.

**Предлагаемое решение:** задокументировать в `singularity-todo-maestro-flows`
(частично сделано) + при появлении второго случая завести общий
`Modifier.windowTestTags()`-хелпер или вынести правило в `TestTags.kt` KDoc.

**Оценка:** документация — 10 минут; хелпер — по ситуации.

### 6. Skill-имя `singularity-todo-test-tag-strategy` вводит в заблуждение

**Доказательство:** скилл описывает JUnit `@Tag("slow")` для Gradle-фильтрации,
не `Modifier.testTag`. При аудите TestTags я потратил время на ложный след.

**Влияние:** невинное, но систематическое — агент ищет «test tag» и находит не то.

**Предлагаемое решение:** переименовать в
`singularity-todo-junit-slow-tags` с redirect-заглушкой в старом имени, либо
добавить в description строку «not about Modifier.testTag» (сделано в
`maestro-flows` скилле, но сам след остался).

**Оценка:** 15 минут + обновление ссылок.

### 7. Эмуляторный SIGSEGV в gfxstream — обход, не фикс

**Доказательство:** ADR `2026-09-28-emulator-gfxstream-colorbuffer-segv`;
coredump-stack в `TextureResize` → `strlen`; лечится отключением soft-IME в
`scripts/run-maestro.sh`. Эмулятор 37.3.1 — последняя доступная версия
(проверено по всем трём фидам dl.google.com).

**Влияние:** flows с вводом текста зависят от обходного пути; включение IME
(тесты imeAction/composition) возвращает риск краша.

**Предлагаемое решение:** репорт в AOSP issuetracker со стеком и конфигом
(Renoir, Fedora 44, Mesa 26.2.3, kernel 7.2.7, emulator 37.3.1) — стек уже
снят. Альтернатива — физическое устройство через
`singularity-todo-adb-workflow`.

**Оценка:** репорт — 30 минут; устройство — по наличию.

### 8. CI не запускает UI-тесты и эмулятор

**Доказательство:** `.github/workflows/ci.yml` — `test-and-check`, `kover-report`,
`mcp-server-check`; Android-эмулятора нет; `assembleDebug` под
`continue-on-error: true`.

**Влияние:** 8/8 зелёных flows живут только на dev-машине; регрессии навигации
вроде Pomodoro-краша ловятся вручную.

**Предлагаемое решение:** Maestro Cloud job (`maestro cloud`) по тегу `smoke`
или self-hosted runner с эмулятором. Начинать с Cloud — не требует
инфраструктуры, нужен API key.

**Оценка:** Cloud — 1–2 часа; self-hosted — полдня.

### 9. DIGEST превышает собственный лимит размера

**Доказательство:** `scripts/check-doc-sizes.py:27` — `DIGEST_MAX = 1500`;
фактически 1972 строки (на `main` было 1949). `just docs-audit` завершается с
exit 1. Причина структурная: каждый slug дублируется в «Index (slug -> tags)» и
«Active entries» (~600 строк дублей при 303 записях).

**Влияние:** docs-audit красный всегда — сигнал перестаёт что-либо значить
(cry-wolf), новые реальные нарушения размера не видны на фоне.

**Предлагаемое решение:** убрать одну из двух секций (или слить теги в
«Active entries»), уложиться в лимит на годы вперёд; отдельно пересмотреть
политику «critical bullets» — они тоже растут линейно.

**Оценка:** 1 час (скрипт + регенерация + ручная проверка читаемости).

## Decision

Все записи — **deferred**: каждая самодостаточна, не блокирует MR про
Maestro и требует либо владельца, либо отдельного MR. Порядок, в котором
стоит браться: **9** (возвращает сигнал docs-audit) → **4** (снимает класс
навигационных багов) → **1** (делает реестр правдивым) → **8** (CI) →
остальные по мере касания.

## Consequences

- Этот ADR — единственная запись о семи проблемах; фикс каждой завершается
  правкой статуса здесь.
- `docs-audit` остаётся красным из-за п.9 до отдельного фикса — это
  задокументированное известное состояние, не регрессия MR.

## Links

- `docs/decisions/2026-09-28-android-cold-start-nav3-serializer-crash.md` — краш навигации
- `docs/decisions/2026-09-28-emulator-gfxstream-colorbuffer-segv.md` — эмуляторный SEGV
- `docs/decisions/2026-09-28-setup-hooks-broken-githooks-path.md` — сломанный setup-hooks
- `scripts/check-doc-sizes.py` — источник лимита DIGEST
