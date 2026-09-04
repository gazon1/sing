package com.singularity.todo.feature.genui.render

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.singularity.todo.feature.genui.catalog.UiNode

/**
 * A registry mapping [UiNode] `kind` strings to their Compose render functions.
 *
 * Renderers are registered via [register] and looked up during rendering.
 * Use [GenuiRenderer] in your UI to dispatch to the registry.
 *
 * Example:
 * ```
 * val registry = ComponentRegistry().also { Material3Catalog.install(it) }
 * ```
 */
class ComponentRegistry {

    private val builders = mutableMapOf<String, @Composable (UiNode, DataContext, Modifier) -> Unit>()

    /**
     * Registers a builder for [kind]. The builder receives the concrete [UiNode] subtype
     * (already cast from the sealed interface), the [DataContext] and an optional [Modifier].
     */
    fun register(kind: String, builder: @Composable (UiNode, DataContext, Modifier) -> Unit) {
        builders[kind] = builder
    }

    /**
     * Renders [node] using the registered builder for its kind.
     * Falls back to a simple error text if the kind is unknown.
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
            androidx.compose.material3.Text(
                text = "[Unknown: $kind]",
                color = androidx.compose.ui.graphics.Color.Red,
                modifier = modifier,
            )
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
        is UiNode.Tab -> "tab"       // nested inside Tabs; not registered separately
        is UiNode.Icon -> "icon"
        is UiNode.Modal -> "modal"
    }
