package com.singularity.todo.feature.genui.render.material3.input

import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.textfields.OwnedText
import com.singularity.todo.core.ui.textfields.rememberBridgedTextFieldState
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
 *
 * **The value is not a local string.** It is a `JsonPrimitive` at a path in the surface's shared
 * data model, and that model can be rewritten by an incoming server message
 * (`UiEvent.UpdateData`) *while the user is typing*. The field therefore uses the shared bridge,
 * `rememberBridgedTextFieldState` in `core/ui/components/TextFieldStateBridge.kt`, which keeps
 * the field's own text and the model in agreement in both directions.
 *
 * **The rule the bridge applies.** The field owns the text while the user types: every edit is
 * written to the model, and a model value that is not an echo of what the field itself pushed
 * replaces the field's text. A server rewrite therefore wins over the keyboard, which is what
 * the value-based field did — the model always won — so the conflict rule the surface protocol
 * never spelled out is the bridge's rule, and it is the same one the rest of the app uses.
 *
 * **The one difference.** The bridge recognises an echo by the values the field pushed, not by
 * the last one alone: a model value that equals a value the field pushed earlier is treated as
 * an echo and is not written back into the field. The difference is benign in the common case —
 * the field already shows that text, so nothing visible changes — but it is *not* benign when
 * the server rewrites the field to a value the field had already sent and has moved on since.
 * That is a rewrite, not an echo, and the field would keep the text the user typed.
 *
 * **The rewrite token.** The surface knows which writes came from the server and which came from
 * the field, so it can say "this value is a rewrite": `ctx.serverRevision()` counts the server's
 * writes. The server is the owner of the value, so the counter belongs to the source the field
 * is handed — an owned source, which says in its type that the server may replace the text with
 * anything. A server rewrite then wins over the keyboard — the same rule the value-based field
 * had, and the same rule the surface protocol never spelled out. `TextFieldStateBridgeTest` pins
 * the guards.
 *
 * **This renderer builds the source, and that is the exception.** Everywhere else the owner
 * builds it and the field passes it through; here the owner is a server the renderer does not
 * own, and the renderer is the boundary where the server's value and the server's counter are
 * read. The source it builds is still the server's — the count comes from the data context, not
 * from the renderer.
 *
 * See ADR `2026-10-19-text-field-state-migration`.
 */
@Composable
private fun BoundTextField(f: UiNode.TextField, ctx: DataContext, modifier: Modifier) {
    val bound by ctx.value(f.path).collectAsStateWithLifecycle(initialValue = null)
    val current: String = (bound as? JsonPrimitive)?.content ?: f.initial
    val serverRevision by ctx.serverRevision().collectAsStateWithLifecycle(initialValue = 0L)
    val state = rememberBridgedTextFieldState(
        source = OwnedText(current, serverRevision),
        onTextChanged = { newValue: String -> ctx.write(f.path, JsonPrimitive(newValue)) },
    )
    OutlinedTextField(
        state = state,
        label = { Text(ctx.resolveText(f.label)) },
        modifier = modifier.genuiTag(
            name = "textfield_${f.label.replace(" ", "_")}",
            description = "TextField: ${f.label}",
        ),
    )
}
