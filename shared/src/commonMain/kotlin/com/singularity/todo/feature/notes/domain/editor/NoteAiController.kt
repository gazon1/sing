package com.singularity.todo.feature.notes.domain.editor

import com.singularity.todo.core.llm.AI_NOT_CONFIGURED
import com.singularity.todo.feature.ai.tools.RewriteTone
import com.singularity.todo.feature.notes.ExtractActionsResult
import com.singularity.todo.feature.notes.NoteAiAction
import com.singularity.todo.feature.notes.NoteAiResult
import com.singularity.todo.feature.notes.SuggestTagsResult
import com.singularity.todo.feature.notes.SummarizeResult

/**
 * Wraps note AI use cases with availability check + Result→sealed-result mapping.
 * Each lambda is nullable so tests can omit unused actions without needing fake tools.
 */
class NoteAiController(
    private val improveNote: (suspend (title: String, html: String) -> Result<NoteAiResult.Improved>)? = null,
    private val summarizeNote: (suspend (title: String, html: String) -> Result<String>)? = null,
    private val extractActions: (suspend (title: String, html: String) -> Result<List<String>>)? = null,
    private val rewriteNote: (
        suspend (
            title: String,
            html: String,
            tone: String,
        ) -> Result<NoteAiResult.Improved>
    )? = null,
    private val suggestTags: (suspend (title: String, html: String) -> Result<List<String>>)? = null,
) {
    val isAvailable: Boolean get() = improveNote != null

    /**
     * Returns true if the given [action] is available.
     */
    fun isActionAvailable(action: NoteAiAction): Boolean = when (action) {
        NoteAiAction.Improve -> improveNote != null
        NoteAiAction.Summarize -> summarizeNote != null
        NoteAiAction.ExtractActions -> extractActions != null
        NoteAiAction.RewriteOneLiner, NoteAiAction.RewriteTldr, NoteAiAction.RewriteStructured -> rewriteNote != null
        NoteAiAction.SuggestTags -> suggestTags != null
    }

    /**
     * Runs the given [action] with the provided title and body.
     * Returns a result mapped to the appropriate sealed interface.
     */
    @Suppress("UNCHECKED_CAST")
    suspend fun run(action: NoteAiAction, title: String, html: String): Result<Any> = when (action) {
        NoteAiAction.Improve -> {
            val fn = improveNote ?: return Result.failure(IllegalStateException(AI_NOT_CONFIGURED))
            fn(title, html).fold(
                onSuccess = { Result.success(it as Any) },
                onFailure = { Result.failure(it) },
            )
        }

        NoteAiAction.Summarize -> {
            val fn = summarizeNote ?: return Result.failure(IllegalStateException(AI_NOT_CONFIGURED))
            fn(title, html).fold(
                onSuccess = { Result.success(SummarizeResult.Ok(it) as Any) },
                onFailure = { Result.failure(it) },
            )
        }

        NoteAiAction.ExtractActions -> {
            val fn = extractActions ?: return Result.failure(
                IllegalStateException(AI_NOT_CONFIGURED),
            )
            fn(title, html).fold(
                onSuccess = { Result.success(ExtractActionsResult.Ok(it) as Any) },
                onFailure = { Result.failure(it) },
            )
        }

        NoteAiAction.RewriteOneLiner, NoteAiAction.RewriteTldr, NoteAiAction.RewriteStructured -> {
            val fn = rewriteNote ?: return Result.failure(IllegalStateException(AI_NOT_CONFIGURED))
            val tone = when (action) {
                NoteAiAction.RewriteOneLiner -> RewriteTone.OneLiner.name
                NoteAiAction.RewriteTldr -> RewriteTone.Tldr.name
                NoteAiAction.RewriteStructured -> RewriteTone.Structured.name
            }
            fn(title, html, tone).fold(
                onSuccess = { Result.success(it as Any) },
                onFailure = { Result.failure(it) },
            )
        }

        NoteAiAction.SuggestTags -> {
            val fn = suggestTags ?: return Result.failure(IllegalStateException(AI_NOT_CONFIGURED))
            fn(title, html).fold(
                onSuccess = { Result.success(SuggestTagsResult.Ok(it) as Any) },
                onFailure = { Result.failure(it) },
            )
        }
    }

    /**
     * Runs AI improvement (legacy single-action entry point).
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

/** Production DI adapter for [SummarizeNoteUseCase]. */
internal fun summarizeNoteLambda(
    useCase: com.singularity.todo.feature.ai.use_cases.SummarizeNoteUseCase,
): suspend (String, String) -> Result<String> = { title, body ->
    useCase(title, body)
}

/** Production DI adapter for [ExtractActionsUseCase]. */
internal fun extractActionsLambda(
    useCase: com.singularity.todo.feature.ai.use_cases.ExtractActionsUseCase,
): suspend (String, String) -> Result<List<String>> = { title, body ->
    useCase(title, body)
}

/** Production DI adapter for [RewriteNoteUseCase]. */
internal fun rewriteNoteLambda(
    useCase: com.singularity.todo.feature.ai.use_cases.RewriteNoteUseCase,
): suspend (String, String, String) -> Result<NoteAiResult.Improved> = { title, body, tone ->
    useCase(title, body, tone).map { NoteAiResult.Improved(it.title, it.body) }
}

/** Production DI adapter for [SuggestTagsUseCase]. */
internal fun suggestTagsLambda(
    useCase: com.singularity.todo.feature.ai.use_cases.SuggestTagsUseCase,
): suspend (String, String) -> Result<List<String>> = { title, body ->
    useCase(title, body)
}
