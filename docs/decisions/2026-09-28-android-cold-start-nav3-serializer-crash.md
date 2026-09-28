---
title: "Android cold start крашится: SerializerAlreadyRegisteredException в navSavedStateConfig"
date: 2026-09-28
status: open
---

# Android cold start крашится: `SerializerAlreadyRegisteredException` в `navSavedStateConfig`

## Context

Обнаружено в Phase 3 плана `2026-09-28-maestro-ui-flows` (Maestro UI-тесты):
первый же flow (`smoke/01-launch-today.yaml`) не может дождаться `nav_tab_today`,
потому что приложение падает при холодном старте.

Воспроизводится детерминированно: `launchApp` → `FATAL EXCEPTION` → процесс мёртв.

```
kotlinx.serialization.modules.SerializerAlreadyRegisteredException:
    Serializer for class androidx.navigation3.runtime.NavKey already registered
    in the scope of class androidx.navigation3.runtime.NavKey
    at kotlinx.serialization.modules.SerializersModuleBuilder.registerPolymorphicSerializer(SerializersModuleBuilders.kt:203)
    at kotlinx.serialization.modules.PolymorphicModuleBuilder.buildTo(PolymorphicModuleBuilder.kt:167)
    at com.singularity.todo.feature.nav.Nav3SavedStateKt.navSavedStateConfig$lambda$0(Nav3SavedState.kt:88)
    at androidx.savedstate.serialization.SavedStateConfigurationKt.SavedStateConfiguration(SavedStateConfiguration.kt:135)
    at com.singularity.todo.feature.nav.Nav3SavedStateKt.navSavedStateConfig(Nav3SavedState.kt:35)
    at com.singularity.todo.feature.nav.Nav3StateFactory_androidKt.rememberNav3State(Nav3StateFactory.android.kt:38)
    at com.singularity.todo.AppKt.AppContent(App.kt:88)
```

Цепочка: `MainActivity` → `App()` → `AppVersionGateScreen` (кейс `Allowed`) →
`AppContent` → `rememberNav3State` → `navSavedStateConfig(*AppDestinationSerializers)`.

Краш происходит **при построении** `SerializersModule`, до первой сериализации —
то есть на любом холодном старте, независимо от состояния БД.

## Что проверено

- **Не регрессия текущей ветки.** Diff `feat/maestro-ui-flows` не затрагивает ни
  `feature/nav/`, ни сериализацию: изменены только `TestTags.kt`,
  `ProjectEditorScreen.kt` (testTag'и), `MenuBottomSheet.kt` (источник тега),
  `AndroidShellNav3.kt` (testTag). `git diff main -- feature/nav/` пуст.
- **Не дубликаты в списке.** `AppDestinationSerializers` содержит 24 элемента и
  24 различных `descriptor.serialName` — проверено тестом, ни одна пара не
  совпадает.
- Значит, конфликт возникает не на уровне имён, а на уровне того, как
  `PolymorphicModuleBuilder.buildTo()` оптимизирует (`PolymorphicSerializer.optimize()`)
  каждый переданный сериализатор. Наиболее вероятная причина: один из элементов
  списка — сериализатор **sealed-иерархии**, который при `optimize()` разворачивается
  в плоский набор подкласса и претендует на ключ базового типа, уже занятый другим
  элементом. Кандидаты — `AgendaStartRoute` (`@Serializable sealed interface :
  NavKey`) и устаревшие `AppDestination.Inbox/Today/Upcoming`
  (`@Deprecated data object` внутри `sealed interface AppDestination : NavKey`).

Полностью довести диагностику до конца в этой ветке не удалось: black-box проба
регистрации на JVM упёрлась в несовпадение API `polymorphic`/`subclass` между
версиями kotlinx-serialization.

## Idea

1. Чинить здесь, в ветке Maestro-тестов.
2. Передать владельцу навигации — есть уже созданный worktree
   `/home/max/work/singularity-nav3-empty-entries` на ветке
   `fix/nav3-empty-entries-crash` (пока без коммитов поверх `main`).
3. Откатить `@Serializable` на sealed-иерархиях `NavKey`.

## Decision

**Вариант 2 — передать владельцу навигации. Не чинить в этой ветке.**

Обоснование:

- Это production-баг в навигации, а не в тестовой инфраструктуре. Смешивать его
  с MR про UI-тесты — ровно тот drive-by fix, от которого предостерегает
  ревью-план.
- Баг блокирует **всё** Android-направление, а не только Maestro: приложение
  не запускается на холодном старте вообще. Это самостоятельный инцидент.
- Для навигации уже есть выделенная ветка; дублировать работу двух исполнителей
  в одном репозитории — прямой путь к конфликту.

**Maestro-инфраструктура при этом остаётся валидной** — flows корректны, просто
не могут выполняться, пока Android не стартует. Как только краш починен, набор
запускается без изменений.

## Rationale

Грилл-ревью плана заранее предполагало, что UI-работа может упереться в
блокеры, и предписало: критические баги чинить сразу, остальное — ADR. Здесь
важнее граница между «чинить» и «зафиксировать»: баг реален и критичен, но он
**не мой** и не в границах этого MR. Правильный ход — передать его с точными
доказательствами, а не чинить второпях и рисковать конфликтом с чужой веткой.

Отдельно: этот баг — лучшее оправдание самой работы. До Maestro UI-тестов
холодный старт Android был непроверяемым: `connectedDebugAndroidTest` в CI не
запускается, а локальные smoke-тесты — заглушки. Первый же настоящий
end-to-end запуск сразу нашёл дефект, который жил незамеченным.

## Consequences

- **Все 8 Maestro flows заблокированы** до фикса краша. Инфраструктура
  (flows, helpers, `scripts/run-maestro.sh`, just-recipe, skill) готова и
  запустится сразу после фикса — правок в YAML не потребуется.
- `RUN_MAESTRO=1 ./check.sh` добавлен как **opt-in**, что в этих обстоятельствах
  оправдано: дефект не должен ронять pipeline по умолчанию.
- Нужен отдельный MR/инцидент на краш навигации.
- `local.properties` отсутствует в новом worktree (`gitignore`) — Gradle падает с
  «SDK location not found». Скилл `singularity-todo-worktree-isolation` этого не
  упоминает; см. ADR про `setup-hooks` там же.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/Nav3SavedState.kt:35,88`
- `shared/src/androidMain/kotlin/com/singularity/todo/feature/nav/Nav3StateFactory.android.kt:38`
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AppDestination.kt:362` (`AppDestinationSerializers`)
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/nav/AgendaStartRoute.kt` (sealed-иерархия-кандидат)
- Ветка-кандидат на фикс: `fix/nav3-empty-entries-crash`
- ADR про сломанный `setup-hooks`: `2026-09-28-setup-hooks-broken-githooks-path.md`
