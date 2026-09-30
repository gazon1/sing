---
name: singularity-todo-ai-action-registry-pattern
description: AI action slot-API pattern for Singularity Todo. Use when adding a new AI action to a feature (task or note), or when wiring an AI bottom sheet to a ViewModel. Covers the AiActionDescriptor registry, per-action nullable lambdas in the controller, and the wiring checklist for screen-to-VM-to-controller connection.
---

# AI Action Registry Pattern

When a feature needs to expose multiple AI-powered actions (improve, summarize, extract, rewrite, suggest tags, etc.), the **slot-API pattern** avoids a large `when` expression in the ViewModel and makes each action independently testable.

This pattern was established during the Lotti cherry-pick (Phase C + Phase F).

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Screen (Sheet or BottomBar)                                    │
│  onSelect: (NoteAiAction) -> Unit                              │
└──────────────────────────┬──────────────────────────────────────┘
                           │ onAiAction?.invoke(action)
┌──────────────────────────▼──────────────────────────────────────┐
│  ViewModel  runAiAction(action: NoteAiAction)                    │
│    └─ ai.run(action, title, html)  ← nullable lambda dispatch    │
└──────────────────────────┬──────────────────────────────────────┘
                           │ lambda call
┌──────────────────────────▼──────────────────────────────────────┐
│  NoteAiController (or TaskAiController)                          │
│    val improveNote: (suspend (String, String) -> Result<T>)?    │
│    val summarizeNote: (suspend (String, String) -> Result<R>)? │
│    val extractActions: (suspend (String, String) -> Result<..>)?│
│    val rewriteNote: (suspend (String, String, String) -> Result)?│
│    val suggestTags: (suspend (String, String) -> Result<..>)?   │
│                                                                  │
│    fun isActionAvailable(action: NoteAiAction): Boolean          │
│    fun run(action: NoteAiAction, title: String, html: String)  │
│      = when (action) {                                          │
│          Improve -> improveNote?.invoke(title, html)             │
│          Summarize -> summarizeNote?.invoke(title, html)         │
│          ...                                                    │
│        }                                                        │
└──────────────────────────┬──────────────────────────────────────┘
                           │ useCase(title, body)
┌──────────────────────────▼──────────────────────────────────────┐
│  LlmUseCase<T, R>  (one per tool)                              │
│    SummarizeNoteUseCase, ExtractActionsUseCase,                  │
│    RewriteNoteUseCase(tone), SuggestTagsUseCase, etc.           │
└──────────────────────────┬──────────────────────────────────────┘
                           │
┌──────────────────────────▼──────────────────────────────────────┐
│  Koog SimpleTool<T>  (registered in AiToolsModule)              │
└─────────────────────────────────────────────────────────────────┘
```

## The Controller Class

```kotlin
// feature/notes/domain/editor/NoteAiController.kt
class NoteAiController(
    private val improveNote: (suspend (String, String) -> Result<NoteAiResult.Improved>)? = null,
    private val summarizeNote: (suspend (String, String) -> Result<String>)? = null,
    private val extractActions: (suspend (String, String) -> Result<List<String>>)? = null,
    private val rewriteNote: (suspend (String, String, String) -> Result<NoteAiResult.Improved>)? = null,
    private val suggestTags: (suspend (String, String) -> Result<List<String>>)? = null,
) {
    /** True when any action is available (AI is configured). */
    val isAvailable: Boolean
        get() = improveNote != null

    /** True when a specific action is available. */
    fun isActionAvailable(action: NoteAiAction): Boolean = when (action) {
        NoteAiAction.Improve -> improveNote != null
        NoteAiAction.Summarize -> summarizeNote != null
        NoteAiAction.ExtractActions -> extractActions != null
        NoteAiAction.RewriteOneLiner,
        NoteAiAction.RewriteTldr,
        NoteAiAction.RewriteStructured -> rewriteNote != null
        NoteAiAction.SuggestTags -> suggestTags != null
    }

    suspend fun run(action: NoteAiAction, title: String, html: String): Result<Any> {
        val fn: Any? = when (action) {
            NoteAiAction.Improve -> improveNote
            NoteAiAction.Summarize -> summarizeNote
            NoteAiAction.ExtractActions -> extractActions
            NoteAiAction.RewriteOneLiner -> rewriteNote
            NoteAiAction.RewriteTldr -> rewriteNote
            NoteAiAction.RewriteStructured -> rewriteNote
            NoteAiAction.SuggestTags -> suggestTags
        }
        @Suppress("UNCHECKED_CAST")
        return when (action) {
            NoteAiAction.RewriteOneLiner,
            NoteAiAction.RewriteTldr,
            NoteAiAction.RewriteStructured -> {
                val tone = action.name.removePrefix("Rewrite")
                (fn as (String, String, String) -> Result<NoteAiResult.Improved>)
                    .invoke(title, html, tone).map { it as Any }
            }
            else -> (fn as (String, String) -> Result<Any>).invoke(title, html)
        }
    }
}
```

**Key properties:**
- Each action is a **nullable field** — allows graceful degradation when AI is unconfigured
- `run()` returns `Result<Any>` — each action has its own result type
- `isActionAvailable()` checks if the lambda is non-null
- Rewrite actions share one lambda but pass a `tone` string

## The Lambda Factory Functions

```kotlin
// feature/notes/domain/editor/NoteAiController.kt (extension factories)

internal fun improveNoteLambda(
    useCase: ImproveNoteUseCase,
): suspend (String, String) -> Result<NoteAiResult.Improved> = { title, html ->
    useCase(title, html).map { NoteAiResult.Improved(it.title, it.body) }
}

internal fun summarizeNoteLambda(
    useCase: SummarizeNoteUseCase,
): suspend (String, String) -> Result<String> = { title, html ->
    useCase(title, html)
}

internal fun extractActionsLambda(
    useCase: ExtractActionsUseCase,
): suspend (String, String) -> Result<List<String>> = { title, html ->
    useCase(title, html)
}

internal fun rewriteNoteLambda(
    useCase: RewriteNoteUseCase,
): suspend (String, String, String) -> Result<NoteAiResult.Improved> = { title, body, tone ->
    useCase(title, body, tone).map { NoteAiResult.Improved(it.title, it.body) }
}

internal fun suggestTagsLambda(
    useCase: SuggestTagsUseCase,
): suspend (String, String) -> Result<List<String>> = { title, html ->
    useCase(title, html)
}
```

## The DI Wiring (Android + JVM)

```kotlin
// core/di/NotesDiModule.kt
val notesModule = module {
    viewModel { (noteId: NoteId?) ->
        NoteEditor(
            deps = get(),
            linkRepo = get(),
            idGen = get(),
            ai = NoteAiController(
                improveNote = getOrNull<ImproveNoteUseCase>()?.let { improveNoteLambda(it) },
                summarizeNote = getOrNull<SummarizeNoteUseCase>()?.let { summarizeNoteLambda(it) },
                extractActions = getOrNull<ExtractActionsUseCase>()?.let { extractActionsLambda(it) },
                rewriteNote = getOrNull<RewriteNoteUseCase>()?.let { rewriteNoteLambda(it) },
                suggestTags = getOrNull<SuggestTagsUseCase>()?.let { suggestTagsLambda(it) },
            ),
            log = get(),
        )
    }
}
```

**Rule**: use `getOrNull<T>()` — the AI features are optional. If the use case isn't registered (profile doesn't support AI), the lambda is `null` and `isActionAvailable` returns `false`.

## The Action Enum

```kotlin
// feature/notes/Ids.kt
enum class NoteAiAction {
    Improve,           // existing — improveNote
    Summarize,         // new — summarizeNote
    ExtractActions,    // new — extractActions
    RewriteOneLiner,   // new — rewriteNote(tone="OneLiner")
    RewriteTldr,       // new — rewriteNote(tone="Tldr")
    RewriteStructured, // new — rewriteNote(tone="Structured")
    SuggestTags,       // new — suggestTags
}
```

For tasks, the equivalent enum is in `feature/tasks/domain/model/TaskAi.kt`:

```kotlin
enum class TaskAiAction {
    RefineTitle,
    GenerateDescription,
    GenerateChecklist,
    Decompose,
    SuggestTime,
}
```

## Result Types

```kotlin
// feature/notes/Ids.kt
sealed interface SummarizeResult { Ok, Error }
sealed interface ExtractActionsResult { Ok, Error }
sealed interface SuggestTagsResult { Ok, Error }
```

Results are displayed via `NotesUiEvent.AiResult(text)` — a one-shot event sent to `NotificationHost`.

## The ViewModel Method

```kotlin
// NotesViewModel / NoteEditor
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

## The UI Sheet (NoteAiActionSheet)

```kotlin
// feature/notes/presentation/components/NoteAiActionSheet.kt
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteAiActionSheet(
    onSelect: (NoteAiAction) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 32.dp)) {
            Text("AI Actions", style = MaterialTheme.typography.titleMedium)
            // One ListItem per action with icon + title + subtitle
            AiActionItem(
                icon = Icons.Filled.AutoAwesome,
                title = "Improve writing",
                subtitle = "Polish clarity, conciseness, and readability",
                onClick = { onSelect(NoteAiAction.Improve) },
            )
            // ... more actions
        }
    }
}
```

## Wiring Checklist

When adding a new AI action:

- [ ] Add to `NoteAiAction` / `TaskAiAction` enum
- [ ] Add lambda parameter to `NoteAiController` / `TaskAiController`
- [ ] Add `isActionAvailable` branch
- [ ] Add `run()` branch (for `Result<Any>` return, use `@Suppress("UNCHECKED_CAST")`)
- [ ] Add lambda factory function (`*Lambda()`)
- [ ] Add `LlmUseCase` subclass in `feature/ai/use_cases/`
- [ ] Add `SimpleTool` in `feature/ai/tools/`
- [ ] Add prompts in `Prompts.kt`
- [ ] Register in `AiToolsModule.android.kt` + `AiToolsModule.jvm.kt`
- [ ] Wire nullable lambda in `NotesDiModule` / `TasksDiModule` via `getOrNull<T>()`
- [ ] Add `result` interface in `Ids.kt` if needed for typed result display
- [ ] Add formatter in `NoteFormatters.kt` / `TaskFormatters.kt`
- [ ] Add sheet item in `NoteAiActionSheet` / `TaskAiBottomSheet`
- [ ] Add `runAiAction` branch in ViewModel
- [ ] Add unit test (use `FakeTextGen(failureMessage = "...")` for error scenarios)

## Testing with FakeTextGen

```kotlin
// Success path
val fakeTextGen = FakeTextGen(output = "Summarized text")
val useCase = SummarizeNoteUseCase(SummarizeNoteTool(fakeTextGen))

// Failure path
val fakeTextGen = FakeTextGen(failureMessage = "Rate limit exceeded")
val useCase = SummarizeNoteUseCase(SummarizeNoteTool(fakeTextGen))
```

## See Also

- `singularity-todo-koog-agent` — how Koog tools are registered in the AI agent
- `singularity-todo-mcp-server` — how AI tools are exposed via MCP
- `singularity-todo-feature-scaffold` — canonical CRUD feature template
- `singularity-todo-sheet-extraction` — how to wire bottom sheets to screens
- `docs/decisions/2026-09-25-ai-action-registry-design.md` — Phase C ADR
- `docs/decisions/2026-09-25-note-ai-multi-op-design.md` — Phase F ADR
