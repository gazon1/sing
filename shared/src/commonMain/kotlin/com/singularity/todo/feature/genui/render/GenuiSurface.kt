package com.singularity.todo.feature.genui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
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
 * val ctx = rememberDataContext(surfaceId, controller, registry, onDataChange)
 * CompositionLocalProvider(LocalGenuiOnAction provides { _, name, data -> onAction(name, data) }) {
 *     GenuiSurface(ctx, modifier = Modifier)
 * }
 * ```
 */
@Composable
fun GenuiSurface(ctx: DataContext, modifier: Modifier = Modifier) {
    // UI-derived: which surface exists and its root id. Derived from the surfaces map rather than
    // a collected flow, so an unrelated surface change does not recompose this node.
    val surfacesState = ctx.surfaces.collectAsStateWithLifecycle()
    val rootRefState = remember(surfacesState, ctx.surfaceId) {
        derivedStateOf { surfacesState.value[ctx.surfaceId]?.rootId }
    }
    val root = rootRefState.value ?: return
    // UI-derived: one component's node, derived from that surface's component store.
    val store = surfacesState.value[ctx.surfaceId]?.componentStore ?: return
    val componentsState = store.all.collectAsStateWithLifecycle()
    val nodeState = remember(store, root.id) {
        derivedStateOf { componentsState.value[root.id] }
    }
    ctx.registry.render(nodeState.value ?: return, ctx, modifier)
}
