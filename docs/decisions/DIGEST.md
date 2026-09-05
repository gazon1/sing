# Decision Log Digest

Auto-generated consolidated rules from `docs/decisions/`. The agent
reads this at session start. Per-decision entries (`docs/decisions/YYYY-MM-DD-*.md`)
are the human-facing reasoning. Refresh with:

```bash
./scripts/refresh-decisions-digest.sh
```

Each bullet below is a rule the agent must honour. Entries that have
been superseded (see frontmatter `supersedes:`) are excluded.

## Rules


### 2026-09-05-koin-suspend-bridge
Don't use `GlobalScope.launch { ... }` inside factories — non-deterministic.
Don't use raw `runBlocking { ... }` inside `module { ... }` blocks.
`koinBridge` is for one-shot startup reads. Not for hot-path code, not for long-running operations.
Use `koinBridge { ... }` in any Koin factory that calls a `suspend` function.
When the script's grep is broken (a stray `runBlocking` appears), fix it immediately; the helper exists specifically so this is detectable.

### 2026-09-05-koog-both-platforms
`ai.koog:http-client-okhttp` must be declared in **both** `androidMain.dependencies` and `jvmMain.dependencies`.
A passing `:androidApp:assembleDebug` is the cross-platform smoke test (it would have failed under the old stub).
`single<PromptExecutorPort>` and `single<PromptExecutor>` are both bound; `PromptExecutorPort` is for the streaming executor inside `KoogAgentService`, `PromptExecutor` is for the AI tool factories.
The old `JvmPromptExecutorPort` and `AndroidPromptExecutorPort` files are deleted.
The unified `KoogPromptExecutorPort` lives in `commonMain` and exposes `val executor: PromptExecutor` publicly for the platform `single<PromptExecutor>` binding.

### 2026-09-05-koog-test-workarounds
Don't add capabilities to `KnownModels` unless a feature needs them — the simple form avoids the static init entirely.
`grep -rn "OpenAIModels" shared/src/commonMain shared/src/jvmMain shared/src/androidMain --include="*.kt"` must return only comments in `KnownModels.kt`. Anything else is a regression.
`JvmAiDiGraphTest` keeps its `LLModel` override as a safety belt — if someone reintroduces `OpenAIModels.*`, this test fails at graph-build time.
When adding a new AI tool, add its use case with **explicit `get<ConcreteTool>()`** if the use case's parameter is `SimpleTool<T>`:

### 2026-09-05-llm-provider-settings
`AiTestResult` is part of `SettingsUiState.Content.aiTestResult` with default `Idle`. Don't make it a `UiEvent`.
`FakeTextGen` is parametrised: `(success, failureMessage, trackGenerateCalls)`. Use `trackGenerateCalls = true` in VM tests that assert the no-key short-circuit.
Provider-switch logic in the UI delegates to `OpenAiConfig.resolveBaseUrl(storedUrl, provider)`. If you change the auto-fill rule, change it in `resolveBaseUrl` only.
`SettingsViewModel.testConnection()` always short-circuits with `Error("API key not configured")` when no key, **without** calling `textGen`. Tests assert this with `FakeTextGen(trackGenerateCalls = true)` and `assertEquals(emptyList(), textGen.generateCalls)`.
The Test connection "probe" prompt is hard-coded: `"Reply with the single word: pong."` — change together with the system prompt if needed.

### 2026-09-05-secret-storage-split
Adding a new secret (e.g. another provider's API key) follows the same pattern: new `KEY_*` constant, new `OpenAiConfig`-style config object, migration on first DataStore access, no DataStore copy.
`AiApiKeyMigration` is wired through `koinBridge { ... }` inside the DataStore factory's `.also { ds -> ... }` block. See `koin-suspend-bridge` decision.
`SettingsRepository` must NOT contain an `aiApiKey` field. Adding one back is a regression.
`SettingsUiState.Content` must NOT contain an `aiApiKey` field.
`SettingsViewModel.processIntent(UpdateAiApiKey)` writes only to `secureStorage`. Never `settings.setAiApiKey(...)`.
The password field on `AiProviderSettingsScreen` is a local `mutableStateOf`. Don't lift it to the VM.

## Active entries

- `2026-09-05-koin-suspend-bridge` — "Bridge suspend code into Koin factories via koinBridge { ... }"
- `2026-09-05-koog-both-platforms` — "Wire Koog AI agent for both JVM desktop and Android"
- `2026-09-05-koog-test-workarounds` — "Avoid OpenAIModels.Chat.* — use KnownModels; explicit get<>() for SimpleTool<T>"
- `2026-09-05-llm-provider-settings` — "LLM provider settings: pure-Kotlin config object + sealed test result"
- `2026-09-05-secret-storage-split` — "API key lives in SecureStorage only — never in DataStore, never in UI state"
