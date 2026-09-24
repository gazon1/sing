package com.singularity.todo.feature.notes.domain.editor

import com.singularity.todo.feature.notes.NoteAiResult

/**
 * Wraps the AI note-improver with availability check + Result→[NoteAiResult] mapping.
 * Takes a lambda so tests don't need to construct [com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase]
 * with a fake `SimpleTool` — DI adapts from the real use case at the boundary.
 */
class NoteAiController(
    private val improveNote: (suspend (title: String, html: String) -> Result<NoteAiResult.Improved>)?,
) {
    val isAvailable: Boolean get() = improveNote != null

    /**
     * Runs AI improvement.
     * @return [NoteAiResult.Improved] on success, [NoteAiResult.Error] on failure or when unavailable.
     */
    suspend fun improve(title: String, html: String): NoteAiResult {
        val tool = improveNote ?: return NoteAiResult.Error("AI unavailable")
        return tool(title, html).fold(
            onSuccess = { it },
            onFailure = { NoteAiResult.Error(it.message ?: "Failed") },
        )
    }
}

/** Production DI adapter: adapts [ImproveNoteUseCase] to the lambda [NoteAiController] expects. */
internal fun improveNoteLambda(
    useCase: com.singularity.todo.feature.ai.use_cases.ImproveNoteUseCase,
): suspend (String, String) -> Result<NoteAiResult.Improved> = { title, body ->
    useCase(title, body).map { NoteAiResult.Improved(it.title, it.body) }
}
