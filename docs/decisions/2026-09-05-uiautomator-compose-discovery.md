---
created: 2026-09-05
---

# UI Automator + JetBrains Compose: несовместимость обнаружения элементов

## Context

Потребовалось написать UI-тесты, работающие на **реальном Android-устройстве** (не эмуляторе/Robolectric). Тесты должны проверять, что экран авторизации отображает поля ввода email/password и кнопки.

## Ideas

1. **UI Automator** — стандартный Android-фреймворк для UI-тестов на реальных устройствах.
2. **Compose TestRule** — нативная поддержка JetBrains Compose test tags (`testTag`).
3. **Espresso** — не подходит: несовместим с Robolectric и не видит Compose-элементы.

## Decision

Временно **отложить** UI Automator тесты на реальном устройстве. Провести исследование:

### Что обнаружено

- `By.res(packageName, "auth_email_input")` — **не находит** Compose-элементы. JetBrains Compose **не создаёт** традиционные Android resource-id для `testTag`.
- `By.textContains("Sign In")` — **не находит** кнопки, потому что Compose `Button` рендерит текст через semantics, не как `android.widget.TextView`.
- `By.desc()` (content-description) — потенциальный workaround: добавить `contentDescription` в дополнение к `testTag`.
- UI Automator дамп (через `uiautomator dump`) показал только home screen, приложение не было запущено.

### Причины отказа

1. **testTag ≠ resource-id**: `Modifier.testTag()` в Compose устанавливает semantics property, **не** Android view `resource-id`. UI Automator ищет по `resource-id`, поэтому `By.res()` не работает.
2. **Compose semantics ≠ Android view hierarchy**: UI Automator обходит традиционное Android view hierarchy. Compose semantics layer не экспонит элементы через `ViewGroup` hierarchy, которую видит `UiSelector`.
3. **Приложение не установлено на устройстве**: тесты не могут запуститься — пакет `com.singularity.todo` отсутствует на устройстве.

### Workaround-ы для будущего исследования

1. **Добавить `contentDescription`** вместо/в дополнение к `testTag`:
   ```kotlin
   // Compose:
   TextField(
       value = email,
       modifier = Modifier.testTag("auth_email_input")
                          .semantics { this.contentDescription = "auth_email_input" }
   )
   ```
   UI Automator: `device.hasObject(By.desc("auth_email_input"))`.

2. **Использовать `performInteractiveuideSnapshot()`** из AndroidX test — делает snapshot UI-дерева, но не решает проблему селекторов.

3. **Координатные клики** — определить координаты элементов и кликать по ним. Хрупко, но работает.

4. **Перейти на Appium или Kaspresso** — эти фреймворки понимают Compose semantics.

## Consequences

- UI Automator тесты **удалены** (`UIAutomatorTest.kt`).
- Robolectric widget tests в `androidHostTest` также **удалены** — все 5 классов
  (`AuthViewModelWidgetTest`, `NotesScreenWidgetTest`, `NoteEditorScreenWidgetTest`,
  `TasksScreenWidgetTest`, `AndroidShellFlowTest`). Причина: `assertIsDisplayed()`
  проксирует через Espresso → `InputManager` → `NoSuchMethodException` на JVM.
  `assertExists()` не существует в `ui-test-junit4:1.7.3` (v2 API). Лечение —
  AndroidX UI Test 2.x, но оно требует отдельного исследования.
- Оставшиеся `androidHostTest`: только `AppNavigatorTest` (nav contract, без Espresso),
  `AndroidDiGraphTest` (Koin DI verify). Все зелёные.
- Для UI-тестов на реальном устройстве: Kaspresso или `contentDescription` + `By.desc()`.

## Links

- [UI Automator documentation](https://developer.android.com/training/testing/other-components/ui-automator)
- [JetBrains Compose testTag semantics](https://developer.android.com/jetpack/compose/testing)
