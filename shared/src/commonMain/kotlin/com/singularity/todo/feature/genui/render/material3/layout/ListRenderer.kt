package com.singularity.todo.feature.genui.render.material3.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.material3.RenderSingle

private val MAX_LIST_HEIGHT = 400.dp
private val ITEM_SPACING = 4.dp

internal fun ComponentRegistry.registerList(): Unit = register("list") { node, ctx, modifier ->
    val l = node as UiNode.ListView
    when (l.direction) {
        UiNode.Direction.Vertical -> LazyColumn(
            verticalArrangement = Arrangement.spacedBy(ITEM_SPACING),
            modifier = modifier.genuiTag("list_v", "List").heightIn(max = MAX_LIST_HEIGHT),
        ) { items(l.children) { ref -> RenderSingle(ref, ctx) } }

        UiNode.Direction.Horizontal -> LazyRow(
            horizontalArrangement = Arrangement.spacedBy(ITEM_SPACING),
            modifier = modifier.genuiTag("list_h", "List"),
        ) { items(l.children) { ref -> RenderSingle(ref, ctx) } }
    }
}
