---
name: singularity-todo-koog-agent
description: KMP-native AI agent pattern using JetBrains Koog 1.1.1 with SimpleTool<T>, suspend `expect/actual createKoogPromptExecutor`, `TextGenPort` abstraction, and `KnownModels` to dodge JVM-test classpath NPEs. Use when building or modifying AI features that integrate with JetBrains Koog across JVM and Android in this project.
---

# Singularity TODO — Koog AI Agent Pattern (current state)

Earlier versions of this skill described Koog as JVM-only with an Android stub. That has changed: Koog now runs on **both** platforms via the OkHttp HTTP backend. The cross-platform wiring details live in `singularity-todo-koog-both-platforms`; this skill covers the agent-level pattern that sits on top.

## Architecture

```
TextGenPort (interface, commonMain)
    ├── KoogAgentService    — production, calls Koog AIAgent
    └── FakeTextGen         — in-memory placeholder for tests / fallback

createKoogPromptExecutor (expect/actual suspend fun)
    ├── JvmKoogFactory      — real MultiLLMPromptExecutor + OpenAILLMClient (JVM)
    └── AndroidKoogFactory  — real MultiLLMPromptExecutor + OpenAILLMClient (Android)

aiToolsCoreModule (commonMain)   — 17 SimpleTool<T>, 9 use cases, GenUI, ChatViewModel
aiToolsModule (expect/actual)    — adds LLModel + PromptExecutorPort + raw PromptExecutor
```

## PromptExecutor — suspend `expect/actual`

The `PromptExecutor` is JVM-only **as a type in commonMain** if you import it directly. We keep it out of commonMain by wrapping it in a `PromptExecutorPort` interface. The factory itself is `suspend`:

```. Runkotlin
// commonMain
expect suspend fun createKoogPromptExecutor(
    secureStorage: SecureStoragePort,
    settings: SettingsRepository,
): PromptExecutorPort

// jvmMain / androidMain
actual suspend fun createKoogPromptExecutor(
    secureStorage: SecureStoragePort,
    settings: SettingsRepository,
): PromptExecutorPort {
    val cfg = OpenAiConfig.resolve(secureStorage, settings)
    val executor = buildExecutor(cfg)              // local helper
    return KoogPromptExecutorPort(executor)
}
```

The suspend bridge from Koin's sync DSL is `koinBridge { ... }` — see `singularity-todo-koin-suspend-bridge`.

## `KoogPromptExecutorPort` (commonMain)

Single adapter for both platforms. Replaces the old `JvmPromptExecutorPort` / `AndroidPromptExecutorPort` pair.

```. Runkotlin
class KoogPromptExecutorPort(
    val executor: ai.koog.prompt.executor.model.PromptExecutor,
) : PromptExecutorPort {
    override suspend fun execute(prompt, model, tools) = executor.execute(prompt, model, tools)
    override fun executeStreaming(prompt, model, tools) = executor.executeStreaming(prompt, model, tools)
}
```

The `val executor` is exposed publicly so the platform AI module can rebind it as `single<PromptExecutor>` for the AI tool factories.

## `KnownModels` — avoiding `OpenAIModels` in production

Never reference `OpenAIModels.Chat.*` in production code. Use `KnownModels` instead:

```. Runkotlin
internal object KnownModels {
    val GPT4o: LLModel = LLModel(OpenAILLMProvider, "gpt-4o")
    val GPT4oMini: LLModel = LLModel(OpenAILLMProvider, "gpt-4o-mini")
    // ...
}
```

Why: `OpenAIModels$Chat.<clinit>` NPEs in the JVM-test classpath. Full details in `singularity-todo-koog-test-workarounds`.

## `KoogAgentService` — the production `TextGenPort`

```. Runkotlin
class KoogAgentService(
    private val secureStorage: SecureStoragePort,
    private val settings: SettingsRepository,
    private val promptExecutor: ai.koog.prompt.executor.model.PromptExecutor,
    private val streamingExecutor: PromptExecutorPort,
    private val tools: List<Tool<*, *>>,
) : TextGenPort {

    private val agentTools = ToolRegistry.builder().tools(tools).build()

    private fun createAgent(systemPrompt: String, modelId: String): AIAgent<String, String> =
        AIAgent.builder()
            .promptExecutor(promptExecutor)
            .systemPrompt(systemPrompt)
            .toolRegistry(agentTools)
            .llmModel(resolveModel(modelId))    // Koog 1.1.1: llmModel, not model
            .build()

    override suspend fun generate(prompt, systemPrompt, model): Result<String> = runCatching {
        val apiKey = secureStorage.read(OpenAiConfig.KEY_OPENAI)?.takeIf { it.isNotBlank() }
            ?: return@runCatching "(AI unavailable: API key not configured.)"
        // ...
    }
}
```

Key facts:

- **API key check first** — never reach the executor without a key. Return a friendly message instead of an exception.
- **`llmModel()` not `model()`** — Koog 1.1.1's `AIAgentBuilder` exposes `llmModel(LLModel)`. Easy to get wrong.
- **`resolveModel(modelId)`** lives in `feature/ai/KnownModels.kt` and uses `KnownModels.*` (no `OpenAIModels`).

## SimpleTool<T> pattern

```. Runkotlin
@Serializable
data class RefineTaskInput(val currentTitle: String, val description: String? = null)

@Serializable
data class RefineTaskOutput(val newTitle: String)

class RefineTaskTool(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
) : SimpleTool<RefineTaskInput>(TypeToken.of(RefineTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: RefineTaskInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.refineSystem)
            user(Prompts.refineUser(args.currentTitle, args.description))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return Json.encodeToString(RefineTaskOutput.serializer(), RefineTaskOutput(text.trim()))
    }

    companion object {
        const val NAME = "refine_task"
        const val DESCRIPTION = "Rewrite the task title to be clearer and more actionable."
        private fun extractText(response: Message.Assistant): String =
            response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }.trim()
    }
}
```

The `factory` wrappers `llmTool(...)` and `dataTool(...)` in `ToolFactories.kt` are the canonical way to build tools without writing one class per tool — see `singularity-todo-ai-tool` for that.

## DI registration

```. Runkotlin
// commonMain — aiToolsCoreModule
single<TextGenPort> { KoogAgentService(get(), get(), get(), get(), get()) }
single<List<Tool<*, *>>> {
    listOf(get<RefineTaskTool>(), get<SmartRewriteTool>(), /* ... */)
}

// jvmMain / androidMain — aiToolsModule actual
includes(aiToolsCoreModule())
single<LLModel> { KnownModels.GPT4oMini }
single<PromptExecutorPort> {
    koinBridge {
        createKoogPromptExecutor(get<SecureStoragePort>(), get<SettingsRepository>())
    }
}
single<PromptExecutor> {
    (get<PromptExecutorPort>() as KoogPromptExecutorPort).executor
}
```

## FakeTextGen — parametrised

```. Runkotlin
class FakeTextGen(
    private val success: String = "(Placeholder AI response — configure API key ...)",
    private val failureMessage: String? = null,
    private val trackGenerateCalls: Boolean = false,
) : TextGenPort {
    private val _generateCalls = mutableListOf<Triple<String, String?,?,>>()
    val generateCalls: List<Triple<String, String?,?,>> get() = _generateCalls

    override suspend fun generate(prompt, systemPrompt, model): Result<String> {
        if (trackGenerateCalls) _generateCalls += Triple(prompt, systemPrompt, model)
        return failureMessage?.let { Result.failure(RuntimeException(it)) } ?: Result.success(success)
    }
    override fun streamChat(message): Flow<String> = flowOf(success)
}
```

Use `trackGenerateCalls = true` to assert in tests that "no API key" branch never reached the executor.

## Prompt DSL

```. Runkotlin
val p = prompt(Prompt.Empty, KoogClock.System) {
    system("...")
    user("...")
}
```

- `Prompt.Empty` (capital E), not `Prompt.EMPTY`.
- `KoogClock.System` (capital S), not `KoogClock.SYSTEM`.
- 2-arg form only — there's no 1-arg overload.

## Adding a new AI feature

See `singularity-todo-koog-both-platforms` for the full checklist. In short:

1. `use_cases/MyUseCase.kt`
2. `tools/MyTool.kt` (`SimpleTool<MyInput>`)
3. Add `factory` to `aiToolsCoreModule`
4. Add use case as nullable to the VM (test-friendly), branch in the dispatch
5. Tests with `FakeTextGen` + `FakeSecureStorage`

## Common mistakes

- `OpenAIModels.Chat.*` references anywhere — NPEs in tests.
- `factory { Foo(get()) }` where `Foo`'s parameter is `SimpleTool<T>` — see `singularity-todo-koog-test-workarounds`.
- `runBlocking { createKoogPromptExecutor(...) }` directly — use `koinBridge { ... }`.
- `AIAgent.builder().model(...)` — Koog 1.1.1 uses `llmModel(LLModel)`.
- `baseUrl = ...` passed to `OpenAILLMClient` constructor — pass via `OpenAIClientSettings(baseUrl = ...)` instead.

## Related skills

- `singularity-todo-koog-both-platforms` — cross-platform wiring details (OkHttp, Gradle, build matrix).
- `singularity-todo-koog-test-workarounds` — `KnownModels` and the Koin generic-type gotcha.
- `singularity-todo-ai-tool` — `SimpleTool`/`LlmUseTool` factories in `ToolFactories.kt`.
- `singularity-todo-ai-provider-settings` — the user-facing settings screen + Test connection.
- `singularity-todo-koin-suspend-bridge` — `koinBridge { ... }` helper.
- `singularity-todo-koin-di` — Koin conventions.