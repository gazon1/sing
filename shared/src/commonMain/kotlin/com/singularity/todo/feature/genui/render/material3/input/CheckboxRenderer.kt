package com.singularity.todo.feature.genui.render.material3.input

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.resolveText
import kotlinx.serialization.json.JsonPrimitive

internal fun ComponentRegistry.registerCheckbox(): Unit = register("checkbox") { node, ctx, modifier ->
    val c = node as UiNode.Checkbox
    BoundCheckbox(c, ctx, modifier)
}

/**
 * A checkbox reading and writing the data model.
 *
 * It used to render `initial` and report an inverted copy of it, which meant the displayed state
 * never changed and a value written by the model was ignored. The value at the path is the value
 * shown; [UiNode.Checkbox.initial] is only what to show before any data arrives.
 */
@Composable
private fun BoundCheckbox(
    c: UiNode.Checkbox,
    ctx: com.singularity.todo.feature.genui.render.DataContext,
    modifier: Modifier,
) {
    val bound by ctx.value(c.path).collectAsStateWithLifecycle(initialValue = null)
    val checked: Boolean = (bound as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: c.initial
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .genuiTag("checkbox_${c.label.replace(" ", "_")}", "Checkbox: ${c.label}")
            .selectable(selected = checked, onClick = { ctx.write(c.path, JsonPrimitive(!checked)) }),
    ) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(ctx.resolveText(c.label))
    }
}
