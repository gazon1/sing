package com.singularity.todo.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import kotlin.math.sqrt

/**
 * The relative luminance at which black and white contrast equally with a background.
 *
 * WCAG's ratio is `(lighter + 0.05) / (darker + 0.05)`, so the two inks meet where
 * `(L + 0.05) / 0.05 == 1.05 / (L + 0.05)` — that is, at `L = sqrt(1.05 * 0.05) - 0.05 ≈ 0.179`.
 * Black is the more legible ink above it and white below it, and at the crossover itself both
 * still reach ≈4.6:1, so every background gets a foreground that clears WCAG AA for body text.
 *
 * A midpoint of 0.5 is not this number, and using it hands white to everything between 0.179
 * and 0.5 — where white's contrast falls as low as 1.9:1, and where most of the project palette
 * sits (Green 3.0:1, Orange 1.05:1 under white).
 */
private val BLACK_WHITE_CROSSOVER: Float = sqrt(1.05f * 0.05f) - 0.05f

/**
 * Foreground (black or white) that is most readable on [this] background.
 *
 * Picks the ink with the higher WCAG contrast ratio rather than the one that merely looks
 * opposite to the background, so a mid-luminance colour gets black and stays readable.
 */
fun Color.contrastingForeground(): Color = if (luminance() > BLACK_WHITE_CROSSOVER) Color.Black else Color.White
