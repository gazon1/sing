package com.singularity.todo.feature.tasks.presentation.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.tasks.presentation.model.TaskPriority
import com.singularity.todo.feature.tasks.presentation.theme.TaskListColors
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSizes
import com.singularity.todo.feature.tasks.presentation.theme.TaskListSpacing

/**
 * Кастомный чекбокс задачи.
 *
 * В отличие от [androidx.compose.material3.Checkbox] даёт полный контроль над
 * анимацией заполнения — это узнаваемый паттерн Todoist/Things: обводка кружка
 * заполняется цветом приоритета, а не всегда одним акцентным цветом.
 *
 * Поведение:
 *  - **Одноразовый "поп"** при каждом переключении (а не на первом композе —
 *    строки, которые просто появляются на экране, не должны подпрыгивать).
 *  - **Лёгкое сжатие при нажатии** — визуальный feedback на touch, не полагаемся
 *    на системный ripple, потому что на тёмном фоне он плохо виден.
 *  - **Цвет заливки = цвет приоритета** — единая семантика по всей строке.
 *
 * @param accentColor цвет заполнения — обычно цвет приоритета задачи
 */
@Composable
fun TaskCheckbox(
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = TaskListColors.Accent,
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    // Transient "поп" — анимируется к scale=0.85 на клик и пружинит обратно в 1f.
    // remember(isChecked) как "предыдущее значение" — пропускаем самый первый композ.
    val popScale = remember { Animatable(1f) }
    var isFirstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(isChecked) {
        if (isFirstComposition) {
            isFirstComposition = false
            return@LaunchedEffect
        }
        popScale.snapTo(0.85f)
        popScale.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioMediumBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    // Сжатие под пальцем — отдельный, непрерывный канал, не зависит от поп-импульса.
    val pressScale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = tween(80),
        label = "checkboxPress",
    )

    val fillProgress by animateFloatAsState(
        targetValue = if (isChecked) 1f else 0f,
        animationSpec = tween(220),
        label = "checkboxFill",
    )

    Box(
        modifier = modifier
            .size(TaskListSizes.Checkbox)
            .scale(popScale.value * pressScale)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Checkbox,
            ) { onCheckedChange(!isChecked) }
            .semantics {
                role = Role.Checkbox
                stateDescription = if (isChecked) "Выполнено" else "Не выполнено"
            },
        contentAlignment = Alignment.Center,
    ) {
        // Обводка — всегда видна, интерполируем цвет от серой к акцентной
        Box(
            modifier = Modifier
                .size(TaskListSizes.Checkbox)
                .border(
                    width = 1.8.dp,
                    color = lerpColor(TaskListColors.TextTertiary, accentColor, fillProgress),
                    shape = CircleShape,
                ),
        )

        // Заливка — растёт от центра, создавая эффект "заполнения"
        Box(
            modifier = Modifier
                .size(TaskListSizes.Checkbox)
                .scale(fillProgress)
                .background(accentColor, CircleShape),
        )

        AnimatedVisibility(
            visible = isChecked,
            enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(),
            exit = scaleOut(tween(120)) + fadeOut(tween(80)),
        ) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null, // дублируется в stateDescription
                tint = TaskListColors.OnAccent,
                modifier = Modifier.size(TaskListSizes.CheckIcon),
            )
        }
    }
}

/** Линейная интерполяция цвета — Compose не имеет её в public API до 1.7. */
private fun lerpColor(start: Color, end: Color, fraction: Float): Color = Color(
    red = start.red + (end.red - start.red) * fraction,
    green = start.green + (end.green - start.green) * fraction,
    blue = start.blue + (end.blue - start.blue) * fraction,
    alpha = start.alpha + (end.alpha - start.alpha) * fraction,
)

@Preview(showBackground = true, backgroundColor = 0xFF0B0E14)
@Composable
private fun TaskCheckboxPreview() {
    MaterialTheme {
        Row(
            horizontalArrangement = Arrangement.spacedBy(TaskListSpacing.Lg),
            modifier = Modifier.padding(TaskListSpacing.Lg).size(280.dp, 60.dp),
        ) {
            TaskCheckbox(isChecked = false, onCheckedChange = {})
            TaskCheckbox(isChecked = true, onCheckedChange = {})
            TaskCheckbox(
                isChecked = false,
                onCheckedChange = {},
                accentColor = TaskListColors.PriorityHigh,
            )
            TaskCheckbox(
                isChecked = true,
                onCheckedChange = {},
                accentColor = TaskListColors.PriorityMedium,
            )
            TaskCheckbox(
                isChecked = false,
                onCheckedChange = {},
                accentColor = priorityColor(TaskPriority.LOW),
            )
        }
    }
}
