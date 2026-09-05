---
title: "LLM provider settings: pure-Kotlin config object + sealed test result"
date: 2026-09-05
tags: [ai, settings, ui]
---

## Context

The AI Provider settings screen needed: provider selection (OpenAI / Ollama / custom), base URL, model, system prompt, API key, and a "Test connection" button. The challenge: keep the logic testable as pure-Kotlin functions and avoid leaking Koog types into UI code.

## Idea

- Put everything in the SettingsViewModel — Test connection calls `TextGenPort.generate(...)` and stores the result in `SettingsUiState`.
- Have the screen read the API key from `state.aiApiKey` and update via `UpdateAiApiKey`.

## Decision

**Pure-Kotlin config layer (`commonMain`):**

- `LlmProvider` enum with `id`, `defaultBaseUrl`, and `fromId()` fallback.
- `OpenAiConfig` data class + suspend `resolve(secureStorage, settings)` — reads everything once, returns an immutable value.
- `OpenAiConfig.resolveBaseUrl(storedUrl, provider)` — pure function, used by both the UI (auto-fill on provider switch) and `resolve()` itself. **Same rule, one place.**
- `AiTestResult` sealed (`Idle` / `Testing` / `Ok(latencyMs)` / `Error(message)`) lives in `SettingsUiState.kt` next to the existing UI state types.

**VM (`SettingsViewModel`):**

- `processIntent(TestAiConnection)` runs `OpenAiConfig.resolve(...)`, returns `Error("API key not configured")` **without** calling `textGen` if the key is blank.
- Otherwise calls `textGen.generate(prompt = "ping", systemPrompt = "...Reply: pong", model = cfg.defaultModelId)`. Folds the `Result<String>` into the appropriate `AiTestResult`.
- Wrapped in `scope.launch(Dispatchers.Unconfined) { ... }` so `advanceUntilIdle()` in tests sees the updates.

**UI (`AiProviderSettingsScreen`):**

- Five `SettingsSection`s: Provider, API Key, Base URL, Model, System Prompt, Test Connection.
- Provider dropdown uses `LlmProvider.entries` and calls `OpenAiConfig.resolveBaseUrl(...)` to decide whether to update `aiBaseUrl`.
- API key is a local `remember { mutableStateOf("") }` — never enters state. (See `secret-storage-split` decision.)
- Test connection button is disabled while `state.aiTestResult is Testing`. Banner below the button renders `Ok` / `Error`.

## Rationale

- Pure-Kotlin `OpenAiConfig.resolveBaseUrl` is the rule. UI and `resolve()` agree because they call the same function. The first version of the screen had its own copy of the rule — drifted and produced inconsistent test results. Single source of truth.
- `AiTestResult` as part of `SettingsUiState.Content` (not a separate `UiEvent`) because it's the *current* state of the connection, not a one-shot event. Survives rotation, can be re-rendered.
- `textGen.generate` (not `streamChat`) — `Result.fold` fits the `Ok`/`Error` sealed cleanly.
- `Dispatchers.Unconfined` in the test branch: without it, `advanceUntilIdle()` does not process the coroutine on `backgroundScope`. Existing pattern in `singularity-todo-koin-di`.

## Consequences

- `AiTestResult` is part of `SettingsUiState.Content.aiTestResult` with default `Idle`. **Never** make it a `UiEvent`.
- **Always** keep the auto-fill rule in `OpenAiConfig.resolveBaseUrl(storedUrl, provider)` only. The UI delegates to it — changing both is a bug.
- `SettingsViewModel.testConnection()` **always** short-circuits with `Error("API key not configured")` when no key, **without** calling `textGen`. Tests assert this with `FakeTextGen(trackGenerateCalls = true)` and `assertEquals(emptyList(), textGen.generateCalls)`.
- The Test connection "probe" prompt is hard-coded: `"Reply with the single word: pong."` — change together with the system prompt if needed.
- `FakeTextGen` is parametrised: `(success, failureMessage, trackGenerateCalls)`. **Always** use `trackGenerateCalls = true` in VM tests that assert the no-key short-circuit.

## Links

- `shared/src/commonMain/.../feature/ai/OpenAiConfig.kt`
- `shared/src/commonMain/.../feature/ai/LlmProvider.kt`
- `shared/src/commonMain/.../feature/settings/SettingsUiState.kt` — `AiTestResult` and `SettingsIntent.TestAiConnection`
- `shared/src/commonMain/.../feature/settings/SettingsViewModel.kt` — `testConnection()`
- `shared/src/commonMain/.../feature/settings/screens/AiProviderSettingsScreen.kt`
- `shared/src/commonMain/.../test/fakes/FakeRepositories.kt` — parametrised `FakeTextGen`
- Commit `5db5821`