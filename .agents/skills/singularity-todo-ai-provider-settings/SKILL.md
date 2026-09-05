---
name: singularity-todo-ai-provider-settings
description: Settings screen for OpenAI-compatible LLM providers — provider dropdown, base URL, model, system prompt, API key (SecureStorage only), and a "Test connection" probe. Use when adding or modifying the AI Provider settings UI, the SettingsViewModel AI branch, or `TextGenPort` integration with user-facing configuration.
---

# Singularity TODO — AI Provider Settings

The AI provider screen lets the user configure any OpenAI-compatible endpoint (OpenAI, OpenAI-compatible proxies, Ollama, custom). This skill documents the four pieces that have to stay in sync — `OpenAiConfig`, `LlmProvider`, the `SettingsViewModel` AI branch, and the `AiProviderSettingsScreen` Composable.

## Storage split (the most important rule)

| Setting | Storage | Why |
|---|---|---|
| `aiProvider` | DataStore | Non-secret. |
| `aiBaseUrl` | DataStore | Non-secret. |
| `aiModel` | DataStore | Non-secret. |
| `aiSystemPrompt` | DataStore | Non-secret. |
| API key (`ai_key_openai`) | `SecureStoragePort` ONLY | Hardware-backed (Android Keystore, Linux libsecret). Never in DataStore, never in `SettingsUiState.Content`, never logged. |

`SettingsRepository` has no `aiApiKey` field. `SettingsUiState.Content` has no `aiApiKey` field. The Compose password field holds its value in a `remember { mutableStateOf("") }` local — sent through `SettingsIntent.UpdateAiApiKey` and discarded from memory after dispatch.

If you find yourself wanting to put the key into state or DataStore — that's the smell of a regression. Stop, read `singularity-todo-secure-storage` instead.

## The configuration object (commonMain, pure-Kotlin)

`shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/OpenAiConfig.kt`:

```. Runkotlin
@JvmInline value class ApiKey(val value: String) {
    val isConfigured: Boolean get() = value.isNotBlank()
    companion object { val EMPTY = ApiKey("") }
}

data class OpenAiConfig(
    val provider: LlmProvider,
    val apiKey: ApiKey,
    val baseUrl: String,
    val defaultModelId: String,
)
```

`OpenAiConfig.resolve(secureStorage, settings)` is `suspend` — reads Flow-backed settings, returns an immutable value. It's used by:

- `createKoogPromptExecutor` (the platform-specific DI factory) to build the OpenAI client;
- `SettingsViewModel.testConnection()` to build the prompt the probe sends.

The same `resolve` logic is also surfaced via `OpenAiConfig.resolveBaseUrl(storedUrl, provider)` — used by the UI's provider-switch dropdown to auto-fill the base URL when the user changes provider. Keep these two in sync; if the rule changes (e.g. "only auto-fill when stored URL was empty"), change both.

## `LlmProvider` enum

`shared/src/commonMain/kotlin/com/singularity/todo/feature/ai/LlmProvider.kt`:

```. Runkotlin
enum class LlmProvider(val id: String, val defaultBaseUrl: String) {
    OPENAI("openai", "https://api.openai.com/v1"),
    OPENAI_COMPATIBLE("openai-compatible", "https://api.openai.com/v1"),
    OLLAMA("ollama", "http://localhost:11434/v1"),
    CUSTOM("custom", "");
    companion object {
        val DEFAULT: LlmProvider = OPENAI
        fun fromId(id: String?): LlmProvider =
            entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
```

When adding a new provider:

1. Add the enum entry with `id` and `defaultBaseUrl`.
2. If the user might have an API key from a third-party keychain, check whether they need a different `KEY_*` constant in `OpenAiConfig`.
3. Update `LlmProviderTest` if you change `fromId` semantics.

## SettingsViewModel — Test connection

```. Runkotlin
fun onIntent(intent: SettingsIntent) { when (intent) {
    // ...
    SettingsIntent.TestAiConnection -> testConnection()
    // ...
}}

private fun testConnection() {
    scope.launch(Dispatchers.Unconfined) {
        update { it.copy(aiTestResult = AiTestResult.Testing) }
        val cfg = OpenAiConfig.resolve(secureStorage, settingsRepository)
        if (!cfg.apiKey.isConfigured) {
            update { it.copy(aiTestResult = AiTestResult.Error("API key not configured")) }
            return@launch
        }
        val start = clock()
        val result = textGen.generate(
            prompt = "ping",
            systemPrompt = "You are a connectivity probe. Reply with the single word: pong.",
            model = cfg.defaultModelId,
        )
        val latency = clock() - start
        update {
            it.copy(
                aiTestResult = result.fold(
                    onSuccess = { AiTestResult.Ok(latency) },
                    onFailure = { e -> AiTestResult.Error(e.message ?: "Unknown error") },
                )
            )
        }
    }
}
```

Three things to preserve:

1. **No key → never call `textGen`** — the "API key not configured" branch is a deliberate short-circuit, not an error. Tests assert this (`textGen.generateCalls` must be empty).
2. **`Dispatchers.Unconfined`** — without it, `advanceUntilIdle()` does not process the coroutine (the test dispatcher isn't advanced inside `viewModelScope`). See `singularity-todo-koin-di` for the pattern.
3. **Use `textGen.generate(...)`, not `textGen.streamChat(...)`** — generate returns a `Result<String>` we can fold into `AiTestResult.Ok`/`Error`. Streaming returns `Flow<String>` which doesn't fold cleanly.

## `AiTestResult` sealed

`shared/src/commonMain/kotlin/com/singularity/todo/feature/settings/SettingsUiState.kt`:

```. Runkotlin
sealed interface AiTestResult {
    data object Idle : AiTestResult
    data object Testing : AiTestResult
    data class Ok(val latencyMs: Long) : AiTestResult
    data class Error(val message: String) : AiTestResult
}
```

This is a `SettingsUiState.Content` field — separate from any `UiEvent.ShowDialog(...)` flow. The screen renders it as a banner (see `singularity-todo-shared-ui-components`).

## Composable shape (`AiProviderSettingsScreen.kt`)

Five sections, in order:

1. **Provider** — `ExposedDropdownMenuBox` listing `LlmProvider.entries`. On selection: dispatch `UpdateAiProvider` and, via `OpenAiConfig.resolveBaseUrl`, dispatch `UpdateAiBaseUrl` if the URL would change.
2. **API Key** — local `remember { mutableStateOf("") }` password field. On change: dispatch `UpdateAiApiKey(value)`. Never enters state.
3. **Base URL** — `OutlinedTextField` over `state.aiBaseUrl`. URI keyboard.
4. **Model** — `OutlinedTextField` over `state.aiModel`.
5. **System Prompt** — multi-line `OutlinedTextField` (`minLines = 3, maxLines = 6`) over `state.aiSystemPrompt`.
6. **Test Connection** — `Button` that dispatches `TestAiConnection`. Disabled while `aiTestResult is Testing`. Result rendered via `AiTestResultBanner(...)` (green `tertiaryContainer` for Ok, red `errorContainer` for Error).

The Composable takes `state: SettingsUiState.Content` and `onIntent: (SettingsIntent) -> Unit`. No events, no side-effects — pure render + dispatch.

## Testing strategy

For the VM:

- `FakeTextGen(success = "pong", failureMessage = "...")` — parametrised fake.
- `FakeSecureStorage(mutableMapOf(KEY_OPENAI to "sk-test"))` — seed the key when testing success paths.
- For "no key" tests: assert that `textGen.generateCalls` is **empty** (not just that the result is `Error`). Parametrised `FakeTextGen` records calls when `trackGenerateCalls = true`.

```. Runkotlin
val textGen = FakeTextGen(success = "pong", trackGenerateCalls = true)
val vm = createVm(backgroundScope, textGen = textGen)
vm.processIntent(SettingsIntent.TestAiConnection)
advanceUntilIdle()
assertEquals(emptyList(), textGen.generateCalls, "TextGen must not be invoked without a key")
```

For pure logic (`OpenAiConfig.resolveBaseUrl`, `LlmProvider.fromId`):

- `commonTest` — no Koin, no Robolectric, instant.

For the UI: Robolectric + Compose-test (currently NOT in this project). Manual smoke test: open Settings → AI Provider on Android emulator, enter a test key, press Test connection, observe `Ok(latencyMs)` banner.

## Common mistakes

- Putting `aiApiKey` back into `SettingsUiState` or `SettingsRepository`. Even temporarily "for the test". It will leak into logs.
- Adding `OpenAIModels.Chat.*` references anywhere outside `KnownModels.kt`. The static init NPEs in JVM-test classpath; see `singularity-todo-koog-test-workarounds`.
- Wiring `textGen.streamChat` for the probe — use `textGen.generate` (returns `Result<String>`).
- Forgetting `Dispatchers.Unconfined` on the probe coroutine — tests will fail at `advanceUntilIdle`.

## Related skills

- `singularity-todo-secure-storage` — API key storage backend.
- `singularity-todo-secret-migration` — one-shot migration of legacy DataStore-stored keys.
- `singularity-todo-koog-test-workarounds` — why `KnownModels` exists.
- `singularity-todo-koog-both-platforms` — how the executor is wired across JVM and Android.
- `singularity-todo-shared-ui-components` — `SettingsSection`, `ResultDialog`, banner components.