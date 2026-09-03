package com.singularity.todo.feature.genui.catalog

/**
 * The basic component catalog for GenUI surfaces.
 *
 * These are the only component kinds the LLM is allowed to emit.
 * Each name corresponds to a `@SerialName` value on [UiNode] subtypes.
 *
 * To regenerate after adding a new node type:
 * ```
 * UiNode::class.sealedSubclasses
 *     .mapNotNull { it.annotations.filterIsInstance<kotlin.serialization.SerialName>().firstOrNull()?.serialName }
 * ```
 */
object BasicCatalog {
    val componentNames: List<String> = listOf(
        "text", "heading", "button", "column", "row", "card",
        "list", "divider", "badge", "text_field", "checkbox", "tabs", "icon", "modal",
    )

    /** System-prompt appendix instructing the LLM to use only catalog components. */
    val systemPromptAppendix: String = buildString {
        append("You may use only these UI component kinds: ")
        append(componentNames.joinToString(", "))
        append(". Reference child nodes by their id (NodeRef). ")
        append("User actions arrive as action ids you supplied. ")
        append("All JSON messages must be A2UI v0.9 formatted.")
    }

    /** All component kinds joined as a pipe-separated regex alternation for validation. */
    val kindPattern: String = componentNames.joinToString("|")
}
