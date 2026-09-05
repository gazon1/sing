---
title: "API key lives in SecureStorage only — never in DataStore, never in UI state"
date: 2026-09-05
tags: [security, secure-storage, settings]
---

## Context

The OpenAI API key was originally stored in two places: DataStore (via `SettingsRepository.aiApiKey`) and SecureStorage (via `SecureStoragePort.write("ai_key_openai", ...)`). Both were written by `SettingsViewModel.UpdateAiApiKey`. This was a duplication bug, and worse: the key was sitting in DataStore in plaintext.

When we moved the key to SecureStorage exclusively, the existing users had the key only in DataStore — so we needed a one-shot migration.

## Idea

- Just stop writing to DataStore — users who upgraded lose their key, re-enter it.
- Stop writing to DataStore AND write a migration that copies any legacy DataStore value into SecureStorage on first launch.

## Decision

Single source of truth for the API key: `SecureStoragePort[OpenAiConfig.KEY_OPENAI]`. DataStore is no longer involved.

Migration: `AiApiKeyMigration.run(dataStore, secureStorage)` runs lazily when DataStore is first constructed on Android. Idempotent — no-op if the secure entry exists, no-op if the DataStore entry is empty, copies + clears otherwise.

The Composable password field holds its value in `remember { mutableStateOf("") }` and dispatches `SettingsIntent.UpdateAiApiKey` on every keystroke. The value never enters `SettingsUiState.Content` and is never read back from state.

## Rationale

- Hardware-backed storage: Android Keystore (Android), libsecret/AES-GCM (JVM desktop). Survives uninstall-reinstall in the same user account, doesn't survive app data wipe (correct security boundary).
- UI state is observable (logs, screenshots, accessibility tools). A password field is sensitive; the smallest possible surface area is `remember` + dispatch.
- Migration preserves the user experience: they don't have to re-enter their key after upgrading.

## Consequences

- `SettingsRepository` must NOT contain an `aiApiKey` field. Adding one back is a regression.
- `SettingsUiState.Content` must NOT contain an `aiApiKey` field.
- `SettingsViewModel.processIntent(UpdateAiApiKey)` writes only to `secureStorage`. Never `settings.setAiApiKey(...)`.
- The password field on `AiProviderSettingsScreen` is a local `mutableStateOf`. Don't lift it to the VM.
- `AiApiKeyMigration` is wired through `koinBridge { ... }` inside the DataStore factory's `.also { ds -> ... }` block. See `koin-suspend-bridge` decision.
- Adding a new secret (e.g. another provider's API key) follows the same pattern: new `KEY_*` constant, new `OpenAiConfig`-style config object, migration on first DataStore access, no DataStore copy.

## Links

- `shared/src/commonMain/.../feature/settings/AiApiKeyMigration.kt`
- `shared/src/commonMain/.../core/settings/SettingsRepository.kt`
- `shared/src/commonMain/.../feature/settings/SettingsViewModel.kt`
- `shared/src/commonMain/.../feature/settings/screens/AiProviderSettingsScreen.kt`
- Commit `5db5821` (the migration); commit `097f505` (the split)