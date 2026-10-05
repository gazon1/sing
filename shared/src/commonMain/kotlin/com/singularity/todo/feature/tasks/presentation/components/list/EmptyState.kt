package com.singularity.todo.feature.tasks.presentation.components.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.singularity.todo.core.ui.preview.PreviewThemed
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing
import com.singularity.todo.feature.tasks.presentation.theme.mutedTextColor
import com.singularity.todo.feature.tasks.presentation.theme.elevatedSurfaceColor

/**
 * Заглушка для пустого списка.
 *
 * Два варианта:
 *  - [EmptyState] — общий случай (большой иконка-контейнер + заголовок + подсказка)
 *  - [EmptyStateCompact] — для вторичных секций (маленький inline)
 *
 * UX-принципы:
 *  - Иконка внутри цветного "тарелочного" контейнера — стандартный приём,
 *    чтобы пустота не выглядела как "тут что-то сломано"
 *  - Подсказка конкретная ("Добавьте первую задачу, нажав +") — не общая
 *    "Ничего нет"
 */
@Composable
fun EmptyState(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Inbox,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(TaskListSpacing.Md),
            modifier = Modifier.padding(TaskListSpacing.Xxl),
        ) {
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(elevatedSurfaceColor()),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
            Text(
                text = title,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 18.sp,
                lineHeight = 24.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = description,
                color = mutedTextColor(),
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun EmptyStateCompact(text: String, modifier: Modifier = Modifier, icon: ImageVector = Icons.Filled.Check) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(TaskListSpacing.Sm),
        modifier = modifier.padding(TaskListSpacing.Lg),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = mutedTextColor(),
            modifier = Modifier.size(TaskListSizes.PriorityStar),
        )
        Text(
            text = text,
            color = mutedTextColor(),
            fontSize = 14.sp,
        )
    }
}

@Preview(showBackground = true)
@Composable
private fun EmptyStatePreview() {
    PreviewThemed(darkTheme = true, useSurface = true) {
        EmptyState(
            title = "Задач пока нет",
            description = "Добавьте первую задачу, нажав на синюю кнопку снизу",
        )
    }
}
