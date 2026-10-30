package com.singularity.todo.core.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/** Foreground (black or white) that stays readable on [this] background. */
fun Color.contrastingForeground(): Color =
    if (luminance() > 0.5f) Color.Black else Color.White
