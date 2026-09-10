package com.singularity.todo.feature.tasks.presentation.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens for the Task Creation screen.
 *
 * Держим палитру и spacing в одном месте, чтобы не плодить магические
 * значения по компонентам и не ловить рассинхрон между экранами.
 */
object TaskColors {
    val Background = Color(0xFF0F1115)
    val Surface = Color(0xFF161A22)
    val SurfaceElevated = Color(0xFF1C212B) // чуть светлее — для сгруппированных карточек
    val Outline = Color(0xFF262C38)

    val TextPrimary = Color(0xFFE2E4E9)
    val TextSecondary = Color(0xFF8B94A6)
    val TextPlaceholder = Color(0xFF5A6376)

    val AccentBlue = Color(0xFF4A90E2)
    val AccentBlueContainer = Color(0xFF1B2B44) // фон для активных чипов ("Сегодня")

    // Семантика приоритета — раньше весь текст был одного серого цвета,
    // из-за чего "приоритет" не читался как приоритет
    val PriorityLow = Color(0xFF6FCF97)
    val PriorityMedium = Color(0xFFF2C94C)
    val PriorityHigh = Color(0xFFEB5757)
}

object TaskSpacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp

    val screenPadding = 16.dp
    val cardCornerRadius = 14.dp
    val cardPaddingHorizontal = 16.dp
    val cardPaddingVertical = 14.dp
    val iconSize = 22.dp
    val iconSizeLarge = 26.dp
}
