package com.singularity.todo.core.ui.menu

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.unit.DpOffset

/**
 * JVM Desktop implementation of [Modifier.onSecondaryClick].
 * Uses the Compose pointer event API to detect right mouse button presses.
 *
 * NOTE: `onPointerEvent` is marked [ExperimentalPointerInputApi] in JetBrains Compose Multiplatform,
 * but the annotation class itself is not part of the multiplatform ABI (it lives in AndroidX
 * compose-ui which is JVM-only). We suppress the usage warning so this compiles cleanly.
 */
@Suppress("OPT_IN_USAGE_ERROR")
actual fun Modifier.onSecondaryClick(onClick: (DpOffset) -> Unit): Modifier =
    this.onPointerEvent(PointerEventType.Press) { event ->
        if (event.buttons.isSecondaryPressed) {
            val change = event.changes.first()
            onClick(DpOffset(change.position.x.toDp(), change.position.y.toDp()))
            change.consume()
        }
    }
