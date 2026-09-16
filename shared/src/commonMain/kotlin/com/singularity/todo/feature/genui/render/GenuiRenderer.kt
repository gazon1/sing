package com.singularity.todo.feature.genui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.surface.Surface
import com.singularity.todo.feature.genui.surface.SurfaceId

/**
 * Renders a GenUI surface inside a Compose UI.
 *
 * Collects the surface state once and dispatches to [ComponentRegistry] for the
 * root node. The registry is reached through [DataContext.registry], so this
 * function intentionally does not take a separate [ComponentRegistry] parameter.
 *
 * Usage:
 * ```
 * val ctx = rememberDataContext(surfaceId, controller)
 * GenuiRenderer(surfaceId, ctx)
 * ```
 */
@Composable
fun GenuiRenderer(surfaceId: SurfaceId, ctx: DataContext, modifier: Modifier = Modifier) {
    val surfaces by ctx.surfaces.collectAsState()
    val surface = surfaces[surfaceId] ?: return
    RenderNode(nodeRef = surface.rootId, surface = surface, ctx = ctx, modifier = modifier)
}

/**
 * Recursive dispatcher. Looks up [nodeRef] inside [surface] and asks the
 * registry to render it. Registry is read from [ctx.registry] — no need to
 * pass it explicitly.
 */
@Composable
private fun RenderNode(nodeRef: NodeRef, surface: Surface, ctx: DataContext, modifier: Modifier = Modifier) {
    val node = surface.components[nodeRef.id] ?: return
    ctx.registry.render(node, ctx, modifier)
}
