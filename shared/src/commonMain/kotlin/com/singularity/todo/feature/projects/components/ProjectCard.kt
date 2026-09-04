package com.singularity.todo.feature.projects.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.singularity.todo.core.ui.components.AiActionButton
import com.singularity.todo.core.ui.components.DeleteActionButton
import com.singularity.todo.feature.projects.Project

/**
 * Visual representation of a [Project]. Stateless — every interaction is
 * forwarded through [onClick] (whole-row tap) and [actions] (per-button).
 */
@Composable
fun ProjectCard(
    project: Project,
    onClick: () -> Unit,
    actions: ProjectCardActions = ProjectCardActions.Empty,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ColorCircle(color = Color(project.color))
            ProjectText(project = project, modifier = Modifier.weight(1f))
            AiActionButton(onClick = actions::onReviewClick)
            DeleteActionButton(onClick = actions::onDelete)
        }
    }
}

@Composable
private fun ColorCircle(color: Color) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(CircleShape)
            .background(color),
    ) {
        Icon(
            Icons.Filled.Home,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.padding(4.dp),
        )
    }
}

@Composable
private fun ProjectText(project: Project, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(start = 12.dp)) {
        Text(text = project.name, style = MaterialTheme.typography.titleMedium)
        project.description?.let { desc ->
            Text(
                text = desc.take(50),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
