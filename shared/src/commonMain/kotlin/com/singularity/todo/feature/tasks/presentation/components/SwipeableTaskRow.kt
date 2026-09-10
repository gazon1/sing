package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing

/**
 * Универсальная обёртка со свайпом-для-удаления.
 *
 * Не знает, что именно рисуется внутри — принимает [content] слотом, поэтому
 * одинаково оборачивает и [TaskRowFlat], и [TaskRowCard]. Свайп только влево
 * (EndToStart).
 *
 * UX-детали:
 *  - **Хаптик-фидбек** в момент прохождения порога срабатывания (targetValue != Settled)
 *  - **Иконка растёт** при приближении к порогу, чтобы дать визуальный сигнал
 *    "ещё чуть-чуть и удалится"
 *  - **[backgroundShape]** задаёт форму фона — прямая для Flat-стиля,
 *    скруглённая для Card-стиля
 *
 * @param backgroundShape форма фона удаления — RoundedCornerShape(0.dp) для Flat, [TaskListShapes.CardRadius] для Card
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeableTaskRow(
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    backgroundShape: RoundedCornerShape = RoundedCornerShape(0.dp),
    content: @Composable () -> Unit,
) {
    val dismissState = rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.4f },
        // без confirmValueChange
    )

    LaunchedEffect(dismissState.currentValue) {
        // (2) side effect: onDelete когда стейт осёл на EndToStart
        if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart) onDelete()
    }

    // 0..1 — степень "свайпнутости" относительно порога
    val swipeProgress by remember(dismissState) {
        derivedStateOf {
            val target = dismissState.targetValue
            val current = dismissState.currentValue
            when {
                target == SwipeToDismissBoxValue.Settled -> 0f
                current == SwipeToDismissBoxValue.EndToStart -> 1f
                else -> dismissState.progress
            }
        }
    }

    // Иконка масштабируется по мере свайпа
    val iconScale by animateFloatAsState(
        targetValue = 0.85f + (swipeProgress.coerceIn(0f, 1f) * 0.4f),
        animationSpec = spring(),
        label = "swipeIconScale",
    )

    val willTrigger by remember {
        derivedStateOf { dismissState.targetValue != SwipeToDismissBoxValue.Settled }
    }

    val haptic = LocalHapticFeedback.current
    LaunchedEffect(willTrigger) {
        if (willTrigger) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    SwipeToDismissBox(
        state = dismissState,
        modifier = modifier,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(backgroundShape)
                    .background(TaskListColors.Danger)
                    .padding(horizontal = TaskListSpacing.Xxl),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить задачу",
                    tint = Color.White,
                    modifier = Modifier
                        .size(TaskListSizes.MinTouchTarget - TaskListSpacing.Md) // 32dp
                        .scale(iconScale),
                )
            }
        },
        content = {
            // Контент обязан иметь непрозрачный фон — иначе backgroundContent
            // (красная подложка удаления) просвечивает сквозь него постоянно,
            // а не только во время свайпа. TaskRowCard красит фон сам (Surface),
            // а TaskRowFlat — прозрачный по дизайну, поэтому фон экрана
            // подкладывается здесь, единожды, для любого стиля строки.
            Box(modifier = Modifier.fillMaxSize().background(TaskListColors.Background)) {
                content()
            }
        }

    )
}
