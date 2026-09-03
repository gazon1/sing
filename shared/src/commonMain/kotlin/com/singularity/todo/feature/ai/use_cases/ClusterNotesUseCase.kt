package com.singularity.todo.feature.ai.use_cases

import com.singularity.todo.feature.ai.tools.ClusterNotesInput
import com.singularity.todo.feature.ai.tools.ClusterNotesOutput
import com.singularity.todo.feature.ai.tools.ClusterNotesTool
import kotlinx.serialization.json.Json

/**
 * Use case: group note titles into 2-5 thematic clusters.
 */
class ClusterNotesUseCase(private val tool: ClusterNotesTool) {

    suspend operator fun invoke(notes: List<String>): Result<Map<String, List<String>>> = runCatching {
        val json = tool.execute(ClusterNotesInput(notes))
        Json.decodeFromString<ClusterNotesOutput>(json).clusters
    }
}
