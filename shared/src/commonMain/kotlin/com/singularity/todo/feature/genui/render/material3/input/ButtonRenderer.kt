package com.singularity.todo.feature.genui.render.material3.input

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag

internal fun ComponentRegistry.registerButton(): Unit = register("button") { node, ctx, modifier ->
    val b = node as UiNode.Button
    Button(
        onClick = { ctx.onAction(ctx.surfaceId, b.action ?: "", b.data) },
        modifier = modifier.genuiTag(
            name = "btn_${b.label.replace(" ", "_")}",
            description = "Button: ${b.label}",
        ),
    ) { Text(b.label) }
}
