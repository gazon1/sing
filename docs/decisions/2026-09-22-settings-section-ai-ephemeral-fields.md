---
title: "Keep ephemeral state inside SettingsSection.Ai, not in EphemeralState"
date: 2026-09-22
tags: [settings, architecture, state-management]
status: accepted
---

## Context

Phase 4 of the `SettingsContributor` migration attempted to slim `SettingsSection.Ai` by moving ephemeral UI state (`testResult`, `models`, `isFetchingModels`, `fetchModelsError`) into a separate `EphemeralState.Ai` holder, leaving `SettingsSection.Ai` with only persisted fields. `AiSettingsStore.observe()` was changed to a 4-flow combine, and `SettingsViewModel.reloadAiSection()` was updated to write ephemeral values to `it.aiEphemeral` instead of the top-level `aiTestResult`, `aiModels`, etc. fields that the existing `SettingsViewModelTest` tests assert on.

After this change, three `TestConnection*` tests all failed with `Expected Error, actual Idle` — `_testResult.value` inside `AiSettingsStore` was never being read by the tests.

## Decision

Keep ephemeral state (`testResult`, `models`, `isFetchingModels`, `fetchModelsError`) **inside** `SettingsSection.Ai`. Keep `EphemeralState` types (and the `aiEphemeral` field in `SettingsUiState.Content`) for future sections that are not yet migrated. Revert `AiSettingsStore.observe()` to its original 8-flow combine form. Always update **both** the ephemeral fields on `SettingsSection.Ai` **and** the top-level flat fields in `SettingsUiState.Content` when either changes.

## Rationale

The existing `SettingsViewModelTest` tests assert on `state.aiTestResult`, `state.aiModels`, etc. — top-level parameters on `SettingsUiState.Content`. Moving ephemeral state to a separate `EphemeralState.Ai` holder broke these assertions because `reloadAiSection()` was only writing to `aiEphemeral`, not to the top-level fields.

Reverting to the original structure is the pragmatic choice: the 8-flow `combine` in `AiSettingsStore.observe()` already includes all ephemeral `MutableStateFlow`s; `MutableStateFlow` is hot and immediately reflects updates regardless of the collector context; and the existing test suite passes without modification.

The `EphemeralState` type itself is not wrong — it correctly models non-persisted state for sections that don't have an existing 8-flow store pattern. But retro-fitting it onto `SettingsSection.Ai` after the fact, without updating the tests, is the mistake.

## Consequences

- `SettingsSection.Ai` always contains all AI state (persisted + ephemeral) — never split.
- `AiSettingsStore.observe()` is an 8-flow `combine`: 4 persisted flows + 4 ephemeral `MutableStateFlow`s.
- `SettingsViewModel.reloadAiSection()` always updates **both** the `ai.*` fields on the `SettingsSection.Ai` object **and** the top-level flat fields (`aiTestResult`, `aiModels`, `isFetchingAiModels`, `fetchAiModelsError`) in `SettingsUiState.Content`.
- The `aiEphemeral` field in `SettingsUiState.Content` is kept for future migrations; do not rely on it as the primary read path for AI ephemeral state today.
- `AiSettingsContributor` stays as a 1-argument class — `observe()` returns `Flow<SettingsSection.Ai>` (no `stateIn` wrapper) to avoid `CoroutineScope` requirements that break `DiGraphTest`.
- When adding new AI-related state, add it to `SettingsSection.Ai` directly; do not introduce a parallel `EphemeralState.Ai` field.

## Links

- `shared/src/commonMain/kotlin/com/singularity/todo/core/settings/SettingsBundle.kt` — `SettingsSection.Ai` definition
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/data/AiSettingsStore.kt` — `observe()` with 8-flow combine
- `shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/SettingsViewModel.kt` — `reloadAiSection()` updates both section and flat fields
- `shared/src/jvmTest/kotlin/com/singularity/todo/feature/settings/SettingsViewModelTest.kt` — tests that assert on `aiTestResult`, `aiModels`, etc.
