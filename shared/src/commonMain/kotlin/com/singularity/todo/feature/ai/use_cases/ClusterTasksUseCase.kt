package com.singularity.todo.feature.ai.use_cases

import com.singularity.todo.feature.ai.tools.ClusterTasksInput
import com.singularity.todo.feature.ai.tools.ClusterTasksOutput
import com.singularity.todo.feature.ai.tools.ClusterTasksTool
import kotlinx.serialization.json.Json

/**
 * Use case: group task titles into 2-5 thematic clusters.
 */
class ClusterTasksUseCase(private val tool: ClusterTasksTool) {

    suspend operator fun invoke(tasks: List<String>): Result<Map<String, List<String>>> = runCatching {
        val json = tool.execute(ClusterTasksInput(tasks))
        Json.decodeFromString<ClusterTasksOutput>(json).clusters
    }
}
