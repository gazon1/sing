package com.singularity.todo.feature.genui.render.material3.input

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.material3.RenderSingle

internal fun ComponentRegistry.registerTabs(): Unit = register("tabs") { node, ctx, modifier ->
    val t = node as UiNode.Tabs
    if (t.tabs.isEmpty()) return@register
    var selected by remember { mutableIntStateOf(0) }
    Column(modifier = modifier.genuiTag("tabs", "Tabs")) {
        PrimaryTabRow(selectedTabIndex = selected) {
            t.tabs.forEachIndexed { idx, tab ->
                Tab(
                    selected = selected == idx,
                    onClick = { selected = idx },
                    text = { Text(tab.title) },
                )
            }
        }
        HorizontalDivider()
        val selectedTab = t.tabs.getOrNull(selected) ?: return@Column
        RenderSingle(selectedTab.child, ctx)
    }
}
