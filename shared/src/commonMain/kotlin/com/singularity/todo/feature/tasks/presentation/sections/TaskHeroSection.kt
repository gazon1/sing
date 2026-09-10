package com.singularity.todo.feature.tasks.presentation.sections

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.domain.model.TaskKind
import com.singularity.todo.feature.tasks.presentation.components.TaskDetailActions

/**
 * The top "hero" area of a task detail screen: a large checkbox, inline-editable
 * title, inline-editable description, and kind/someday indicator buttons.
 *
 * @param title        Current task title (may be empty — placeholder is shown).
 * @param description  Current description, or `null` when blank.
 * @param isCompleted  Completion state — drives checkbox and strikethrough.
 * @param kind         Current [TaskKind] — determines the kind icon shown.
 * @param isSomeday   Whether the task is marked as someday/maybe.
 * @param actions      Packed [TaskDetailActions] callback handler.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskHeroSection(
    title: String,
    description: String?,
    isCompleted: Boolean,
    kind: TaskKind,
    isSomeday: Boolean,
    actions: TaskDetailActions,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(
            checked = isCompleted,
            onCheckedChange = { actions.onToggleComplete() },
            modifier = Modifier.padding(top = 2.dp),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            InlineEditableText(
                value = title,
                placeholder = "Task title",
                style = MaterialTheme.typography.titleLarge,
                textDecoration = if (isCompleted) TextDecoration.LineThrough else null,
                onValueChange = actions::onTitleChange,
            )
            InlineEditableText(
                value = description ?: "",
                placeholder = "Add description...",
                style = MaterialTheme.typography.bodyMedium.copy(
                    color = if (description.isNullOrBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                ),
                textDecoration = null,
                onValueChange = actions::onDescriptionChange,
            )

            // Kind and someday indicator row
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 4.dp),
            ) {
                // Kind chip
                IconButton(
                    onClick = { actions.onOpenKindPicker() },
                    modifier = Modifier.size(32.dp),
                ) {
                    Icon(
                        imageVector = if (kind == TaskKind.Note) Icons.Filled.Lightbulb else Icons.Filled.CheckBox,
                        contentDescription = "Kind: ${kind.name}",
                        tint = if (kind == TaskKind.Note) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = Modifier.size(18.dp),
                    )
                }

                // Someday chip
                if (isSomeday) {
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { actions.onToggleSomeday() },
                        modifier = Modifier.size(32.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Filled.WatchLater,
                            contentDescription = "Someday/Maybe — tap to activate",
                            tint = MaterialTheme.colorScheme.secondary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * A single-line text field that shows a styled placeholder when empty and
 * switches seamlessly to an editable state — used for title and description.
 */
@Composable
private fun InlineEditableText(
    value: String,
    placeholder: String,
    style: TextStyle,
    textDecoration: TextDecoration?,
    onValueChange: (String) -> Unit,
) {
    Box {
        if (value.isEmpty()) {
            Text(
                text = placeholder,
                style = style.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
            )
        }
        androidx.compose.foundation.text.BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = style.copy(textDecoration = textDecoration),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
