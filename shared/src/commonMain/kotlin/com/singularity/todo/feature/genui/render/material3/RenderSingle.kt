package com.singularity.todo.feature.genui.render.material3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.render.DataContext

/**
 * Recursive dispatch helper used by container renderers (Column, Row, Card,
 * ListView, Modal, Tabs). Looks up the child node via the surface cached in
 * [DataContext.surfaces] and asks [DataContext.registry] to render it.
 *
 * The surface is collected once per recomposition; in deep trees this avoids
 * the previous O(N) `collectAsState()` calls that lived inside every renderer.
 */
@Composable
internal fun RenderSingle(ref: NodeRef, ctx: DataContext, modifier: Modifier = Modifier) {
    val surfaces by ctx.surfaces.collectAsState()
    val surface = surfaces[ctx.surfaceId] ?: return
    val node = surface.components[ref.id] ?: return
    ctx.registry.render(node, ctx, modifier)
}
