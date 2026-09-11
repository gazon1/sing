package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens экрана списка задач.
 *
 * Собраны в одном месте — это даёт один источник правды при смене темы
 * и убирает магические hex-значения / dp-числа из UI-кода.
 *
 * Шкалы (spacing, sizes) идут с шагом 4.dp — стандарт Material 8-pt grid,
 * кратный 2х, чтобы значения не "плавали" на разных плотностях экрана.
 */
object TaskListColors {
    // Поверхности
    val Background = Color(0xFF0B0E14)      // чуть темнее и холоднее чем Zinc900 — меньше "серости"
    val Surface = Color(0xFF161A22)         // карточка / hover-подложка
    val SurfaceElevated = Color(0xFF1D222C) // приподнятое состояние (pressed)
    val Divider = Color(0xFF232833)

    // Текст
    val TextPrimary = Color(0xFFF2F3F5)
    val TextSecondary = Color(0xFF9096A3)
    val TextTertiary = Color(0xFF5C6270)

    // Акценты
    val Accent = Color(0xFF5B8DEF)           // основной синий, чуть мягче исходного Blue500
    val OnAccent = Color(0xFFFFFFFF)
    val Danger = Color(0xFFE5484D)
    val Success = Color(0xFF4CC38A)

    // Приоритеты (по аналогии с Todoist p1–p3)
    val PriorityHigh = Color(0xFFE5484D)
    val PriorityMedium = Color(0xFFF5A623)
    val PriorityLow = Color(0xFF5B8DEF)
    val PriorityNone = TextTertiary
    val PriorityUrgent = Color(0xFFFF6B6B)
}

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
