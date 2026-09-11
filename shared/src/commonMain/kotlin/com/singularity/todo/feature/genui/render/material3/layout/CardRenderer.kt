package com.singularity.todo.feature.genui.render.material3.layout

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.material3.RenderSingle

internal fun ComponentRegistry.registerCard(): Unit = register("card") { node, ctx, modifier ->
    val c = node as UiNode.Card
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
            .genuiTag("card", "Card")
            .padding(4.dp),
    ) { RenderSingle(c.child, ctx) }
}
