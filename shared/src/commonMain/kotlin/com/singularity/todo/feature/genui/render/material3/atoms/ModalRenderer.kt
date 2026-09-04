package com.singularity.todo.feature.genui.render.material3.atoms

import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.material3.RenderSingle

/**
 * Modal renderer. Currently passes the child through; a future iteration
 * should read [UiNode.Modal.openPath] against the surface data model and
 * show an actual [androidx.compose.material3.AlertDialog] shell when open.
 */
internal fun ComponentRegistry.registerModal(): Unit = register("modal") { node, ctx, modifier ->
    val m = node as UiNode.Modal
    RenderSingle(m.child, ctx, modifier)
}
