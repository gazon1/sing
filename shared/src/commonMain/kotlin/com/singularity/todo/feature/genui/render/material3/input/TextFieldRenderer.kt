package com.singularity.todo.feature.genui.render.material3.input

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag
import kotlinx.serialization.json.JsonPrimitive

internal fun ComponentRegistry.registerTextField(): Unit = register("text_field") { node, ctx, modifier ->
    val f = node as UiNode.TextField
    OutlinedTextField(
        value = f.initial,
        onValueChange = { newValue: String ->
            ctx.onDataChange(ctx.surfaceId, f.path, JsonPrimitive(newValue))
        },
        label = { Text(f.label) },
        modifier = modifier.genuiTag(
            name = "textfield_${f.label.replace(" ", "_")}",
            description = "TextField: ${f.label}",
        ),
    )
}
