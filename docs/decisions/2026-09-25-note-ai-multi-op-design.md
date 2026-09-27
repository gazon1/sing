---
title: Note AI Multi-Op Design
date: 2026-09-25
status: accepted
---

# Note AI Multi-Op Design

## Context

Lotti (Flutter) has several AI-powered note actions not yet implemented in Singularity Todo:
`Summarize`, `ExtractActions`, `Rewrite` (3 tones), `SuggestTags`. The existing `NoteEditor`
only supports `ImproveNoteUseCase` via a single `improveNote()` call.

## Decision

### Actions enum

```kotlin
enum class NoteAiAction {
    Improve,
    Summarize,
    ExtractActions,
    RewriteOneLiner,
    RewriteTldr,
    RewriteStructured,
    SuggestTags,
}
```

### Result sealed interfaces

```kotlin
sealed interface SummarizeResult {
    data class Ok(val summary: String) : SummarizeResult
    data class Error(val message: String) : SummarizeResult
}

sealed interface ExtractActionsResult {
    data class Ok(val actions: List<String>) : ExtractActionsResult
    data class Error(val message: String) : ExtractActionsResult
}

sealed interface SuggestTagsResult {
    data class Ok(val tags: List<String>) : SuggestTagsResult
    data class Error(val message: String) : SuggestTagsResult
}
```

### NoteAiController extension

`NoteAiController` gains 5 nullable constructor parameters (one per new action) and two methods:

```kotlin
fun isActionAvailable(action: NoteAiAction): Boolean
suspend fun run(action: NoteAiAction, title: String, html: String): Result<Any>
```

Returns `Result<Any>` to unified return type — callers pattern-match on the actual sealed type.

### NoteEditor extension

```kotlin
fun runAiAction(action: NoteAiAction)  // dispatches via ai.run(), applies title/body changes for Improve/Rewrite, emits formatted result for Summarize/ExtractActions/SuggestTags
```

### Tone ext point for Rewrite

`RewriteTone { OneLiner, Tldr, Structured }` enum in `RewriteNoteTool`. Only `OneLiner` is wired to the agent tool list. `Tldr` and `Structured` are defined but not registered — they can be added later without API changes.

### Result formatters (pure)

`formatSummarizeResult`, `formatExtractActionsResult`, `formatSuggestTagsResult` — in `NoteFormatters.kt`. All pure, no Compose dependencies.

### DI wiring (nullable)

```kotlin
NoteAiController(
    improveNote = getOrNull<ImproveNoteUseCase>()?.let(::improveNoteLambda),
    summarizeNote = getOrNull<SummarizeNoteUseCase>()?.let(::summarizeNoteLambda),
    extractActions = getOrNull<ExtractActionsUseCase>()?.let(::extractActionsLambda),
    rewriteNote = getOrNull<RewriteNoteUseCase>()?.let(::rewriteNoteLambda),
    suggestTags = getOrNull<SuggestTagsUseCase>()?.let(::suggestTagsLambda),
)
```

Each lambda adapter is `internal` so tests don't need `SimpleTool` fakes.

## Consequences

- `NoteEditor` now has two AI entry points: `improveNote()` (legacy) and `runAiAction()` (new).
- All 7 actions require AI to be configured — if no AI is available, `isActionAvailable()` returns false and `runAiAction()` is a no-op.
- `ExtractActions` output is only displayed as formatted text in the event notification — actual task creation from extracted actions (pre-filling `TaskCreateSheet`) is deferred to a follow-up that integrates with `CreateTaskFromDraftUseCase`.

## Links

- Tools: `SummarizeNoteTool.kt`, `ExtractActionsTool.kt`, `RewriteNoteTool.kt`, `SuggestTagsTool.kt`
- Use cases: `LlmUseCase.kt` (4 new subclasses)
- Controller: `NoteAiController.kt`
- Editor: `NoteEditor.kt` (`runAiAction`)
- DI: `AiToolsModule.android.kt`, `AiToolsModule.jvm.kt`, `NotesDiModule.kt`
