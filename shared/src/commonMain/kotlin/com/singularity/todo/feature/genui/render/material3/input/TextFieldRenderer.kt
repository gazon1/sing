package com.singularity.todo.feature.genui.render.material3.input

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.DataContext
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.resolveText
import kotlinx.serialization.json.JsonPrimitive

internal fun ComponentRegistry.registerTextField(): Unit = register("text_field") { node, ctx, modifier ->
    val f = node as UiNode.TextField
    BoundTextField(f, ctx, modifier)
}

/**
 * A text field bound to the data model in both directions.
 *
 * The value shown is the one at the field's path, falling back to `initial` before any data has
 * arrived. Rendering `initial` unconditionally was the bug: a surface that sent both a field and
 * the value for it showed the field empty, and a value written by an earlier message was ignored.
 */
@Composable
private fun BoundTextField(f: UiNode.TextField, ctx: DataContext, modifier: Modifier) {
    val bound by ctx.value(f.path).collectAsStateWithLifecycle(initialValue = null)
    val current: String = (bound as? JsonPrimitive)?.content ?: f.initial
    OutlinedTextField(
        value = current,
        onValueChange = { newValue: String -> ctx.write(f.path, JsonPrimitive(newValue)) },
        label = { Text(ctx.resolveText(f.label)) },
        modifier = modifier.genuiTag(
            name = "textfield_${f.label.replace(" ", "_")}",
            description = "TextField: ${f.label}",
        ),
    )
}
