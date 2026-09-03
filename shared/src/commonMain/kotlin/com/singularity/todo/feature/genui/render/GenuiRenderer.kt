package com.singularity.todo.feature.genui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.surface.Surface
import com.singularity.todo.feature.genui.surface.SurfaceController
import com.singularity.todo.feature.genui.surface.SurfaceId
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.JsonObject

/**
 * Renders a GenUI surface inside a Compose UI.
 *
 * Collects the surface state from [controller] and dispatches to [registry]
 * for each [UiNode].
 *
 * Usage:
 * ```
 * val registry = remember { ComponentRegistry().also { Material3Catalog.install(it) } }
 * val ctx = rememberDataContext(surfaceId, controller)
 * GenuiRenderer(surfaceId, registry, ctx)
 * ```
 */
@Composable
fun GenuiRenderer(
    surfaceId: SurfaceId,
    registry: ComponentRegistry,
    ctx: DataContext,
    modifier: Modifier = Modifier,
) {
    val surface by ctx.surfaces.collectAsState()
    val s = surface[surfaceId] ?: return

    RecursiveRenderer(
        nodeRef = s.rootId,
        components = s.components,
        registry = registry,
        ctx = ctx,
        modifier = modifier,
    )
}

@Composable
private fun RecursiveRenderer(
    nodeRef: NodeRef,
    components: Map<String, UiNode>,
    registry: ComponentRegistry,
    ctx: DataContext,
    modifier: Modifier = Modifier,
) {
    val node = components[nodeRef.id] ?: return
    registry.render(node, ctx)
}
