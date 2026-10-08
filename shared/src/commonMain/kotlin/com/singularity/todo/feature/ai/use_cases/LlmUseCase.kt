package com.singularity.todo.feature.ai.use_cases

import ai.koog.agents.core.tools.SimpleTool
import com.singularity.todo.feature.ai.tools.DecomposeTaskInput
import com.singularity.todo.feature.ai.tools.DecomposeTaskOutput
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
import com.singularity.todo.feature.ai.tools.ExtractActionsInput
import com.singularity.todo.feature.ai.tools.ExtractActionsOutput
import com.singularity.todo.feature.ai.tools.ExtractActionsTool
import com.singularity.todo.feature.ai.tools.GenerateChecklistInput
import com.singularity.todo.feature.ai.tools.GenerateChecklistOutput
import com.singularity.todo.feature.ai.tools.GenerateChecklistTool
import com.singularity.todo.feature.ai.tools.GenerateDescriptionInput
import com.singularity.todo.feature.ai.tools.GenerateDescriptionOutput
import com.singularity.todo.feature.ai.tools.GenerateDescriptionTool
import com.singularity.todo.feature.ai.tools.PickTimeInput
import com.singularity.todo.feature.ai.tools.PickTimeOutput
import com.singularity.todo.feature.ai.tools.PickTimeTool
import com.singularity.todo.feature.ai.tools.RefineTaskInput
import com.singularity.todo.feature.ai.tools.RefineTaskOutput
import com.singularity.todo.feature.ai.tools.RefineTaskTool
import com.singularity.todo.feature.ai.tools.RewriteNoteInput
import com.singularity.todo.feature.ai.tools.RewriteNoteOutput
import com.singularity.todo.feature.ai.tools.RewriteNoteTool
import com.singularity.todo.feature.ai.tools.SuggestTagsInput
import com.singularity.todo.feature.ai.tools.SuggestTagsOutput
import com.singularity.todo.feature.ai.tools.SuggestTagsTool
import com.singularity.todo.feature.ai.tools.SummarizeNoteInput
import com.singularity.todo.feature.ai.tools.SummarizeNoteOutput
import com.singularity.todo.feature.ai.tools.SummarizeNoteTool
import com.singularity.todo.feature.ai.tools.ImproveNoteInput
import com.singularity.todo.feature.ai.tools.ImproveNoteOutput
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import com.singularity.todo.core.error.runCatchingCancellable

/**
 * Base class for LLM-powered use cases.
 * Encapsulates tool execution and JSON decoding. Subclasses define the [invoke] signature.
 *
 * @param I Input type (tool argument)
 * @param O Output type (decoded from tool's JSON response)
 */
abstract class LlmUseCase<I, O>(protected val tool: SimpleTool<I>, protected val outputSerializer: KSerializer<O>) {

    protected suspend fun execute(input: I): Result<O> = runCatchingCancellable {
        val json = tool.execute(input)
        Json.decodeFromString(outputSerializer, json)
    }
}

// ─── Concrete use cases ─────────────────────────────────────────────────────────

class RefineTaskUseCase(tool: RefineTaskTool) :
    LlmUseCase<RefineTaskInput, RefineTaskOutput>(
        tool,
        RefineTaskOutput.serializer(),
    ) {
    suspend operator fun invoke(currentTitle: String, description: String? = null): Result<String> =
        execute(RefineTaskInput(currentTitle, description)).map { it.newTitle }
}

class GenerateDescriptionUseCase(tool: GenerateDescriptionTool) :
    LlmUseCase<GenerateDescriptionInput, GenerateDescriptionOutput>(
        tool,
        GenerateDescriptionOutput.serializer(),
    ) {
    suspend operator fun invoke(title: String): Result<String> =
        execute(GenerateDescriptionInput(title)).map { it.description }
}

class GenerateChecklistUseCase(tool: GenerateChecklistTool) :
    LlmUseCase<GenerateChecklistInput, GenerateChecklistOutput>(
        tool,
        GenerateChecklistOutput.serializer(),
    ) {
    suspend operator fun invoke(title: String, description: String? = null): Result<List<String>> =
        execute(GenerateChecklistInput(title, description)).map { it.steps }
}

class PickTimeUseCase(tool: PickTimeTool) :
    LlmUseCase<PickTimeInput, PickTimeOutput>(
        tool,
        PickTimeOutput.serializer(),
    ) {
    suspend operator fun invoke(title: String, description: String? = null): Result<String> =
        execute(PickTimeInput(title, description)).map { it.suggestedTime }
}

class DecomposeTaskUseCase(tool: DecomposeTaskTool) :
    LlmUseCase<DecomposeTaskInput, DecomposeTaskOutput>(
        tool,
        DecomposeTaskOutput.serializer(),
    ) {
    suspend operator fun invoke(title: String, description: String? = null): Result<List<String>> =
        execute(DecomposeTaskInput(title, description)).map { it.subTasks }
}

class ImproveNoteUseCase(tool: SimpleTool<ImproveNoteInput>) :
    LlmUseCase<ImproveNoteInput, ImproveNoteOutput>(
        tool,
        ImproveNoteOutput.serializer(),
    ) {
    suspend operator fun invoke(title: String, body: String): Result<ImproveNoteOutput> =
        execute(ImproveNoteInput(title, body))
}

class SummarizeNoteUseCase(tool: SummarizeNoteTool) :
    LlmUseCase<SummarizeNoteInput, SummarizeNoteOutput>(
        tool,
        SummarizeNoteOutput.serializer(),
    ) {
    suspend operator fun invoke(title: String, body: String): Result<String> =
        execute(SummarizeNoteInput(title, body)).map { it.summary }
}

class ExtractActionsUseCase(tool: ExtractActionsTool) :
    LlmUseCase<ExtractActionsInput, ExtractActionsOutput>(
        tool,
        ExtractActionsOutput.serializer(),
    ) {
    suspend operator fun invoke(title: String, body: String): Result<List<String>> =
        execute(ExtractActionsInput(title, body)).map { it.actions }
}

class RewriteNoteUseCase(tool: RewriteNoteTool) :
    LlmUseCase<RewriteNoteInput, RewriteNoteOutput>(
        tool,
        RewriteNoteOutput.serializer(),
    ) {
    suspend operator fun invoke(title: String, body: String, tone: String = "OneLiner"): Result<RewriteNoteOutput> =
        execute(RewriteNoteInput(title, body, tone))
}

class SuggestTagsUseCase(tool: SuggestTagsTool) :
    LlmUseCase<SuggestTagsInput, SuggestTagsOutput>(
        tool,
        SuggestTagsOutput.serializer(),
    ) {
    suspend operator fun invoke(title: String, body: String): Result<List<String>> =
        execute(SuggestTagsInput(title, body)).map { it.tags }
}

/** ProjectReviewTool returns raw text — special case, not using the generic factory */
class ProjectReviewUseCase(private val tool: com.singularity.todo.feature.ai.tools.ProjectReviewTool) {
    suspend operator fun invoke(
        projectName: String,
        taskTitles: List<String>,
    ): Result<String> = runCatchingCancellable {
        tool.execute(com.singularity.todo.feature.ai.tools.ProjectReviewInput(projectName, taskTitles))
    }
}
