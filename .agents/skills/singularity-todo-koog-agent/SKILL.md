---
name: singularity-todo-koog-agent
description: KMP-native AI agent pattern using JetBrains Koog with SimpleTool<T>, TextGenPort interface, FakeTextGen fallback, and Koin auto-registration. Use when building multi-platform AI features that integrate with JetBrains Koog 1.2+.
---

# Singularity TODO — Koog AI Agent Pattern

This skill documents the AI agent architecture used in the Singularity TODO KMP app: JetBrains Koog for agent orchestration, `SimpleTool<T>` with `@Serializable` args, a `TextGenPort` abstraction layer, and Koin DI auto-registration.

## Core Pattern

```
TextGenPort (interface)
    └── KoogAgentService  ── delegates to Koog AIAgent (when available)
    └── FakeTextGen       ── in-memory placeholder (tests / Koog unavailable)
```

The `TextGenPort` interface decouples the UI from the underlying AI provider. All real Koog types are isolated inside `KoogAgentService`; no Koog imports leak into domain or UI layers.

## TextGenPort Interface

```kotlin
interface TextGenPort {
    suspend fun generate(
        prompt: String,
        systemPrompt: String? = null,
        model: String? = null
    ): Result<String>
}
```

Implementations:
- **`KoogAgentService`**: wraps Koog `AIAgent`, converts chat messages to Koog prompt format
- **`FakeTextGen`**: returns a fixed placeholder string — used in tests and when Koog is not on classpath

## SimpleTool<T> Pattern (Koog Native)

Each AI tool is a `SimpleTool<T>` subclass with `@Serializable` input/output:

```kotlin
@Serializable
data class RefineTaskInput(
    val currentTitle: String,
    val description: String? = null
)
@Serializable
data class RefineTaskOutput(val newTitle: String)

class RefineTaskTool(private val settings: SettingsRepository) : SimpleTool<RefineTaskInput>() {
    override val argsSerializer = RefineTaskInput.serializer()
    override val name = "refine_task"
    override val description = "Rewrite the task title to be clearer and more actionable"
    override suspend fun execute(args: RefineTaskInput): RefineTaskOutput {
        // call LLM via TextGenPort
    }
}
```

### JSON Schema via Reflection

Rather than hand-writing JSON schemas, use `serializer<T>().descriptor` to walk `@Serializable` properties:

```kotlin
inline fun <reified T> jsonSchema(name: String, description: String): ToolSpec {
    val d = serializer<T>().descriptor
    val props = buildMap {
        for (i in 0 until d.elementsCount) {
            put(d.getElementName(i), jsonTypeFor(d.getElementDescriptor(i)))
        }
    }
    val required = (0 until d.elementsCount)
        .filter { !d.isElementOptional(it) }
        .map { d.getElementName(it) }
    return ToolSpec(name, description, JsonObject(mapOf(
        "type" to JsonPrimitive("object"),
        "properties" to JsonObject(props),
        "required" to JsonArray(required.map { JsonPrimitive(it) })
    )))
}
```

This single ~20 LOC helper generates schemas for all 16 tools automatically.

## Prompts as Kotlin String Templates

All prompt text lives in `Prompts.kt` as plain strings — no template engine:

```kotlin
object Prompts {
    const val refineSystem = "You are a productivity assistant. Rewrite the user's task title to be clearer..."
    // Non-const strings use .trimIndent():
    val smartRewriteSystem = """
        You are a title rewriting assistant...
    """.trimIndent()

    fun refineUser(currentTitle: String, description: String?) = buildString {
        appendLine("Title: $currentTitle")
        appendLine("Description: ${description ?: "(none)"}")
    }
}
```

**Rule**: `const val` only for static strings with no runtime interpolation. Everything else is a plain `val` with `.trimIndent()` or `buildString {}`.

## FakeTextGen — Test Double

```kotlin
class FakeTextGen : TextGenPort {
    override suspend fun generate(
        prompt: String,
        systemPrompt: String?,
        model: String?
    ): Result<String> = Result.success("(Placeholder AI response: $prompt)")

    fun streamChat(message: String): Flow<String> = flow {
        emit("(AI unavailable)")
    }
}
```

Used in:
- Unit tests for use cases (no network, no API key)
- `KoogAgentService` when Koog is not configured

## Koog Agent Service (Deferred to Phase 5)

The real `KoogAgentService` integrates with JetBrains Koog:

```kotlin
class KoogAgentService(
    private val secureStorage: SecureStoragePort,
    private val settings: SettingsRepository
) : TextGenPort {
    private val delegate: TextGenPort = FakeTextGen() // TODO: real Koog

    override suspend fun generate(...): Result<String> = delegate.generate(...)

    fun streamChat(message: String): Flow<String> = flow {
        emit("(AI unavailable: Koog integration pending)")
    }
}
```

Once Koog 1.2.0 API is confirmed, replace `FakeTextGen()` with:
```kotlin
private val agent by lazy {
    AIAgent(
        promptExecutor = simpleOpenAIExecutor(
            apiKey = secureStorage.read("ai_key_openai").orEmpty(),
            baseUrl = settings.aiBaseUrl.first()
        ),
        systemPrompt = Prompts.chatSystem,
        toolRegistry = toolRegistry { tool(it) }
    )
}
```

## Tool Registration via Koin

All `SimpleTool<T>` implementations are auto-registered via Koin's `@ComponentScan`:

```kotlin
@OptIn(KoinApiExtension::class)
@ComponentScan("com.singularity.todo.feature.ai.tools")
class AiToolsModule
```

Tools are retrieved via `Koin.getAll<Tool>()` and passed to `ToolRegistry { tools(...) }`.

## Chat Screen

Full streaming chat UI using Compose:

```kotlin
@Composable
fun ChatScreen(service: KoogAgentService = koinInject()) {
    var input by remember { mutableStateOf("") }
    val messages by service.messages.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn { items(messages) { msg -> ChatBubble(msg) } }
    Row {
        TextField(value = input, onValueChange = { input = it })
        IconButton(onClick = {
            scope.launch { service.send(input); input = "" }
        }) { Icon(Icons.Default.Send) }
    }
}
```

Streaming text via `service.streamChat()` → `LazyColumn` items with `LaunchedEffect` for auto-scroll.

## Testing Pattern

Use `FakeTextGen` and test use cases in isolation:

```kotlin
class RefineTaskUseCaseTest {
    @Test
    fun `refine returns new title`() = runTest {
        val fakeGen = FakeTextGen()
        val useCase = RefineTaskUseCase(fakeGen)
        val result = useCase("buy milk")
        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.isNotBlank())
    }
}
```

No mocks — `FakeTextGen` implements the interface directly.

## When to Use This Pattern

- Building AI features in a KMP app that may target JVM, Android, iOS, or JS
- Needing to test AI logic without network or API keys
- Wanting type-safe tool definitions via `@Serializable` data classes
- Using JetBrains Koog as the agent framework (or a compatible alternative)

## Key Files

| File | Purpose |
|---|---|
| `shared/src/commonMain/.../feature/ai/TextGenPort.kt` | Abstraction interface |
| `shared/src/commonMain/.../feature/ai/KoogAgentService.kt` | Koog wrapper + FakeTextGen |
| `shared/src/commonMain/.../feature/ai/prompts/Prompts.kt` | All prompt strings |
| `shared/src/commonMain/.../feature/ai/tools/` | 16 SimpleTool<T> implementations |
| `shared/src/commonMain/.../feature/ai/use_cases/` | 8 use case classes |
| `shared/src/commonMain/.../feature/ai/chat/ChatScreen.kt` | Streaming chat UI |
