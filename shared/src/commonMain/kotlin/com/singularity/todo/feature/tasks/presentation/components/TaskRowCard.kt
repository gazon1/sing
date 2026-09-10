package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.presentation.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.model.TaskUi
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListShapes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing

/**
 * Карточный стиль строки — с собственной поверхностью, скруглением и лёгкой
 * тенью, которая усиливается при press/hover. Более "воздушный" вариант,
 * лучше подходит для коротких списков, где карточки читаются как отдельные
 * объекты, а не строки таблицы.
 *
 * Hover/press — это композиция из elevation + лёгкого tint акцентом,
 * а не ripple: на тёмном фоне ripple-индикация читается плохо, а лёгкое
 * "приподнимание" карточки с заметным accent-glow — заметно и приятно.
 */
@Composable
fun TaskRowCard(
    task: TaskUi,
    onToggleCompleted: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isPressed by interactionSource.collectIsPressedAsState()

    val elevation by animateDpAsState(
        targetValue = when {
            isPressed -> TaskListSizes.CardElevation * 2
            isHovered -> TaskListSizes.CardElevationHover
            else -> TaskListSizes.CardElevation
        },
        animationSpec = tween(150),
        label = "cardElevation",
    )

    val focusIntensity by animateFloatAsState(
        targetValue = when {
            isPressed -> 1f
            isHovered -> 0.5f
            else -> 0f
        },
        animationSpec = tween(150),
        label = "cardFocus",
    )

    val shadowColor = TaskListColors.Accent.copy(alpha = focusIntensity * 0.25f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(
                elevation = elevation,
                shape = TaskListShapes.CardRadius,
                clip = false,
                ambientColor = shadowColor,
                spotColor = shadowColor,
            )
            .clip(TaskListShapes.CardRadius)
            .background(TaskListColors.Surface)
            .border(
                width = 1.dp,
                color = if (isHovered) TaskListColors.Accent.copy(alpha = 0.25f) else TaskListColors.Divider,
                shape = TaskListShapes.CardRadius,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            ),
    ) {
        TaskRowContent(
            task = task,
            onToggleCompleted = onToggleCompleted,
            modifier = Modifier.padding(TaskListSpacing.Lg),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0E14, widthDp = 360)
@Composable
private fun TaskRowCardPreview() {
    MaterialTheme {
        Column(
            verticalArrangement = Arrangement.spacedBy(TaskListSpacing.Md),
            modifier = Modifier.padding(TaskListSpacing.Lg),
        ) {
            TaskRowCard(
                task = TaskUi(
                    id = 1,
                    title = "KotlinConf 2026",
                    project = "Конференции",
                    dueLabel = "09:41",
                    priority = TaskPriority.LOW,
                ),
                onToggleCompleted = {},
                onClick = {},
            )
            TaskRowCard(
                task = TaskUi(
                    id = 2,
                    title = "Помыть туалет и пол там",
                    project = "Квартира",
                    dueLabel = "22 июль",
                    isCompleted = true,
                ),
                onToggleCompleted = {},
                onClick = {},
            )
            TaskRowCard(
                task = TaskUi(
                    id = 3,
                    title = "Просроченный счёт за интернет",
                    project = "Финансы",
                    dueLabel = "Вчера",
                    priority = TaskPriority.HIGH,
                    isOverdue = true,
                ),
                onToggleCompleted = {},
                onClick = {},
            )
        }
    }
}
