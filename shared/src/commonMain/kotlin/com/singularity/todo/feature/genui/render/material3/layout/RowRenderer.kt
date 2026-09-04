package com.singularity.todo.feature.genui.render.material3.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.material3.RenderSingle

internal fun ComponentRegistry.registerRow(): Unit = register("row") { node, ctx, modifier ->
    val r = node as UiNode.Row
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.genuiTag("row", "Row"),
    ) {
        r.children.forEach { ref -> RenderSingle(ref, ctx) }
    }
}
