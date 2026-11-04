package com.singularity.todo.core.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Material 3's own type scale: the baseline for the slots the app has never adopted, and the reason
 * those slots do not need a baseline written out here.
 *
 * A value holder with no composition in it, built once rather than per call.
 */
private val materialBaseline: Typography = Typography()

/**
 * The app's typography, with the user's text scale applied to every slot it exposes.
 *
 * ## One policy for the scale
 *
 * [scale] is the appearance setting `fontSizeScale`: 1 is the Material 3 baseline, 1.25 grows every
 * size and every line height by a quarter. The multiplication happens in [scaledStyle] (the slots
 * written out below) and in [scaledBy] (the slots taken from [materialBaseline]) and nowhere else, so
 * a size and its line height cannot drift apart — a slot whose line height kept the baseline while
 * its size grew loses the design system's rhythm at every scale but 1.
 *
 * ## Two baselines, on purpose
 *
 * `Typography` exposes thirty slots: the fifteen the app renders, and fifteen Emphasized ones it has
 * never adopted. The fifteen it renders keep the Material 3 values they have always had, written out
 * here, so a Material upgrade cannot move the app's text quietly. The fifteen Emphasized slots have
 * no such history: they are taken from [materialBaseline] and passed through [scaledBy], which is
 * what makes a screen adopting one tomorrow inherit the setting instead of ignoring it.
 *
 * ## A slot this builder does not name
 *
 * The slot list is explicit, and nothing in the language can see that it is incomplete:
 * `Typography`'s constructor substitutes Material 3's built-in style for any slot left out, and that
 * style knows nothing about [scale]. A screen adopting such a slot would render text that ignores the
 * user's setting while every other style honours it, with nothing on screen to say so. The test
 * `TypographyScaleTest` closes that hole: it walks all thirty slots at a scale of 1.25 and fails for
 * any slot that did not move.
 *
 * ## Why the slots the app renders are written out instead of derived
 *
 * Deriving them too would be shorter, but `Typography()`'s defaults carry a per-slot `letterSpacing`
 * that these styles have never set, so deriving would change the tracking of every text on every
 * screen at every scale — a typography change arriving with an accessibility setting. The
 * written-out values leave what the UI renders unchanged at the baseline, and the test keeps any of
 * them from being dropped.
 */
fun createTypography(scale: Float = 1f): Typography =
    Typography(
        displayLarge = scaledStyle(57, 64, scale),
        displayMedium = scaledStyle(45, 56, scale),
        displaySmall = scaledStyle(36, 48, scale),
        headlineLarge = scaledStyle(32, 40, scale),
        headlineMedium = scaledStyle(28, 36, scale),
        headlineSmall = scaledStyle(24, 32, scale),
        titleLarge = scaledStyle(22, 28, scale),
        titleMedium = scaledStyle(16, 24, scale, FontWeight.Medium),
        titleSmall = scaledStyle(14, 20, scale, FontWeight.Medium),
        bodyLarge = scaledStyle(16, 24, scale),
        bodyMedium = scaledStyle(14, 20, scale),
        bodySmall = scaledStyle(12, 16, scale),
        labelLarge = scaledStyle(14, 20, scale, FontWeight.Medium),
        labelMedium = scaledStyle(12, 16, scale, FontWeight.Medium),
        labelSmall = scaledStyle(11, 14, scale, FontWeight.Medium),
        displayLargeEmphasized = materialBaseline.displayLargeEmphasized.scaledBy(scale),
        displayMediumEmphasized = materialBaseline.displayMediumEmphasized.scaledBy(scale),
        displaySmallEmphasized = materialBaseline.displaySmallEmphasized.scaledBy(scale),
        headlineLargeEmphasized = materialBaseline.headlineLargeEmphasized.scaledBy(scale),
        headlineMediumEmphasized = materialBaseline.headlineMediumEmphasized.scaledBy(scale),
        headlineSmallEmphasized = materialBaseline.headlineSmallEmphasized.scaledBy(scale),
        titleLargeEmphasized = materialBaseline.titleLargeEmphasized.scaledBy(scale),
        titleMediumEmphasized = materialBaseline.titleMediumEmphasized.scaledBy(scale),
        titleSmallEmphasized = materialBaseline.titleSmallEmphasized.scaledBy(scale),
        bodyLargeEmphasized = materialBaseline.bodyLargeEmphasized.scaledBy(scale),
        bodyMediumEmphasized = materialBaseline.bodyMediumEmphasized.scaledBy(scale),
        bodySmallEmphasized = materialBaseline.bodySmallEmphasized.scaledBy(scale),
        labelLargeEmphasized = materialBaseline.labelLargeEmphasized.scaledBy(scale),
        labelMediumEmphasized = materialBaseline.labelMediumEmphasized.scaledBy(scale),
        labelSmallEmphasized = materialBaseline.labelSmallEmphasized.scaledBy(scale),
    )

/**
 * One slot the app renders: Material 3's baseline [fontSize] and [lineHeight] for the slot, both
 * multiplied by [scale].
 *
 * The two numbers are given in `sp` as integers so that this function is the only place the scale is
 * applied, and so that the font size and the line height are taken from the same baseline pair
 * rather than from two independent edits.
 */
private fun scaledStyle(
    fontSize: Int,
    lineHeight: Int,
    scale: Float,
    weight: FontWeight = FontWeight.Normal,
): TextStyle =
    TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = weight,
        fontSize = (fontSize * scale).sp,
        lineHeight = (lineHeight * scale).sp,
    )

/**
 * One slot the app has not adopted: Material 3's own style for it, with its size and line height
 * multiplied by [scale] and everything else — weight, family, tracking — left as Material defines
 * it, because the app has never rendered this slot and has nothing to preserve.
 */
private fun TextStyle.scaledBy(scale: Float): TextStyle =
    copy(
        fontSize = fontSize * scale,
        lineHeight = lineHeight * scale,
    )
