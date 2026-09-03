package com.singularity.todo.feature.ai.use_cases

import ai.koog.agents.core.tools.SimpleTool
import com.singularity.todo.feature.ai.tools.ClusterNotesInput
import com.singularity.todo.feature.ai.tools.ClusterNotesOutput
import com.singularity.todo.feature.ai.tools.ClusterNotesTool
import com.singularity.todo.feature.ai.tools.ClusterTasksInput
import com.singularity.todo.feature.ai.tools.ClusterTasksOutput
import com.singularity.todo.feature.ai.tools.ClusterTasksTool
import com.singularity.todo.feature.ai.tools.DecomposeTaskInput
import com.singularity.todo.feature.ai.tools.DecomposeTaskOutput
import com.singularity.todo.feature.ai.tools.DecomposeTaskTool
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
import com.singularity.todo.feature.ai.tools.SmartRewriteInput
import com.singularity.todo.feature.ai.tools.SmartRewriteOutput
import com.singularity.todo.feature.ai.tools.SmartRewriteTool
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Base class for LLM-powered use cases.
 * Encapsulates tool execution and JSON decoding. Subclasses define the [invoke] signature.
 *
 * @param I Input type (tool argument)
 * @param O Output type (decoded from tool's JSON response)
 */
abstract class LlmUseCase<I, O>(protected val tool: SimpleTool<I>, protected val outputSerializer: KSerializer<O>) {

    protected suspend fun execute(input: I): Result<O> = runCatching {
        val json = tool.execute(input)
        Json.decodeFromString(outputSerializer, json)
    }
}

// ─── Concrete use cases ─────────────────────────────────────────────────────────

class RefineTaskUseCase(tool: RefineTaskTool) : LlmUseCase<RefineTaskInput, RefineTaskOutput>(
    tool, RefineTaskOutput.serializer()
) {
    suspend operator fun invoke(currentTitle: String, description: String? = null): Result<String> =
        execute(RefineTaskInput(currentTitle, description)).map { it.newTitle }
}

class SmartRewriteUseCase(tool: SmartRewriteTool) : LlmUseCase<SmartRewriteInput, SmartRewriteOutput>(
    tool, SmartRewriteOutput.serializer()
) {
    suspend operator fun invoke(rawIdea: String): Result<String> =
        execute(SmartRewriteInput(rawIdea)).map { it.newTitle }
}

class GenerateDescriptionUseCase(tool: GenerateDescriptionTool) : LlmUseCase<GenerateDescriptionInput, GenerateDescriptionOutput>(
    tool, GenerateDescriptionOutput.serializer()
) {
    suspend operator fun invoke(title: String): Result<String> =
        execute(GenerateDescriptionInput(title)).map { it.description }
}

class GenerateChecklistUseCase(tool: GenerateChecklistTool) : LlmUseCase<GenerateChecklistInput, GenerateChecklistOutput>(
    tool, GenerateChecklistOutput.serializer()
) {
    suspend operator fun invoke(title: String, description: String? = null): Result<List<String>> =
        execute(GenerateChecklistInput(title, description)).map { it.steps }
}

class PickTimeUseCase(tool: PickTimeTool) : LlmUseCase<PickTimeInput, PickTimeOutput>(
    tool, PickTimeOutput.serializer()
) {
    suspend operator fun invoke(title: String, description: String? = null): Result<String> =
        execute(PickTimeInput(title, description)).map { it.suggestedTime }
}

class ClusterTasksUseCase(tool: ClusterTasksTool) : LlmUseCase<ClusterTasksInput, ClusterTasksOutput>(
    tool, ClusterTasksOutput.serializer()
) {
    suspend operator fun invoke(tasks: List<String>): Result<Map<String, List<String>>> =
        execute(ClusterTasksInput(tasks)).map { it.clusters }
}

class ClusterNotesUseCase(tool: ClusterNotesTool) : LlmUseCase<ClusterNotesInput, ClusterNotesOutput>(
    tool, ClusterNotesOutput.serializer()
) {
    suspend operator fun invoke(notes: List<String>): Result<Map<String, List<String>>> =
        execute(ClusterNotesInput(notes)).map { it.clusters }
}

class DecomposeTaskUseCase(tool: DecomposeTaskTool) : LlmUseCase<DecomposeTaskInput, DecomposeTaskOutput>(
    tool, DecomposeTaskOutput.serializer()
) {
    suspend operator fun invoke(title: String, description: String? = null): Result<List<String>> =
        execute(DecomposeTaskInput(title, description)).map { it.subTasks }
}

class ImproveNoteUseCase(tool: ai.koog.agents.core.tools.SimpleTool<com.singularity.todo.feature.ai.tools.ImproveNoteInput>) : LlmUseCase<com.singularity.todo.feature.ai.tools.ImproveNoteInput, com.singularity.todo.feature.ai.tools.ImproveNoteOutput>(
    tool, com.singularity.todo.feature.ai.tools.ImproveNoteOutput.serializer()
) {
    suspend operator fun invoke(title: String, body: String): Result<com.singularity.todo.feature.ai.tools.ImproveNoteOutput> =
        execute(com.singularity.todo.feature.ai.tools.ImproveNoteInput(title, body))
}

/** ProjectReviewTool returns raw text — special case, not using the generic factory */
class ProjectReviewUseCase(private val tool: com.singularity.todo.feature.ai.tools.ProjectReviewTool) {
    suspend operator fun invoke(projectName: String, taskTitles: List<String>): Result<String> = runCatching {
        tool.execute(com.singularity.todo.feature.ai.tools.ProjectReviewInput(projectName, taskTitles))
    }
}
