package com.singularity.todo.feature.genui.render.material3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.genui.catalog.NodeRef
import com.singularity.todo.feature.genui.render.DataContext

/**
 * Recursive dispatch helper used by container renderers (Column, Row, Card, ListView, Modal,
 * Tabs). Looks the child up through [DataContext.component] and asks the registry to render it.
 *
 * The subscription is to the child, not to the surface. This helper used to collect the whole
 * surface map, which meant every node in the tree was a subscriber to every change anywhere in it
 * — a surface of a hundred nodes re-composed a hundred times for one edited field.
 *
 * A missing child renders nothing: a container routinely names components that a later message
 * will define, and that is a wait, not an error.
 */
@Composable
internal fun RenderSingle(ref: NodeRef, ctx: DataContext, modifier: Modifier = Modifier) {
    val node by ctx.component(ref.id).collectAsStateWithLifecycle(initialValue = null)
    ctx.registry.render(node ?: return, ctx, modifier)
}
