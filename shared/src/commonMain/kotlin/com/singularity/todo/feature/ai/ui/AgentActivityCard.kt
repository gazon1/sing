package com.singularity.todo.feature.ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.llm.AgentPlanStep
import com.singularity.todo.core.llm.PlanStepStatus
import com.singularity.todo.core.ui.TestTags
import kotlinx.collections.immutable.ImmutableList

/**
 * The agent's plan, as a list of steps.
 *
 * A snapshot rather than a delta: the reader wants the plan as it is now, not a patch to
 * apply to a plan they may no longer be looking at.
 */
@Composable
fun AgentPlanCard(
    steps: ImmutableList<AgentPlanStep>,
    modifier: Modifier = Modifier,
) {
    if (steps.isEmpty()) return

    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.AGENT_PLAN_CARD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = "Plan",
                style = MaterialTheme.typography.labelMedium,
            )
            steps.forEach { step ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = step.status.marker(),
                        style = MaterialTheme.typography.bodySmall,
                        color = step.status.color(),
                    )
                    Text(
                        text = step.title,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun PlanStepStatus.marker(): String = when (this) {
    PlanStepStatus.PENDING -> "○"
    PlanStepStatus.IN_PROGRESS -> "◐"
    PlanStepStatus.COMPLETED -> "●"
}

@Composable
private fun PlanStepStatus.color() = when (this) {
    PlanStepStatus.PENDING -> MaterialTheme.colorScheme.onSurfaceVariant
    PlanStepStatus.IN_PROGRESS -> MaterialTheme.colorScheme.primary
    PlanStepStatus.COMPLETED -> MaterialTheme.colorScheme.primary
}

/** Maps a raw tool name (snake_case) to a human-readable label. */
fun humanToolName(name: String): String {
    val parts = name.split('_')
    val verb = parts.firstOrNull()?.lowercase().orEmpty()
    val noun = parts.drop(1).joinToString(" ")
    val ing = when (verb) {
        "create" -> "Creating"
        "update" -> "Updating"
        "delete" -> "Deleting"
        "list" -> "Listing"
        "get" -> "Getting"
        "search" -> "Searching"
        "refine" -> "Refining"
        "generate" -> "Generating"
        "decompose" -> "Decomposing"
        "cluster" -> "Clustering"
        "suggest" -> "Suggesting"
        "improve" -> "Improving"
        "summarize" -> "Summarizing"
        "extract" -> "Extracting"
        "rewrite" -> "Rewriting"
        "pick" -> "Picking"
        "write" -> "Writing"
        else -> verb.replaceFirstChar { it.uppercase() }
    }
    return if (noun.isBlank()) ing else "$ing $noun"
}
