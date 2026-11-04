package com.singularity.todo.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * The app's typography.
 *
 * Every style sets an explicit [TextStyle.lineHeight], taken from the Material 3 baseline for
 * that slot and multiplied by the same [scale] that multiplies the font size. Without it the
 * line spacing comes from the font's own metrics, so it does not follow the app's font-size
 * setting and drifts from the design system's rhythm.
 */
fun createTypography(scale: Float = 1f): Typography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = (57 * scale).sp,
                lineHeight = (64 * scale).sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = (45 * scale).sp,
                lineHeight = (56 * scale).sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = (36 * scale).sp,
                lineHeight = (48 * scale).sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = (32 * scale).sp,
                lineHeight = (40 * scale).sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = (28 * scale).sp,
                lineHeight = (36 * scale).sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = (24 * scale).sp,
                lineHeight = (32 * scale).sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = (22 * scale).sp,
                lineHeight = (28 * scale).sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = (16 * scale).sp,
                lineHeight = (24 * scale).sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = (14 * scale).sp,
                lineHeight = (20 * scale).sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = (16 * scale).sp,
                lineHeight = (24 * scale).sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = (14 * scale).sp,
                lineHeight = (20 * scale).sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = (12 * scale).sp,
                lineHeight = (16 * scale).sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = (14 * scale).sp,
                lineHeight = (20 * scale).sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = (12 * scale).sp,
                lineHeight = (16 * scale).sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Medium,
                fontSize = (11 * scale).sp,
                lineHeight = (14 * scale).sp,
            ),
    )
