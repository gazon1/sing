---
name: singularity-todo-note-ai-multi-op
description: Note AI multi-operation implementation guide for Singularity Todo. Documents the 4 new AI operations (Summarize, ExtractActions, Rewrite, SuggestTags), their Koog tools, prompts, result types, and the complete wiring from NoteAiActionSheet to NoteEditor.runAiAction. Use when adding a new note AI action or debugging the existing implementation.
---

# Note AI Multi-Op Implementation

Phase F (2026-09-25) added 4 new AI-powered operations to the note editor: **Summarize**, **ExtractActions**, **Rewrite** (3 tones), and **SuggestTags**. This skill documents the complete implementation for future reference.

## Architecture Overview

```
NoteAiActionSheet (UI)
    └─ onSelect: (NoteAiAction) -> Unit
           │
           ▼
NoteEditor.runAiAction(action: NoteAiAction)
    └─ ai.run(action, title, html)  [NoteAiController]
           │
           ▼ (lambda dispatch)
SummarizeNoteUseCase / ExtractActionsUseCase /
RewriteNoteUseCase(tone) / SuggestTagsUseCase
    └─ LlmUseCase<T, R>  [base class]
           │
           ▼
SummarizeNoteTool / ExtractActionsTool /
RewriteNoteTool / SuggestTagsTool  [Koog SimpleTool]
    └─ Prompts.kt  [system + user templates]
           │
           ▼
TextGenPort / FakeTextGen  [platform port]
```

## The 4 Operations

| Action | Input | Output | Tool |
|---|---|---|---|
| `Summarize` | title + body HTML | One-sentence summary | `SummarizeNoteTool` |
| `ExtractActions` | title + body HTML | List of action strings | `ExtractActionsTool` |
| `RewriteOneLiner` | title + body HTML + "OneLiner" | Improved title + body | `RewriteNoteTool` |
| `RewriteTldr` | title + body HTML + "Tldr" | TL;DR summary | `RewriteNoteTool` |
| `RewriteStructured` | title + body HTML + "Structured" | Structured output | `RewriteNoteTool` |
| `SuggestTags` | title + body HTML | List of tag strings | `SuggestTagsTool` |

## The 4 Tools

### SummarizeNoteTool

```kotlin
// feature/ai/tools/SummarizeNoteTool.kt
class SummarizeNoteTool(private val textGen: TextGenPort) : SimpleTool<SummarizeNoteInput> {
    data class Input(val title: String, val body: String)
    data class Output(val summary: String)
    override val inputSerializer = SummarizeNoteInput.serializer()
    override val outputSerializer = SummarizeNoteOutput.serializer()
    override val type = "summarize_note"
    override val description = "Summarize a note into a one-sentence summary."
}
```

### ExtractActionsTool

```kotlin
class ExtractActionsTool(private val textGen: TextGenPort) : SimpleTool<ExtractActionsInput> {
    data class Input(val title: String, val body: String)
    data class Output(val actions: List<String>)
    override val type = "extract_actions"
    override val description = "Extract actionable tasks from a note."
}
```

### RewriteNoteTool

```kotlin
// tone: OneLiner | Tldr | Structured
class RewriteNoteTool(private val textGen: TextGenPort) : SimpleTool<RewriteNoteInput> {
    data class Input(val title: String, val body: String, val tone: String = "OneLiner")
    data class Output(val title: String, val body: String)
    override val type = "rewrite_note"
    override val description = "Rewrite a note in a different style (OneLiner, TLDR, or Structured)."
}
```

### SuggestTagsTool

```kotlin
class SuggestTagsTool(private val textGen: TextGenPort) : SimpleTool<SuggestTagsInput> {
    data class Input(val title: String, val body: String)
    data class Output(val tags: List<String>)
    override val type = "suggest_tags"
    override val description = "Suggest tags for a note based on its content."
}
```

## The Use Cases

Each use case wraps the tool via `LlmUseCase<T, R>`:

```kotlin
// feature/ai/use_cases/LlmUseCase.kt
abstract class LlmUseCase<T, R>(
    private val tool: SimpleTool<T>,
    private val outputSerializer: KSerializer<R>,
) {
    suspend operator fun invoke(input: T): Result<R> = runCatching {
        tool.execute(input)
    }
}
```

```kotlin
class SummarizeNoteUseCase(tool: SummarizeNoteTool) :
    LlmUseCase<SummarizeNoteInput, SummarizeNoteOutput>(
        tool, SummarizeNoteOutput.serializer(),
    ) {
    suspend operator fun invoke(title: String, body: String): Result<String> =
        execute(SummarizeNoteInput(title, body)).map { it.summary }
}
```

## Prompts

All prompts live in `feature/ai/prompts/Prompts.kt`:

```kotlin
// Summarize
val summarizeNoteSystem = """
    You are a concise note-taking assistant. Summarize the note in one clear sentence.
""".trimIndent()

fun summarizeNoteUser(title: String, body: String) = user(
    "Title: $title\n\n$body",
)

// ExtractActions
val extractActionsSystem = """
    You are an action extraction assistant. Identify actionable tasks from the note.
    Return a JSON array of action strings.
""".trimIndent()

// Rewrite — tone-dependent system prompt
val rewriteNoteSystemFor(tone: String) = when (tone) {
    "Tldr" -> """You are a TL;DR assistant. Summarize with key takeaways."""
    "Structured" -> """You are a structured notes assistant. Format with headings and bullets."""
    else -> """You are a writing coach. Rewrite as a short, punchy paragraph."""
}
```

## Result Types

```kotlin
// feature/notes/Ids.kt

/** Result of an AI note improvement or rewrite action. */
sealed interface NoteAiResult {
    data class Improved(val title: String, val body: String) : NoteAiResult
    data class Error(val message: String) : NoteAiResult
}

/** Result of a Summarize action. */
sealed interface SummarizeResult {
    data class Ok(val summary: String) : SummarizeResult
    data class Error(val message: String) : SummarizeResult
}

/** Result of an ExtractActions action. */
sealed interface ExtractActionsResult {
    data class Ok(val actions: List<String>) : ExtractActionsResult
    data class Error(val message: String) : ExtractActionsResult
}

/** Result of a SuggestTags action. */
sealed interface SuggestTagsResult {
    data class Ok(val tags: List<String>) : SuggestTagsResult
    data class Error(val message: String) : SuggestTagsResult
}
```

## The `NoteAiController.run()` Method

```kotlin
// feature/notes/domain/editor/NoteAiController.kt
suspend fun run(action: NoteAiAction, title: String, html: String): Result<Any> {
    return when (action) {
        NoteAiAction.Improve -> improveNote?.invoke(title, html)
            ?.map { it as Any }
        NoteAiAction.Summarize -> summarizeNote?.invoke(title, html)
            ?.map { success ->
                SummarizeResult.Ok(success).let { it as Any }
            }
        NoteAiAction.ExtractActions -> extractActions?.invoke(title, html)
            ?.map { success ->
                ExtractActionsResult.Ok(success).let { it as Any }
            }
        NoteAiAction.RewriteOneLiner,
        NoteAiAction.RewriteTldr,
        NoteAiAction.RewriteStructured -> {
            val tone = action.name.removePrefix("Rewrite")
            rewriteNote?.invoke(title, html, tone)
                ?.map { it as Any }
        }
        NoteAiAction.SuggestTags -> suggestTags?.invoke(title, html)
            ?.map { success ->
                SuggestTagsResult.Ok(success).let { it as Any }
            }
    } ?: Result.failure(IllegalStateException("Action not available"))
}
```

## The `NoteEditor.runAiAction()` Handler

```kotlin
// feature/notes/presentation/viewmodel/NoteEditor.kt
fun runAiAction(action: NoteAiAction) {
    if (!ai.isActionAvailable(action)) return
    scope.launch {
        val c = state.current ?: return@launch
        val result = ai.run(action, c.title, c.html)
        result.fold(
            onSuccess = { success ->
                when {
                    success is NoteAiResult.Improved -> {
                        state.applyImprove(success.title, success.body)
                        _events.trySend(NotesUiEvent.AiResult(formatNoteAiResult(success)))
                    }
                    success is SummarizeResult -> {
                        _events.trySend(NotesUiEvent.AiResult(formatSummarizeResult(success)))
                    }
                    success is ExtractActionsResult -> {
                        _events.trySend(NotesUiEvent.AiResult(formatExtractActionsResult(success)))
                    }
                    success is SuggestTagsResult -> {
                        _events.trySend(NotesUiEvent.AiResult(formatSuggestTagsResult(success)))
                    }
                }
            },
            onFailure = { error ->
                _events.trySend(NotesUiEvent.AiResult("Action failed: ${error.message ?: "unknown"}"))
            },
        )
    }
}
```

## The `NoteAiActionSheet` UI

```kotlin
@Composable
fun NoteAiActionSheet(
    onSelect: (NoteAiAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column {
            Text("AI Actions", style = MaterialTheme.typography.titleMedium)

            // Improve (primary)
            AiActionItem(Icons.Filled.AutoAwesome, "Improve writing", ...)

            // Content actions
            AiActionItem(Icons.Filled.Summarize, "Summarize", ...)
            AiActionItem(Icons.Filled.Checklist, "Extract actions", ...)

            // Rewrite section (subheader)
            Text("Rewrite", style = MaterialTheme.typography.labelMedium)
            AiActionItem(Icons.Filled.Edit, "One-liner", ...)
            AiActionItem(Icons.Filled.Description, "TL;DR", ...)
            AiActionItem(Icons.Filled.FormatListBulleted, "Structured", ...)

            // Tags
            AiActionItem(Icons.Filled.Label, "Suggest tags", ...)
        }
    }
}
```

## DI Registration (Android)

```kotlin
// core/di/AiToolsModule.android.kt
single { SummarizeNoteTool(get<TextGenPort>()) }
single { ExtractActionsTool(get<TextGenPort>()) }
single { RewriteNoteTool(get<TextGenPort>()) }
single { SuggestTagsTool(get<TextGenPort>()) }

single { SummarizeNoteUseCase(get()) }
single { ExtractActionsUseCase(get()) }
single { RewriteNoteUseCase(get()) }
single { SuggestTagsUseCase(get()) }
```

## Deferred Items

The following are **intentionally not implemented** in Phase F:

| Item | Reason | Follow-up |
|---|---|---|
| `ExtractActions` → pre-fill `TaskCreateSheet` | Requires `CreateTaskFromDraftUseCase` integration | Phase F follow-up |
| Template picker toolbar action in `NoteEditorScreen` | Toolbar space constrained; deferred to AI actions sheet expansion | Phase F follow-up |
| `Tldr` / `Structured` tone as separate Koog tools | Single `RewriteNoteTool` with tone param is sufficient; separate tools add no value | When tone-specific prompts need divergent system instructions |

## Key Files

| File | Purpose |
|---|---|
| `feature/notes/Ids.kt` | `NoteAiAction` enum, result sealed interfaces |
| `feature/notes/domain/editor/NoteAiController.kt` | Per-action nullable lambdas, `isActionAvailable`, `run()` |
| `feature/notes/presentation/viewmodel/NoteEditor.kt` | `runAiAction()` method, `improveNote()` legacy |
| `feature/notes/presentation/components/NoteAiActionSheet.kt` | Bottom sheet with all 7 actions |
| `feature/notes/presentation/screen/NoteEditorScreen.kt` | `showAiSheet` state, toolbar wiring, sheet rendering |
| `feature/ai/tools/SummarizeNoteTool.kt` | Koog `SimpleTool` |
| `feature/ai/tools/ExtractActionsTool.kt` | Koog `SimpleTool` |
| `feature/ai/tools/RewriteNoteTool.kt` | Koog `SimpleTool` with tone |
| `feature/ai/tools/SuggestTagsTool.kt` | Koog `SimpleTool` |
| `feature/ai/use_cases/LlmUseCase.kt` | 4 use case subclasses |
| `feature/ai/prompts/Prompts.kt` | System prompts + user templates |
| `core/di/AiToolsModule.android.kt` / `.jvm.kt` | Tool + use case registration |
| `core/di/NotesDiModule.kt` | `NoteAiController` wiring with `getOrNull` |

## See Also

- `singularity-todo-ai-action-registry-pattern` — the general slot-API pattern for AI actions
- `singularity-todo-sheet-extraction` — AI action sheet wiring (showAiSheet state pattern)
- `singularity-todo-feature-scaffold` — canonical CRUD feature template
- `docs/decisions/2026-09-25-note-ai-multi-op-design.md` — Phase F ADR
