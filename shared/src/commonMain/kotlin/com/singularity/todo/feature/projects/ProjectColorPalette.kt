package com.singularity.todo.feature.projects

/**
 * Predefined 12-color palette for projects.
 * Stored as ARGB Int (Color.toArgb()).
 *
 * Matches the color picker in TickTick's project editor.
 * No custom hex in v1 — keeps the color picker simple.
 */
object ProjectColorPalette {

    /** All 12 palette colors as ARGB Int. */
    val all: List<Int> = listOf(
        0xFF2196F3.toInt(), // Blue
        0xFFF44336.toInt(), // Red
        0xFF4CAF50.toInt(), // Green
        0xFFFF9800.toInt(), // Orange
        0xFF9C27B0.toInt(), // Purple
        0xFF00BCD4.toInt(), // Cyan
        0xFFFFEB3B.toInt(), // Yellow
        0xFF795548.toInt(), // Brown
        0xFF607D8B.toInt(), // Blue Grey
        0xFFE91E63.toInt(), // Pink
        0xFF3F51B5.toInt(), // Indigo
        0xFF009688.toInt(), // Teal
    )

    /** Default color (Blue) — used when creating a new project. */
    val default: Int = all.first()

    /** Maps an ARGB Int to a human-readable name. */
    fun nameOf(argb: Int): String = when (argb) {
        0xFF2196F3.toInt() -> "Blue"
        0xFFF44336.toInt() -> "Red"
        0xFF4CAF50.toInt() -> "Green"
        0xFFFF9800.toInt() -> "Orange"
        0xFF9C27B0.toInt() -> "Purple"
        0xFF00BCD4.toInt() -> "Cyan"
        0xFFFFEB3B.toInt() -> "Yellow"
        0xFF795548.toInt() -> "Brown"
        0xFF607D8B.toInt() -> "Blue Grey"
        0xFFE91E63.toInt() -> "Pink"
        0xFF3F51B5.toInt() -> "Indigo"
        0xFF009688.toInt() -> "Teal"
        else -> "Custom"
    }
}
