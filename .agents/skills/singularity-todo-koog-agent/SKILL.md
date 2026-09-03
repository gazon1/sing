---
name: singularity-todo-koog-agent
description: KMP-native AI agent pattern using JetBrains Koog 1.1.1 with SimpleTool<T>, expect/actual PromptExecutor, ToolRegistry, and Koin auto-registration. Use when building multi-platform AI features that integrate with JetBrains Koog on JVM/Android.
---

# Singularity TODO — Koog AI Agent Pattern

This skill documents the AI agent architecture used in the Singularity TODO KMP app: JetBrains Koog 1.1.1 for agent orchestration, `SimpleTool<T>` with `@Serializable` args, a `TextGenPort` abstraction layer, and Koin DI auto-registration.

## Core Pattern

```
TextGenPort (interface)
    └── KoogAgentService  ── delegates to Koog AIAgent (JVM only)
    └── FakeTextGen       ── in-memory placeholder (tests / Android fallback)

createKoogPromptExecutor() (expect/actual)
    ├── JvmKoogFactory  ── real MultiLLMPromptExecutor + OpenAILLMClient (JVM)
    └── AndroidKoogFactory ── error stub (Android)
```

The `TextGenPort` interface decouples the UI from the underlying AI provider. All real Koog types are isolated inside `KoogAgentService` and `JvmKoogFactory`; no Koog imports leak into domain or UI layers.

## PromptExecutor — expect/actual Pattern

Koog's `PromptExecutor` is JVM-only (requires OkHttp). Use expect/actual to keep `TextGenPort` in `commonMain`:

**commonMain** (`KoogPromptExecutorFactory.kt`):
```kotlin
expect fun createKoogPromptExecutor(): PromptExecutor
```

**jvmMain** (`JvmKoogFactory.kt`):
```kotlin
actual fun createKoogPromptExecutor(): PromptExecutor {
    val settings = OpenAIClientSettings()
    val httpClientFactory = OkHttpKoogHttpClient.Factory()
    val openAIClient = OpenAILLMClient(
        apiKey = "",  // overridden per-request from SecureStorage
        settings = settings,
        httpClientFactory = httpClientFactory,
        clock = KoogClock.System
    )
    return MultiLLMPromptExecutor(mapOf(OpenAILLMProvider to openAIClient))
}
```

**androidMain** (`AndroidKoogFactory.kt`):
```kotlin
actual fun createKoogPromptExecutor(): PromptExecutor {
    error("Koog PromptExecutor is not available on Android")
}
```

Required deps in `jvmMain.dependencies`:
```kotlin
implementation(libs.koog.http.client.okhttp)
implementation(libs.koog.prompt.executor.openai.client.jvm)
```

## KoogAgentService — Real Implementation

```kotlin
class KoogAgentService(
    private val secureStorage: SecureStoragePort,
    private val settings: SettingsRepository,
    private val promptExecutor: PromptExecutor,
) : TextGenPort {

    private fun createAgent(systemPrompt: String, model: String): AIAgent<String, String> {
        val resolvedModel = resolveModel(model)
        return AIAgent.builder()
            .promptExecutor(promptExecutor)
            .systemPrompt(systemPrompt)
            .toolRegistry(agentTools)
            .build()
    }

    private fun resolveModel(modelId: String): LLModel = when (modelId) {
        "gpt-4o" -> OpenAIModels.Chat.GPT4o
        "gpt-4o-mini" -> OpenAIModels.Chat.GPT4oMini
        else -> OpenAIModels.Chat.GPT4oMini
    }

    override suspend fun generate(prompt, systemPrompt, model): Result<String> = runCatching {
        val apiKey = secureStorage.read("ai_key_openai").orEmpty()
        if (apiKey.isBlank()) return@runCatching "(AI unavailable: API key not configured.)"
        val effectiveSystemPrompt = systemPrompt ?: Prompts.chatSystem
        val effectiveModel = model ?: "gpt-4o-mini"
        val agent = createAgent(effectiveSystemPrompt, effectiveModel)
        try { agent.run(prompt) } finally { agent.close() }
    }
}
```

Key Koog 1.1.1 API facts:
- **Entry point**: `AIAgent.builder()` (not `AIAgentServiceBuilder`)
- **`promptExecutor.execute(prompt, model, tools)`** returns `Message.Assistant`
- **Extract text**: `response.parts.filterIsInstance<MessagePart.Text>().joinToString("") { it.text }`
- **`Prompt.Empty`** (capital E, not underscore) — static field on `Prompt` companion
- **`KoogClock.System`** (capital S) — static field on `KoogClock` companion
- **prompt DSL**: `prompt(Prompt.Empty, KoogClock.System) { system("..."); user("...") }` (2-arg only)
- **Model constants**: `ai.koog.prompt.executor.clients.openai.OpenAIModels.Chat.GPT4oMini`

## SimpleTool<T> Pattern

Each AI tool is a `SimpleTool<T>` subclass. The tool's `execute` returns a **JSON string** (not a typed value — that's the caller's concern).

```kotlin
@Serializable
data class RefineTaskInput(val currentTitle: String, val description: String? = null)

@Serializable
data class RefineTaskOutput(val newTitle: String)

class RefineTaskTool(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel
) : SimpleTool<RefineTaskInput>(
    TypeToken.of(RefineTaskInput::class.java),
    NAME,
    DESCRIPTION
) {
    override suspend fun execute(args: RefineTaskInput): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.refineSystem)
            user(Prompts.refineUser(args.currentTitle, args.description))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        // Always return JSON from execute()
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

Use cases decode the JSON returned by `tool.execute()`:

```kotlin
class RefineTaskUseCase(private val tool: RefineTaskTool) {
    suspend operator fun invoke(currentTitle: String, description: String? = null): Result<String> =
        runCatching {
            val json = tool.execute(RefineTaskInput(currentTitle, description))
            Json.decodeFromString<RefineTaskOutput>(json).newTitle
        }
}
```

## Prompt DSL — Correct Usage

```kotlin
prompt(Prompt.Empty, KoogClock.System) {
    system("You are a helpful assistant.")
    user("Input text here")
}
```

**Common mistakes**:
- `Prompt.EMPTY` → wrong, use `Prompt.Empty` (Kotlin is case-sensitive)
- `KoogClock.SYSTEM` → wrong, use `KoogClock.System`
- `prompt { system(); user() }` (1-arg) → doesn't exist; must be 2-arg: `prompt(Prompt.Empty, KoogClock.System) { }`
- `Prompt.EMPTY` (all-caps) and `KoogClock.SYSTEM` (all-caps) → these fields don't exist

## Tool Registration via Koin

All `SimpleTool<T>` implementations are auto-registered via Koin's `@ComponentScan`:

```kotlin
@OptIn(KoinApiExtension::class)
@ComponentScan("com.singularity.todo.feature.ai.tools")
class AiToolsModule
```

The tools are retrieved in `KoogAgentService` via `Koin.getAll<Tool>()` and passed to `ToolRegistry { tool(toolInstance) }`.

## Prompts as Kotlin String Templates

All prompt text lives in `Prompts.kt` as plain strings — no template engine:

```kotlin
object Prompts {
    const val refineSystem = "You are a productivity assistant. Rewrite the task title to be clearer..."
    const val refineUser = "Title: %s\nDescription: %s"

    fun refineUser(currentTitle: String, description: String?): String =
        "Title: $currentTitle\nDescription: ${description ?: "(none)"}"
}
```

**Rule**: `const val` only for static strings with no runtime interpolation. Everything else is a plain function.

## When to Use This Pattern

- Building AI features in a KMP app targeting JVM and Android
- Needing type-safe tool definitions via `@Serializable` data classes
- Using JetBrains Koog as the agent framework (KMP-native)
- Wanting to test AI logic without network or API keys via `FakeTextGen`

## Key Files

| File | Purpose |
|---|---|
| `shared/src/commonMain/.../feature/ai/TextGenPort.kt` | Abstraction interface |
| `shared/src/commonMain/.../feature/ai/KoogAgentService.kt` | Koog wrapper with AIAgent.builder() |
| `shared/src/commonMain/.../core/di/KoogPromptExecutorFactory.kt` | expect declaration |
| `shared/src/jvmMain/.../core/di/JvmKoogFactory.kt` | MultiLLMPromptExecutor + OpenAILLMClient |
| `shared/src/androidMain/.../core/di/AndroidKoogFactory.kt` | Error stub |
| `shared/src/commonMain/.../feature/ai/prompts/Prompts.kt` | All prompt strings |
| `shared/src/commonMain/.../feature/ai/tools/` | 16 SimpleTool<T> implementations |
| `shared/src/commonMain/.../feature/ai/use_cases/` | 8 use case classes |
