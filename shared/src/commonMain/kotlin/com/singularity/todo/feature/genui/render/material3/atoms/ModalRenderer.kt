package com.singularity.todo.feature.genui.render.material3.atoms

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.singularity.todo.core.ui.mapTestTagsAsResourceIds
import com.singularity.todo.feature.genui.catalog.UiNode
import com.singularity.todo.feature.genui.render.ComponentRegistry
import com.singularity.todo.feature.genui.render.DataContext
import com.singularity.todo.feature.genui.render.genuiTag
import com.singularity.todo.feature.genui.render.material3.RenderSingle
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * The modal renderer: a dialog shown while a data-model path holds true.
 *
 * The catalogue declares `openPath` as required and tells the model that the panel shows while it
 * is true, and this renderer used to draw the child inline whatever the path held — the
 * declaration was a promise the client did not keep, and a model that sent a modal got its content
 * pasted into the surface whether it had asked for a dialog or not.
 *
 * The path is read from the surface's own data model, which is where a model's `updateData` lands
 * and where a dismissal writes back. That is the whole reason the flag lives there rather than in
 * the renderer: the model can open the dialog again by writing `true` to it, and a dismissal is a
 * value in the model rather than a piece of state nobody else can see.
 */
internal fun ComponentRegistry.registerModal(): Unit =
    register("modal") { node, ctx, modifier ->
        val m = node as UiNode.Modal
        ModalView(m, ctx, modifier)
    }

/**
 * The dialog itself.
 *
 * Nothing is drawn while the path is closed — not the child, not an empty box. A modal that drew
 * its content inline and then covered it with a dialog would show the same content twice, and the
 * copy under the dialog would still be there after it closed.
 */
@Composable
private fun ModalView(
    modal: UiNode.Modal,
    ctx: DataContext,
    modifier: Modifier,
) {
    val open by ctx.valueWithoutCount(modal.openPath).collectAsStateWithLifecycle(initialValue = null)
    if (!modalIsOpen(open)) return

    AlertDialog(
        onDismissRequest = { closeModal(modal, ctx) },
        // A dialog is its own window, so the app-root mapping never reaches it: without this the
        // tags inside the dialog are invisible to UI automation.
        modifier =
            modifier
                .genuiTag(MODAL_TAG, "Modal dialog")
                .mapTestTagsAsResourceIds(),
        text = { RenderSingle(modal.child, ctx) },
        confirmButton = {
            TextButton(
                onClick = { closeModal(modal, ctx) },
                modifier = Modifier.genuiTag(MODAL_CLOSE_TAG, "Close the dialog"),
            ) {
                Text(CLOSE_LABEL)
            }
        },
    )
}

/**
 * Closes the dialog by writing `false` to the path it was opened by.
 *
 * The write is what closes it: the renderer holds no state of its own, so the value in the data
 * model is the only thing that says whether the dialog is open. Writing rather than flipping a
 * local flag is also what lets the model reopen it — by writing `true` to the same path.
 */
private fun closeModal(
    modal: UiNode.Modal,
    ctx: DataContext,
) {
    ctx.write(modal.openPath, JsonPrimitive(false))
}

/**
 * Whether the value at a modal's path means "open".
 *
 * A boolean is what the catalogue describes, and a string is what a model that wrote `"true"` as
 * text produced — both mean open. Anything else, including no value at all, means closed: a modal
 * that opened on an unreadable value would open on a typo.
 */
internal fun modalIsOpen(value: JsonElement?): Boolean {
    val primitive: JsonPrimitive = value as? JsonPrimitive ?: return false
    return primitive.content.toBooleanStrictOrNull() == true
}

/** The tag the dialog carries, so a test can tell a modal from the content around it. */
private const val MODAL_TAG: String = "modal"

/** The tag the control that closes the dialog carries. */
private const val MODAL_CLOSE_TAG: String = "modal_close"

/** The label of the control that closes the dialog. */
private const val CLOSE_LABEL: String = "Close"
