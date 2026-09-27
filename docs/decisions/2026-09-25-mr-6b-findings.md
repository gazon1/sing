---
title: MR-6b Findings (No-Code MR)
date: 2026-09-25
status: accepted
deciders: Singularity Developer
deciders: Singularity Developer
---

# MR-6b Findings (No-Code MR)

## Context

MR-6b по плану: миграция SettingsVM, BackupVM, CalendarSyncVM, SyncVM на MviViewModel. При проверке обнаружено что 2 из 4 VM уже мигрированы.

## Verification Results

| VM | Status | Notes |
|---|---|---|
| `CalendarSyncViewModel` | ✅ Already on MviViewModel | Inherited from `MviViewModel<CalendarSyncUiState, CalendarSyncIntent, Nothing>` |
| `SyncViewModel` | ✅ Already on MviViewModel | Inherited from `MviViewModel<SyncState, SyncIntent, Nothing>` |
| `SettingsViewModel` | ⚠️ Deferred | Complex DI (7 contributors via `getOrNull`), `SettingsIntent` is core typealias not extending MviIntent |
| `BackupViewModel` | ⚠️ Deferred | Screen uses separate `snackbar: SharedFlow<String>` alongside `events: SharedFlow<BackupUiEvent>` — requires screen API change |

## Why SettingsViewModel is Deferred

`SettingsIntent` is a typealias in `feature.settings` pointing to `core.settings.SettingsIntent` (a sealed interface in core module). Making it extend `MviIntent` would require:
1. Adding `MviIntent` inheritance to `core.settings.SettingsIntent` (core module change)
2. Updating all nested sealed interfaces (Appearance, Notifications, etc.) to also extend MviIntent
3. Potentially affecting any code in `core` that references `SettingsIntent`

This is a deeper API change than a simple VM migration.

## Why BackupViewModel is Deferred

BackupScreen consumes two separate flows:
```kotlin
events: SharedFlow<BackupUiEvent>    // for errors + SettingsSnapshotExported
snackbar: SharedFlow<String>         // for success messages ("Backup created", etc.)
```

Migrating to MviViewModel would require:
1. Adding `ShowSnackbar(String)` to `BackupUiEvent`
2. Removing the separate `snackbar` flow
3. Updating `BackupScreen` to handle snackbar messages via event mapping

This is a screen API change, not just a VM change.

## Remaining Work

| # | VM | Blocker | Solution |
|---|---|---|---|
| 1 | SettingsViewModel | Core typealias doesn't extend MviIntent | Add MviIntent to core.settings.SettingsIntent sealed hierarchy |
| 2 | BackupViewModel | Screen uses separate snackbar flow | Unify snackbar into BackupUiEvent + update screen |

Both are Medium effort, require careful screen API migration.

## Verification

- BUILD SUCCESSFUL
- jvmTest: passed
- detekt: 325 findings (baseline, no regressions)
- epic2: already up-to-date (no merge needed)
