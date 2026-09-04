package com.singularity.todo.feature.genui.render.material3.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.material3.RenderSingle

internal fun ComponentRegistry.registerColumn(): Unit = register("column") { node, ctx, modifier ->
    val c = node as UiNode.Column
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.genuiTag("column", "Column"),
    ) {
        c.children.forEach { ref -> RenderSingle(ref, ctx) }
    }
}
