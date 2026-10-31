package com.singularity.todo.core.ui

import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material3.minimumInteractiveComponentSize

object TouchTarget {
    val MinSize = 48.dp
}

fun Modifier.touchTarget(minSize: androidx.compose.ui.unit.Dp = TouchTarget.MinSize): Modifier =
    this
        .minimumInteractiveComponentSize(minSize)
