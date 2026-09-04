package com.singularity.todo.feature.genui.render.material3.input

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag
import kotlinx.serialization.json.JsonPrimitive

internal fun ComponentRegistry.registerCheckbox(): Unit = register("checkbox") { node, ctx, modifier ->
    val c = node as UiNode.Checkbox
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .genuiTag("checkbox_${c.label.replace(" ", "_")}", "Checkbox: ${c.label}")
            .selectable(selected = c.initial, onClick = {
                ctx.onDataChange(ctx.surfaceId, c.path, JsonPrimitive(!c.initial))
            }),
    ) {
        Checkbox(checked = c.initial, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(c.label)
    }
}
