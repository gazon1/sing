---
title: "SettingsRepository: remove dead flat API, keep AI and account"
date: 2026-09-22
status: accepted
tags: [settings, architecture, cleanup]
---

## Context

После Phase 1–5 рефакторинга (per-section repositories, marker interfaces, inline wrappers) в `SettingsRepository` остался **мёртвый flat API** — getters/setters для appearance, notifications, work schedule, greeting, default agenda view. Все потребители уже мигрировали на per-section repositories:

| Flat API | Потребитель (до) | Потребитель (после) |
|---|---|---|
| `darkTheme`, `accentColor`, `fontSizeScale` | `App.kt` | `AppearanceSettingsRepository` |
| `notificationsEnabled`, `notificationSound`, … | — (0 звонков) | `NotificationsSettingsRepository` |
| `workDayStartMinutes`, … | — (0 звонков) | `WorkScheduleSettingsRepository` |
| `greetingMorningEnd`, `greetingAfternoonEnd` | — (0 звонков) | `GreetingSettingsRepository` |
| `defaultSavedAgendaViewId` | — (0 звонков) | `DefaultAgendaViewSettingsRepository` |

При этом `companion object DataStoreSettingsRepository` с `val KEY = booleanPreferencesKey(...)` использовался **только** классом `SettingsDataStoreMigration` для маппинга legacy → namespace keys.

## Decision

### Удалить из `SettingsRepository` интерфейса

Flat getters/setters для: appearance, notifications, work schedule, greeting, default agenda view.

### Сохранить в интерфейсе

- **AI setters**: `setAiProvider`, `setAiModel`, `setAiBaseUrl`, `setAiSystemPrompt` — `AiSettingsStore.process()` вызывает их напрямую (`settings.setAiProvider(...)`).
- **`userId`**: `ProfileAwareCurrentUser` и `SettingsDataStoreMigration` читают/пишут этот flow.

### Сохранить в `DataStoreSettingsRepository`

- **`companion object`** со всеми `val KEY = preferencesKey(...)` — `SettingsDataStoreMigration` импортирует `DataStoreSettingsRepository.DARK_THEME`, `DataStoreSettingsRepository.USER_ID` и т.д. для построения map-а из legacy keys в namespaced. companion object остаётся **единым источником имён** для всех 17 keys; section repositories используют `nsKey()` на местах.

### Результат

`SettingsRepository` интерфейс: было ~80 строк (flat getters + setters для 5 секций), стало ~30 строк (AI setters + userId).

`DataStoreSettingsRepository`: companion object сохранён; flat getters/setters для appearance, notifications, work schedule, greeting, default agenda view — удалены.

`FakeSettingsRepository`: −58 строк аналогично.

## Consequences

- Мёртвый код убран: ни один внешний звонок не сломался.
- `SettingsDataStoreMigration` продолжает работать — companion object не тронут.
- `AiSettingsStore` не нуждается в рефакторинге — AI setters на месте.
- `ProfileAwareCurrentUser` не нуждается в рефакторинге — `userId` на месте.
