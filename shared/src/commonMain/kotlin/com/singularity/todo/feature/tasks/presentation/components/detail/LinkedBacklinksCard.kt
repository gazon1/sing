package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.tasks.domain.model.Task
import com.singularity.todo.feature.tasks.presentation.theme.TaskColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing

/**
 * Shows notes and tasks that link TO this task via `task://<id>` URL scheme.
 * Rendered inside [TaskEditorContent] extraSections when backlinks exist.
 */
@Composable
fun LinkedBacklinksCard(
    linkedNotes: List<Note>,
    linkedTasks: List<Task>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (linkedNotes.isNotEmpty()) {
            BacklinkSection(
                label = "Linked notes",
                items = linkedNotes.map { it.title },
                onClick = { /* TODO: navigate to note */ },
            )
        }
        if (linkedTasks.isNotEmpty()) {
            BacklinkSection(
                label = "Linked tasks",
                items = linkedTasks.map { it.title },
                onClick = { /* TODO: navigate to task */ },
            )
        }
    }
}

@Composable
private fun BacklinkSection(
    label: String,
    items: List<String>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = TaskColors.Surface,
        shape = RoundedCornerShape(TaskSpacing.cardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier
                .padding(
                    horizontal = TaskSpacing.cardPaddingHorizontal,
                    vertical = TaskSpacing.cardPaddingVertical,
                )
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Link,
                contentDescription = null,
                tint = TaskColors.TextSecondary,
                modifier = Modifier.size(TaskSpacing.iconSize),
            )
            Spacer(Modifier.width(TaskSpacing.lg))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    color = TaskColors.TextSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
                items.forEach { title ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(vertical = 2.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.TextSnippet,
                            contentDescription = null,
                            tint = TaskColors.AccentBlue,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = title.ifBlank { "(untitled)" },
                            color = TaskColors.TextPrimary,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
