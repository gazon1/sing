package com.singularity.todo.feature.genui.render.material3.input

import androidx.compose.material3.Button
import androidx.compose.material3.Text
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.templateResolver

internal fun ComponentRegistry.registerButton(): Unit = register("button") { node, ctx, modifier ->
    val b = node as UiNode.Button
    // Resolved here, at the press, rather than when the surface was drawn: the values in a payload
    // are usually ones the user typed, and they did not exist a moment ago.
    val resolver = ctx.templateResolver()
    Button(
        onClick = {
            val payload = resolver.json(b.data) as? kotlinx.serialization.json.JsonObject
            ctx.onAction(ctx.surfaceId, b.action ?: "", payload)
        },
        modifier = modifier.genuiTag(
            name = "btn_${b.label.replace(" ", "_")}",
            description = "Button: ${b.label}",
        ),
    ) { Text(b.label) }
}
