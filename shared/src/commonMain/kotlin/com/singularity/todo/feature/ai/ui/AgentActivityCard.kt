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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.llm.AgentPlanStep
import com.singularity.todo.core.llm.PlanStepStatus
import com.singularity.todo.core.llm.ToolCallStatus
import com.singularity.todo.core.ui.TestTags
import kotlinx.collections.immutable.ImmutableList

/**
 * One card per tool call the agent made.
 *
 * ## Why the card is shared
 *
 * The built-in chat and the external-agent screen show the same thing: an agent working.
 * Two presentations of it would be two things to keep right and a difference for the reader
 * to learn for no benefit. So there is one card, and every surface that shows an agent's
 * activity uses it.
 *
 * ## What it shows, and what it does not
 *
 * The tool's name and where the call got to — running, done, or failed. A failed call is
 * shown as failed rather than omitted: a card that vanishes leaves the reader guessing
 * whether the agent did the thing, and the agent's own prose is not a reliable witness for
 * its own tools.
 *
 * The summary is a short, sanitized description of what the call touched — identifiers,
 * titles, field names. It is **hidden until the reader asks for it**, because a card that
 * shows a whole note body puts the user's content on a screen that was about to be shared,
 * photographed or screenshotted. The reader needs to know *what* was touched, not *the
 * whole thing*.
 */
@Composable
fun AgentToolCallCard(
    name: String,
    status: ToolCallStatus,
    summary: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.AGENT_TOOL_CALL_CARD),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = humanToolName(name),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = status.label(),
                    style = MaterialTheme.typography.labelSmall,
                    color = status.color(),
                    modifier = Modifier.testTag(TestTags.AGENT_TOOL_CALL_STATUS),
                )
            }
            if (!summary.isNullOrBlank()) {
                ToolCallSummary(summary = summary)
            }
        }
    }
}

/**
 * The summary, revealed on request.
 *
 * The expansion state lives here rather than in the caller's state: whether the reader has
 * opened a card is a property of the card on this screen, and lifting it into the ViewModel
 * would put a per-widget detail into the turn's state.
 */
@Composable
private fun ToolCallSummary(summary: String) {
    var expanded by remember { mutableStateOf(false) }

    if (expanded) {
        Text(
            text = summary,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .testTag(TestTags.AGENT_TOOL_CALL_SUMMARY),
        )
    }

    TextButton(
        onClick = { expanded = !expanded },
        modifier = Modifier
            .padding(top = 2.dp)
            .testTag(TestTags.AGENT_TOOL_CALL_SUMMARY_TOGGLE),
    ) {
        Text(if (expanded) "Hide details" else "Details")
    }
}

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

/** The word a reader scans for. Short, because the reader is scanning. */
private fun ToolCallStatus.label(): String = when (this) {
    ToolCallStatus.RUNNING -> "running"
    ToolCallStatus.COMPLETED -> "done"
    ToolCallStatus.FAILED -> "failed"
}

@Composable
private fun ToolCallStatus.color() = when (this) {
    ToolCallStatus.RUNNING -> MaterialTheme.colorScheme.onSurfaceVariant
    ToolCallStatus.COMPLETED -> MaterialTheme.colorScheme.primary
    ToolCallStatus.FAILED -> MaterialTheme.colorScheme.error
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
