package com.singularity.todo.feature.genui.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import co.touchlab.kermit.Logger
import com.singularity.todo.feature.genui.catalog.UiNode

private val logger = Logger.withTag("ComponentRegistry")

/**
 * A registry mapping [UiNode] `kind` strings to their Compose render functions.
 *
 * Renderers are registered via [register] and looked up during rendering.
 * Use [GenuiSurface] in your UI to dispatch to the registry.
 *
 * Example:
 * ```
 * val registry = ComponentRegistry().also { Material3Catalog.installAll(it) }
 * ```
 */
class ComponentRegistry {

    private val builders = mutableMapOf<String, @Composable (UiNode, DataContext, Modifier) -> Unit>()

    /**
     * Registers a builder for [kind]. The builder receives the concrete [UiNode] subtype
     * (already cast from the sealed interface), the [DefaultDataContext] and an optional [Modifier].
     */
    fun register(kind: String, builder: @Composable (UiNode, DataContext, Modifier) -> Unit) {
        builders[kind] = builder
    }

    /**
     * Renders [node] using the registered builder for its kind.
     *
     * [modifier] is forwarded to every registered builder so the caller can
     * position the rendered subtree without each builder having to thread it
     * through its own signature.
     */
    @Composable
    fun render(node: UiNode, ctx: DataContext, modifier: Modifier = Modifier) {
        val kind = node.kind
        val builder = builders[kind]
        if (builder != null) {
            builder(node, ctx, modifier)
        } else {
            // A node with no builder is a defect on this side, not a forward-compatible message
            // arriving from a newer model: the validator rejects a kind the catalog does not
            // declare, so nothing undeclared can reach here. It happens when a renderer is added
            // without its registration, or a component is registered without its schema — the two
            // directions `SingularityCatalogTest` checks, and the reason the branch warns rather
            // than quietly drawing nothing.
            logger.w { "No renderer registered for '$kind' — it draws nothing" }
        }
    }

    /** Returns true if [kind] has a registered builder. */
    fun has(kind: String): Boolean = kind in builders
}

/** Returns the JSON `kind` discriminator for this node. */
val UiNode.kind: String
    get() = when (this) {
        is UiNode.Text -> "text"

        is UiNode.Heading -> "heading"

        is UiNode.Button -> "button"

        is UiNode.Column -> "column"

        is UiNode.Row -> "row"

        is UiNode.Card -> "card"

        is UiNode.ListView -> "list"

        is UiNode.Divider -> "divider"

        is UiNode.Badge -> "badge"

        is UiNode.TextField -> "text_field"

        is UiNode.Checkbox -> "checkbox"

        is UiNode.Tabs -> "tabs"

        // Held by Tabs as {title, child}; the panel is drawn by the tabs renderer, which never
        // hands one to `render` on its own.
        is UiNode.Tab -> "tab"

        is UiNode.Icon -> "icon"

        is UiNode.Modal -> "modal"

        is UiNode.TaskCard -> "task_card"

        is UiNode.DueDate -> "due_date"

        is UiNode.ProjectChip -> "project_chip"
    }
