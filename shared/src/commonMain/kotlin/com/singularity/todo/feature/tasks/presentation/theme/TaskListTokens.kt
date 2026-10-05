package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * Design tokens экрана списка задач — геометрия, spacing, размеры.
 *
 * Шкалы (spacing, sizes) идут с шагом 4.dp — стандарт Material 8-pt grid,
 * кратный 2х, чтобы значения не "плавали" на разных плотностях экрана.
 *
 * Раньше здесь же был объект `TaskListColors` — пятнадцать фиксированных
 * значений в тёмной палитре. Он не мог реагировать на тему, поэтому
 * пользователь в светлом режиме (дефолт приложения) видел тёмный список
 * задач, а выбор акцента на этом экране не работал вовсе: акцент был
 * записан литералом. Цвета теперь берутся из темы — прямые роли из
 * `MaterialTheme.colorScheme` (см. [TaskDerivedColors]), а те, что не должны
 * зависеть от темы, — из [TaskSemanticColors].
 */
object TaskListShapes {
    val CardRadius = RoundedCornerShape(16.dp)
    val ChipRadius = RoundedCornerShape(12.dp)
    val FabRadius = RoundedCornerShape(16.dp)
    val IconContainerRadius = RoundedCornerShape(14.dp)
    val CheckboxSize = 22.dp
}

/** Отступы в UI — одна шкала, чтобы визуальный ритм не "плавал" между компонентами. */
object TaskListSpacing {
    val None = 0.dp
    val Xxs = 2.dp
    val Xs = 4.dp
    val Sm = 8.dp
    val Md = 12.dp
    val Lg = 16.dp
    val Xl = 20.dp
    val Xxl = 24.dp
}

/** Семантические размеры (не "отступы", а "размер виджета"). */
object TaskListSizes {
    val Checkbox = 22.dp
    val CheckIcon = 13.dp
    val PriorityStar = 16.dp
    val MetaIcon = 13.dp
    val DividerThickness = 1.dp
    val CardElevation = 1.dp
    val CardElevationHover = 4.dp
    val MinTouchTarget = 44.dp
}
