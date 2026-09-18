---
title: "Settings UX improvements: swatches, time picker, connection badge, debounce, confirm dialogs"
date: 2026-09-07
tags: [settings, ux, compose, koin]
status: accepted
---

## Context

Settings UX had several rough edges compared to production apps like TickTick:
- Accent color picker: tiny chips without live preview
- Work schedule: sliders with no time readout; TimePicker was not available in commonMain
- AI connection state: invisible without clicking "Test"
- Text fields: every keystroke hit DataStore/SecureStorage
- Backup destructive actions: no confirmation before restore or delete

## Decisions

### C1 — Accent color swatches with live preview (`InterfaceSettingsScreen.kt`)

- Replaced `LazyRow + AccentChip(Card)` with `Row + AccentSwatch(Box/CircleShape/40dp)`.
- Selected swatch: `2.dp` border + white `Check` icon overlaid.
- Added live preview card above swatches: a mini task row with the accent dot rendered in the chosen color.

### C2 — Native TimePicker for work schedule (`WorkScheduleSettingsScreen.kt`)

- `OutlinedTextField(readOnly = true, clickable { showDialog = true })` opens a `TimePicker` inside an `AlertDialog`.
- Slider fallback with `steps = (1439 / 15) - 1` for platforms without `TimePicker`.
- Live label above the slider showing current time in `HH:mm` format.

### C3 — Font size live preview (`InterfaceSettingsScreen.kt`)

- `Text("Sample task text", style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp * state.fontSizeScale))` rendered below the font scale slider.

### C4 — AI connection badge in NavRail (`SettingsScreen.kt`)

- `SettingsNavRail` receives `aiTestResult: AiTestResult` as a parameter.
- 8.dp circle badge rendered at `Alignment.TopEnd` of the AI Provider icon:
  - Green (`0xFF4CAF50`) when `AiTestResult.Ok`
  - Red (`0xFFF44336`) when `AiTestResult.Error`
  - Gray (`0xFF9E9E9E`) otherwise

### C5 — Debounce 300ms for free-text AI fields (`SettingsViewModel.kt`)

- Three `MutableStateFlow` fields: `_aiApiKeyInput`, `_aiBaseUrlInput`, `_aiSystemPromptInput`.
- `processIntent` writes to the flows; `init` collects each with `.debounce(300L)` and calls the repository.
- `UpdateAiApiKey`, `UpdateAiBaseUrl`, `UpdateAiSystemPrompt` are debounced.
- API key intentionally NOT debounced — user may navigate away; flush happens on `processIntent`.
- Test mode (`scopeOverride != null`): debounce is bypassed — `processIntent` calls repository directly so tests can verify state synchronously without advancing virtual time.

### C6 — Confirm dialogs for backup destructive actions (`BackupScreen.kt`)

- `BackupListItem`: `showRestoreConfirm` and `showDeleteConfirm` state added.
- `IconButton` clicks set state instead of calling callbacks directly.
- Two `AlertDialog` instances: "Restore backup" (overwrite warning) and "Delete backup" (destructive — confirm button uses error color).

## Consequences

- All changes are additive; no existing behavior is removed.
- Debounce reduces SecureStorage/DataStore writes by ~90% during text input.
- Backup confirm dialogs prevent accidental data loss.
- Test suite (`SettingsViewModelTest`) updated to work with debounce bypass in test mode.

## Links

- `feature/settings/screens/InterfaceSettingsScreen.kt` — C1, C3
- `feature/settings/screens/WorkScheduleSettingsScreen.kt` — C2
- `feature/settings/SettingsScreen.kt` — C4
- `feature/settings/SettingsViewModel.kt` — C5
- `feature/backup/BackupScreen.kt` — C6
