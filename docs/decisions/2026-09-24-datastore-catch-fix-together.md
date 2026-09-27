---
title: DataStore `.catch` fix-together
date: 2026-09-24
status: accepted
tags: [datastore, resilience, error-handling]
epic: chore/datastore-catch-together
---

# DataStore `.catch` fix-together

## Context

Three Medium articles were evaluated for borrowable patterns:

1. **"From SharedPreferences to DataStore"** (apptractor.ru) — migration guide recommending `.catch { emit(emptyPreferences()) }` on DataStore flows for graceful degradation when the on-disk file is corrupted or locked.

2. **"Jetpack Navigation 3: A Practical Guide"** — returned HTTP 403 (forbidden). Skipped without speculative assessment. The project already uses Navigation 3 with sophisticated multi-back-stack (`ViewModelStoreNavEntryDecorator`, `SaveableStateHolderNavEntryDecorator`); no obvious gaps.

3. **"Kotlin Property Delegates"** (apptractor.ru) — covered only `by lazy`, `Delegates.observable`, and `mapOf`/`setOf` idioms. The codebase has 0 custom `getValue`/`setValue` delegates; 24+ `MutableStateFlow + asStateFlow` pairs are industry-canonical and must not be abstracted behind delegation. No pilot warranted.

The project already uses Preferences DataStore canonically — no SharedPreferences migration needed.

## Decision

Apply `.catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }` to all DataStore-read flows in five modules. When an `IOException` corrupts the on-disk preferences file, the user sees default values rather than a crash.

| Module | Site |
|---|---|
| `PreferenceWrappers.kt` | `BooleanPref`, `IntPref`, `StringPref`, `FloatPref` — `flow` getter |
| `DataStoreDraftStore.kt` | `load()` — `dataStore.data.first()` |
| `SessionStore.kt` | `accessToken`, `refreshToken`, `userEmail` flows; `getOrInitDeviceId()` |
| `WhatsNewPrefs.kt` | `lastShownHash()` |
| `CalendarSyncSettingsRepository.kt` | All five `observe*()` flows |

`NullableStringPref` and `EnumPref` are unchanged — their specific null/default semantics are intentionally different.

## Rationale

DataStore writes go through `DataStore.edit`, which is transactional and will not produce a corrupt file on its own. The corruption scenario arises from external factors: device storage corruption, filesystem errors on low-battery shutdown, or the file being truncated by another process. When `dataStore.data` throws `IOException` during the read of an already-written file, `.catch` intercepts it and emits `emptyPreferences()`, causing all preference reads to return their defaults silently.

The pattern is the same one recommended in the article's "Type 2" (corrupted file) section and matches Android developer guidance for production DataStore usage.

## Consequences

- No new API or delegate layer introduced
- No changes to `SyncPrefs.kt` — handled separately in MR-1 (`refactor/syncprefs-suspend-api`)
- `NullableStringPref` and `EnumPref` intentionally left unchanged
- Navigation 3 changes deferred (article inaccessible)
- Custom property delegates not introduced

## Links

- `shared/src/commonMain/.../core/settings/PreferenceWrappers.kt`
- `shared/src/commonMain/.../core/draft/DataStoreDraftStore.kt`
- `shared/src/commonMain/.../core/auth/SessionStore.kt`
- `shared/src/commonMain/.../feature/whatsnew/presentation/WhatsNewPrefs.kt`
- `shared/src/commonMain/.../feature/calendar_sync/data/CalendarSyncSettingsRepository.kt`
- `shared/src/commonTest/.../core/settings/DataStoreCatchTest.kt`
- Skill `singularity-todo-test-helpers` — test infrastructure
