package com.singularity.todo.feature.genui.catalog

import kotlinx.serialization.SerialName

/**
 * The basic component catalog for GenUI surfaces.
 *
 * These are the only component kinds the LLM is allowed to emit.
 * Each name corresponds to a `@SerialName` value on [UiNode] subtypes.
 *
 * [componentNames] is auto-generated from [UiNode] sealed subclass annotations —
 * no manual maintenance needed when new node types are added.
 */
object BasicCatalog {
    /**
     * Auto-generated from `@SerialName` annotations on [UiNode] subclasses.
     * Regenerated on each class load; no manual list needed.
     */
    val componentNames: List<String> by lazy {
        UiNode::class.sealedSubclasses
            .mapNotNull { subclass ->
                subclass.annotations.filterIsInstance<SerialName>().firstOrNull()?.value
            }
    }

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
