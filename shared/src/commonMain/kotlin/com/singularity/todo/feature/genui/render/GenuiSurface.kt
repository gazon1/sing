package com.singularity.todo.feature.genui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The one composable an application screen uses to draw a generated surface.
 *
 * Everything else in this layer is reachable from here and from nowhere else, which is the point
 * of the split: the parser, the validator and the surface store are implementation, and a screen
 * that reaches for them has coupled itself to how the model happens to be served today.
 *
 * Usage:
 * ```
 * val ctx = rememberDataContext(surfaceId, controller, registry, onAction, onDataChange)
 * GenuiSurface(ctx, modifier = Modifier)
 * ```
 */
@Composable
fun GenuiSurface(ctx: DataContext, modifier: Modifier = Modifier) {
    // A plain Flow, so the initial value has to be stated: there is no current value to inherit
    // before the surface exists, and "nothing yet" is the honest answer.
    val rootRef by ctx.rootRef().collectAsStateWithLifecycle(initialValue = null)
    val root = rootRef ?: return
    val node by ctx.component(root.id).collectAsStateWithLifecycle(initialValue = null)
    ctx.registry.render(node ?: return, ctx, modifier)
}
