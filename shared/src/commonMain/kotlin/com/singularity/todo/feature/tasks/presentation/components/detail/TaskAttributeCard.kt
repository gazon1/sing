package com.singularity.todo.feature.tasks.presentation.components.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.singularity.todo.feature.tasks.presentation.theme.TaskSpacing
import androidx.compose.material3.MaterialTheme

/**
 * Карточка-строка для основных атрибутов задачи (проект, приоритет, дата и т.д.).
 *
 * @param isActive true, если атрибут задан пользователем — тогда используем
 * акцентный фон/цвет вместо нейтрального, чтобы "заполненные" поля визуально
 * отличались от пустых (в исходном варианте это работало не всегда).
 */
@Composable
fun TaskAttributeCard(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isActive: Boolean = false,
    iconTint: Color =
        if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    textColor: Color =
        if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
    containerColor: Color =
        if (isActive) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
    trailingContent: (@Composable () -> Unit)? = null,
    /**
     * Tag for the **label**, not for the card.
     *
     * On Android the label is its own view, so a flow can address it — the recurrence
     * flow does, and `Maestro/TAGS.md` lists it. On desktop the label is folded into the
     * clickable card, and the merged tree a desktop test sees does not carry the label's
     * tag: a test that means "the row" must pass the tag through [modifier] instead.
     */
    testTag: String? = null,
    /**
     * When false the card is shown but cannot be pressed.
     *
     * The workflow-state row uses it for a completed task: the state is a fact about
     * how the work went, so it is still shown, but it is not a choice any more. An
     * empty `onClick` would look identical and would still be a button to a screen
     * reader; no clickable at all is the difference.
     */
    enabled: Boolean = true,
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(TaskSpacing.cardCornerRadius),
        modifier = modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
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
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(TaskSpacing.iconSize),
            )
            Spacer(Modifier.width(TaskSpacing.lg))
            Text(
                text = label,
                color = textColor,
                fontSize = 16.sp,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                modifier = Modifier
                    .weight(1f)
                    .then(if (testTag != null) Modifier.testTag(testTag) else Modifier),
            )
            if (trailingContent != null) {
                trailingContent()
            }
        }
    }
}
