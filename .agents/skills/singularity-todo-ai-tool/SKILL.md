---
name: singularity-todo-ai-tool
description: Koog AI tool creation pattern for the Singularity Todo KMP app. Use when adding a new SimpleTool<T> to the AI agent. Covers @Serializable Input/Output DTOs, SimpleTool<Input> subclass, tool name/description, Koog prompt DSL, ToolRegistry registration via @IntoSet, JSON schema helper, and the use-case layer that decodes the tool output. All 16 existing tools follow this pattern.
---

# Koog AI Tool — Adding a New Tool

## Overview

Every AI tool is a `SimpleTool<T>` subclass registered via `@IntoSet` into a `Set<Tool<*, *>>` that `KoogAgentService` consumes. The tool's `execute` returns a **JSON string**; the use case decodes it.

```
User message → KoogAgentService → SimpleTool.execute(input) → JSON string → UseCase.decode → Result
```

## Pattern (5 files per tool)

For a tool named `<Tool>`:

```
feature/ai/tools/<Tool>Input.kt    — @Serializable data class
feature/ai/tools/<Tool>Output.kt   — @Serializable data class
feature/ai/tools/<Tool>Tool.kt      — SimpleTool<T> implementation
feature/ai/use_cases/<Tool>UseCase.kt — decodes output, exposes Result<T>
Prompts.kt                         — add system + user prompt strings
```

## 1. Input DTO

```kotlin
package com.singularity.todo.feature.ai.tools

import kotlinx.serialization.Serializable

@Serializable
data class <Tool>Input(
    val currentTitle: String,
    val description: String? = null,
)
```

## 2. Output DTO

```kotlin
package com.singularity.todo.feature.ai.tools

import kotlinx.serialization.Serializable

@Serializable
data class <Tool>Output(
    val result: String,
    val confidence: Double? = null,
)
```

## 3. Tool implementation

```kotlin
package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.prompt.executor.model.TypeToken
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.prompt
import ai.koog.prompt.executor.model.Prompt
import ai.koog.prompt.llm.KoogClock
import ai.koog.prompt.prompt.PromptDsl
import ai.koog.prompt.prompt.prompt
import com.singularity.todo.feature.ai.Prompts
import com.singularity.todo.feature.ai.extractText
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class <Tool>Tool(
    private val promptExecutor: PromptExecutor,
    private val model: LLModel,
) : SimpleTool<<Tool>Input>(
    TypeToken.of(<Tool>Input::class.java),
    NAME,
    DESCRIPTION
) {
    override suspend fun execute(args: <Tool>Input): String {
        val p = prompt(Prompt.Empty, KoogClock.System) {
            system(Prompts.<tool>System)
            user(Prompts.<tool>User(args.currentTitle, args.description))
        }
        val response = promptExecutor.execute(p, model, emptyList())
        val text = extractText(response)
        return Json.encodeToString(<Tool>Output(result = text.trim()))
    }

    companion object {
        const val NAME = "<tool_name>"
        const val DESCRIPTION = "One-sentence description of what this tool does."
    }
}
```

## 4. Prompts.kt additions

```kotlin
object Prompts {
    const val <tool>System = "You are a productivity assistant..."

    const val <tool>User = "Current title: %s\nDescription: %s"

    fun <tool>User(title: String, description: String?): String =
        "Current title: $title\nDescription: ${description ?: "(none)"}"
}
```

## 5. Use case (decodes tool JSON output)

```kotlin
package com.singularity.todo.feature.ai.use_cases

import com.singularity.todo.core.error.runCatchingResult
import com.singularity.todo.feature.ai.tools.<Tool>Input
import com.singularity.todo.feature.ai.tools.<Tool>Output
import com.singularity.todo.feature.ai.tools.<Tool>Tool
import kotlinx.serialization.json.Json

class <Tool>UseCase(private val tool: <Tool>Tool) {
    suspend operator fun invoke(currentTitle: String, description: String? = null): Result<String> =
        runCatchingResult {
            val json = tool.execute(<Tool>Input(currentTitle, description))
            Json.decodeFromString<<Tool>Output>(json).result
        }
}
```

## Tool registration via @IntoSet

In `Modules.kt`, each tool is already annotated and the list is manual. **After migration to Koin Annotations** (see `singularity-todo-koin-di`):

```kotlin
// Currently (manual list — 18 lines):
single<List<Tool<*, *>>> {
    listOf(get<RefineTaskTool>(), get<SmartRewriteTool>(), ...)
}

// Target (annotation-based, no manual list):
@Single @IntoSet
class <Tool>Tool(get(), get()) : SimpleTool<...>
```

## JSON schema helper

Koog tools need JSON schema for the LLM. Use the built-in helper:

```kotlin
import ai.koog.prompt.executor.model.TypeToken

val schema = TypeToken.of(<Tool>Input::class.java).toJsonSchema()
// Pass to SimpleTool constructor as 3rd param if needed
```

## extractText utility

```kotlin
// In KoogAgentService or Prompts.kt:
private fun extractText(response: Message.Assistant): String =
    response.parts.filterIsInstance<MessagePart.Text>()
        .joinToString("") { it.text }
        .trim()
```

## Koog API reference (v1.1.1)

| What | API |
|---|---|
| Entry point | `AIAgent.builder()` |
| System prompt | `.systemPrompt("...")` |
| Tool registry | `.toolRegistry(ToolRegistry { tool(it) })` |
| Execute | `agent.run(userMessage)` |
| Prompt DSL | `prompt(Prompt.Empty, KoogClock.System) { system("..."); user("...") }` |
| Model constant | `OpenAIModels.Chat.GPT4oMini` |
| Text extraction | `response.parts.filterIsInstance<MessagePart.Text>()` |
| **`Prompt.Empty`** | Capital E, not underscore |
| **`KoogClock.System`** | Capital S, not underscore |
| **2-arg prompt DSL only** | `prompt(Prompt.Empty, KoogClock.System) { ... }` — 1-arg does not exist |

## Common mistakes

```kotlin
// WRONG:
Prompt.EMPTY           // ← no such field
KoogClock.SYSTEM       // ← no such field
prompt { system("...") }   // ← 1-arg doesn't exist

// CORRECT:
Prompt.Empty
KoogClock.System
prompt(Prompt.Empty, KoogClock.System) { system("..."); user("...") }
```

## All 16 existing tools

| Tool | Input | Use case |
|---|---|---|
| `RefineTaskTool` | currentTitle, description | RefineTaskUseCase |
| `SmartRewriteTool` | currentTitle, description | SmartRewriteUseCase |
| `GenerateDescriptionTool` | title | GenerateDescriptionUseCase |
| `DecomposeTaskTool` | title | DecomposeTaskUseCase |
| `GenerateChecklistTool` | title | GenerateChecklistUseCase |
| `PickTimeTool` | title, description | PickTimeUseCase |
| `ClusterTasksTool` | tasks | ClusterTasksUseCase |
| `ClusterNotesTool` | notes | ClusterNotesUseCase |
| `ProjectReviewTool` | projectId | — (KoogAgentService internal) |
| `WeeklyPlanTool` | tasks | — (KoogAgentService internal) |
| `GetNoteTool` | noteId | — (KoogAgentService internal) |
| `GetProjectTool` | projectId | — (KoogAgentService internal) |
| `GetTaskTool` | taskId | — (KoogAgentService internal) |
| `ListTasksTool` | filters | — (KoogAgentService internal) |
| `ListLinkedTasksTool` | noteId | — (KoogAgentService internal) |
| `SearchTasksTool` | query | — (KoogAgentService internal) |

## Write Tools — Contract for State-Modifying Tools

**Read tools** (get/list/search) follow the pattern above. **Write tools** (create/update/delete) have additional requirements:

For full contract (idempotency, authorization, dry-run, error mapping, usage recording), see `singularity-todo-cli-tool-surface`.

### Write Tool Files

After adding all write tools, the complete tool list will be:

| Tool | Input | Notes |
|---|---|---|
| `CreateTaskTool` | title, description?, priority?, projectId?, tagIds?, dueDate? | Creates via CreateTaskUseCase |
| `UpdateTaskTool` | taskId, title?, description?, priority?, projectId?, dueDate? | Auth check: task.userId == currentUser |
| `CompleteTaskTool` | taskId, completed: Boolean | toggleComplete |
| `DeleteTaskTool` | taskId, dryRun: Boolean = true | softDelete, destructiveHint=true |
| `RestoreTaskTool` | taskId | restore |
| `PinTaskTool` | taskId, pinned: Boolean | togglePinned |
| `SetTaskPriorityTool` | taskId, priority: Int (0-4) | update with priority |
| `SetTaskDueDateTool` | taskId, dueDate: String? (ISO-8601) | update with dueDate |
| `MoveTaskToProjectTool` | taskId, projectId: String? | update with projectId |
| `CreateProjectTool` | name, description?, color?, icon? | Creates via CreateProjectUseCase |
| `DeleteProjectTool` | projectId | soft delete, destructiveHint=true |
| `CreateNoteTool` | title, bodyMarkdown, color?, folder? | Converts markdown→html via MarkdownHtmlPort |
| `ArchiveNoteTool` | noteId | archive, destructiveHint=true |
| `PinNoteTool` | noteId, pinned: Boolean | setPinned |
| `CreateTagTool` | name, color? | Creates via TagsRepository |
| `AssignTagTool` | taskId, tagId | setTags |
| `DecomposeAndCreateTool` | taskId | DecomposeTaskUseCase + ChecklistRepository.createBatch |
| `CompleteChecklistItemTool` | itemId, completed: Boolean | upsert checklist item |
| `WriteAdrTool` | title, slug, context, decision, rationale, consequences?, createNote: Boolean | Writes file to docs/decisions/ |
| `ListAdrsTool` | — | Lists .md files from docs/decisions/ |
| `ReadAdrTool` | slug | Reads single .md file from docs/decisions/ |

### Minimal Write Tool Pattern

```kotlin
package com.singularity.todo.feature.ai.tools

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.serialization.TypeToken
import com.singularity.todo.feature.tasks.CreateTaskUseCase
import com.singularity.todo.core.auth.CurrentUser
import kotlinx.serialization.Serializable

@Serializable
data class CreateTaskInput(
    val title: String,
    val description: String? = null,
    val priority: Int? = null,
    val projectId: String? = null,
    val idempotencyKey: String? = null,
    val dryRun: Boolean = false,
)

@Serializable
data class CreateTaskOutput(
    val taskId: String,
    val status: String,
    val dryRunSkipped: Boolean = false,
    val cached: Boolean = false,
)

class CreateTaskTool(
    private val createTask: CreateTaskUseCase,
    private val currentUser: CurrentUser,
) : SimpleTool<CreateTaskInput>(TypeToken.of(CreateTaskInput::class.java), NAME, DESCRIPTION) {

    override suspend fun execute(args: CreateTaskInput): String {
        if (args.dryRun) {
            return kotlinx.serialization.json.Json.encodeToString(
                CreateTaskOutput.serializer(),
                CreateTaskOutput(taskId = "(dry-run)", status = "validated", dryRunSkipped = true)
            )
        }

        val input = CreateTaskInput(
            title = args.title,
            description = args.description,
            priority = args.priority,
            projectId = args.projectId,
        )

        return createTask(input).fold(
            onSuccess = { id ->
                kotlinx.serialization.json.Json.encodeToString(
                    CreateTaskOutput.serializer(),
                    CreateTaskOutput(taskId = id.value, status = "created")
                )
            },
            onFailure = { error ->
                // Return error as JSON string — MCP server layer maps to isError=true
                """{"error": "${error.message}"}"""
            }
        )
    }

    companion object {
        const val NAME = "tasks.create"
        const val DESCRIPTION = "Create a new task. Returns the taskId."
    }
}
```

### Usage Recording in Write Tools

Every write tool should record token usage via `UsageRecorder`:

```kotlin
class CreateTaskTool(
    private val createTask: CreateTaskUseCase,
    private val currentUser: CurrentUser,
    private val usageRecorder: UsageRecorder,    // ← new dependency
) : SimpleTool<CreateTaskInput>(...) {

    override suspend fun execute(args: CreateTaskInput): String {
        val start = kotlin.time.Clock.System.now()
        return createTask(args).fold(
            onSuccess = { id ->
                usageRecorder.record(ToolUsageEvent(
                    toolName = NAME,
                    modelId = "n/a",         // data tool, no LLM
                    inputTokens = 0,
                    outputTokens = 0,
                    totalTokens = 0,
                    costUsdMicros = null,
                    durationMs = (kotlin.time.Clock.System.now() - start).inWholeMilliseconds,
                    profileId = currentUser.profileId,
                    error = null,
                ))
                CreateTaskOutput(taskId = id.value, status = "created").toJson()
            },
            onFailure = { ... }
        )
    }
}
```

See `singularity-todo-llm-usage-tracking` for the full `UsageRecorder` pattern.

### Tool Annotations

Every tool registered in `AiToolsDiModule.kt` must specify its annotation for MCP exposure:

```kotlin
// In ToolAnnotations.kt (mcp-server module):
val TOOL_ANNOTATIONS = mapOf(
    "tasks.create" to ToolAnnotations(idempotentHint = true),
    "tasks.delete" to ToolAnnotations(destructiveHint = true),
    "tasks.list" to ToolAnnotations(readOnlyHint = true),
    // ...
)
```

See `singularity-todo-cli-tool-surface` for the complete annotation table.

### Required Unit Tests

Every write tool must have a test in `commonTest`:

```kotlin
class CreateTaskToolTest() {
    @Test
    fun `creates task and returns taskId`() = runTest {
        val tool = CreateTaskTool(FakeCreateTaskUseCase(), FakeCurrentUser("user-1"))
        val result = tool.execute(CreateTaskInput(title = "Test task"))
        val output = Json.decodeFromString<CreateTaskOutput>(result)
        assertEquals("created", output.status)
        assertTrue(output.taskId.startsWith("task-"))
    }

    @Test
    fun `dryRun returns validated without persisting`() = runTest {
        val fakeUseCase = FakeCreateTaskUseCase()
        val tool = CreateTaskTool(fakeUseCase, FakeCurrentUser("user-1"))
        val result = tool.execute(CreateTaskInput(title = "Test", dryRun = true))
        val output = Json.decodeFromString<CreateTaskOutput>(result)
        assertTrue(output.dryRunSkipped)
        assertEquals(0, fakeUseCase.callCount)
    }

    @Test
    fun `returns error JSON on failure`() = runTest {
        val tool = CreateTaskTool(FailingCreateTaskUseCase(), FakeCurrentUser("user-1"))
        val result = tool.execute(CreateTaskInput(title = "Test"))
        assertTrue(result.contains("\"error\""))
    }
}
```

See `FakeTaskRepository`, `FakeNotesRepository`, `FakeProjectsRepository`, `FakeTagsRepository` in `test/fakes/FakeRepositories.kt` — all already implement the repository interfaces with in-memory `MutableStateFlow`.
