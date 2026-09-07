---
title: "Settings layout fixes, reactive dark theme, LLM providers"
date: 2026-09-07
tags: [settings, ui, di-graph]
---

## Context

SettingsScreen имел несколько UX-багов: элементы не кликались (Row без fillMaxSize), темная тема не реагировала на переключатель в настройках (SingularityTheme получал дефолтные параметры), sub-screens не скроллились, API key было невозможно посмотреть, tab icons были невыровнены, LLM provider не имел dropdown с Anthropic и функцией Fetch models.

## Decision

**Layout**: `Row` в `SettingsContent` получил `modifier.fillMaxSize()`, `Box` — `fillMaxHeight()`. Все 6 sub-screens получили `verticalScroll(rememberScrollState())`.

**Dark theme**: `App.kt` стал собирать `settings.darkTheme` и `settings.accentColor` через `collectAsState` и передавать в `SingularityTheme(darkTheme, accent)`. `SingularityTheme` получил `background`/`surface` в оба `ColorScheme`.

**API key**: `OutlinedTextField` получил `trailingIcon` с `IconButton` переключающим `passwordVisible: Boolean` и визуальную трансформацию `PasswordVisualTransformation()` ↔ `VisualTransformation.None`.

**Tab icons**: `SettingsNavRail` каждая иконка обёрнута в `Box(48.dp, CircleShape background)` — выбранный таб получает `primaryContainer` background, иконки центрированы.

**LLM providers**: `LlmProvider` получил `ANTHROPIC_COMPATIBLE`. `TextGenPort` получил `listModels(baseUrl, apiKey)` через `HttpURLConnection GET /models`. `SettingsUiState` получил `aiModels`, `isFetchingAiModels`, `fetchAiModelsError`. `SettingsIntent` получил `FetchAiModels`. `AiProviderSettingsScreen` получил `ExposedDropdownMenuBox` для выбора модели и кнопку "Fetch models".

## Rationale

- Row без fillMaxSize — `weight` вложенных Box не работает без заполнения родителя. Аналогично для Box без fillMaxHeight.
- `isSystemInDarkTheme()` в SingularityTheme игнорировал DataStore. Решение — инжект `SettingsRepository` в App и передача реальных значений.
- API key toggle — стандартный Android pattern с `VisualTransformation` + `IconButton`.
- CircleShape background — Material3 Tab не используется, но иконки в NavigationRail требуют визуального выделения выбранного.
- `listModels` — HTTP GET на `/models` стандартен для OpenAI-compatible API. `HttpURLConnection` (JDK) не требует внешних зависимостей.

## Consequences

- `App.kt` инжектит `SettingsRepository` через Koin — это нормально, Koin доступен в Common startup.
- `TextGenPort.listModels` — добавлен в интерфейс, реализация в `KoogAgentService` и `FakeTextGen`.
- Все 6 sub-screens имеют `verticalScroll` — контент больше не обрезается.
- `SettingsNavRail` Column теперь содержит Box с CircleShape — Layout инлайн, не refactor.

## Links

- Commits: `fix(settings)`, `feat(settings)`, `feat(backup)`
- Files: `App.kt`, `SingularityTheme.kt`, `SettingsScreen.kt`, `AiProviderSettingsScreen.kt`, `LlmProvider.kt`, `TextGenPort.kt`, `KoogAgentService.kt`
