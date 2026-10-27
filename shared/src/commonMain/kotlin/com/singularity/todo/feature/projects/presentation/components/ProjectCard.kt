package com.singularity.todo.feature.projects.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.TestTags
import com.singularity.todo.core.ui.components.AiActionButton
import com.singularity.todo.core.ui.components.DeleteActionButton
import com.singularity.todo.core.ui.components.ReopenActionButton
import com.singularity.todo.feature.projects.domain.model.Project

/**
 * Visual representation of a [Project]. Stateless — every interaction is
 * forwarded through [onClick] (whole-row tap) and [actions] (per-button).
 */
@Composable
fun ProjectCard(
    project: Project,
    totalCount: Int = 0,
    completedCount: Int = 0,
    onClick: () -> Unit,
    actions: ProjectCardActions = ProjectCardActions.Empty,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag(TestTags.projectCard(project.name))
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Color circle with icon from registry
            ColorCircle(
                color = Color(project.color),
                iconKey = project.icon,
                contentDescription = project.name,
            )
            Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
                Text(text = project.name, style = MaterialTheme.typography.titleMedium)
                if (totalCount > 0) {
                    Text(
                        text = "$completedCount/$totalCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    project.description?.let { desc ->
                        Text(
                            text = desc.take(50),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (project.closedAt != null) {
                // A closed project is finished: the review and the delete are
                // not what the user came for. The way back is.
                ReopenActionButton(onClick = actions::onReopen)
            } else {
                AiActionButton(onClick = actions::onReviewClick)
                DeleteActionButton(onClick = actions::onDelete)
            }
        }
    }
}

@Composable
private fun ColorCircle(color: Color, iconKey: String?, contentDescription: String? = null) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color),
        contentAlignment = Alignment.Center,
    ) {
        ProjectIcon(
            icon = iconKey,
            tint = Color.White,
            size = 20.dp,
            contentDescription = contentDescription,
        )
    }
}
