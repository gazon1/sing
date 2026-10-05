package com.singularity.todo.feature.genui.catalog

import kotlinx.serialization.json.JsonElement

/**
 * Renders [A2uiCatalog] into the instruction block the model reads.
 *
 * Generated rather than written, and generated **deterministically**: every collection is sorted by
 * name, so an unchanged catalog produces a byte-identical prompt. Without that, a diff in the
 * prompt is indistinguishable from a change in the vocabulary, and the prompt is not versioned
 * anywhere else.
 *
 * The previous instruction was one sentence listing fourteen names. It said nothing about
 * properties, which is where generation actually fails, and nothing about the wire format, so the
 * model invented both and the client discarded the result without a word.
 *
 * The prompt is built by the caller and passed to the transport, not appended inside it. A
 * transport that edits the system prompt on its own is a second place the vocabulary is described,
 * which is the arrangement this replaces.
 */
object CatalogPrompt {

    /** Renders the instruction block for [catalog]. */
    fun render(catalog: A2uiCatalog): String = buildString {
        appendLine("You answer in the app, optionally drawing a screen with it. The client renders")
        appendLine("surfaces for the catalog `${catalog.id}`.")
        appendLine()
        appendLine("## What to send")
        appendLine("- If a screen would help, send it as JSON Lines after your answer. If it would")
        appendLine("  not — a question, a reminder, something you can say in a sentence — answer in")
        appendLine("  prose alone and send no messages. Most questions do not need a screen.")
        appendLine("- Both at once is normal: a sentence, then the screen that goes with it.")
        appendLine()
        appendLine("## Wire format")
        appendLine("- One JSON object per line, with no markdown fences around them.")
        appendLine("- Anything outside those lines is shown to the user as your answer.")
        appendLine("- Each object has exactly one top-level key naming the operation:")
        appendLine("  `createSurface` {surfaceId, rootId, components[]}, `updateComponents`")
        appendLine("  {surfaceId, components[]}, `updateData` {surfaceId, path, value},")
        appendLine("  `deleteSurface` {surfaceId}.")
        appendLine("- `components` is a flat array. Every entry has `id` and `kind`; components")
        appendLine("  reference each other by id. Exactly one is the root named by `rootId`.")
        appendLine("- `path` is slash-separated into the data model, e.g. `/user/name`.")
        appendLine()
        appendLine("## Components")
        catalog.components.keys.sorted().forEach { name: String ->
            appendLine(renderComponent(catalog.component(name) ?: return@forEach))
        }
        appendLine()
        appendLine("## Functions")
        appendLine("Call these from inside a string as `${'$'}{fn(first, second)}`, arguments in the")
        appendLine("order listed below. An argument may itself be a data path: `${'$'}{fn(${'$'}{/p})}`.")
        appendLine("A bare `${'$'}{/user/name}` interpolates that data-model path.")
        catalog.functions.keys.sorted().forEach { name: String ->
            val schema: A2uiFunctionSchema = catalog.functions.getValue(name)
            appendLine("- ${schema.signature} — ${schema.description}")
        }
        appendLine()
        appendLine("## Rules")
        appendLine("- Use only the kinds and functions above. Anything else is rejected.")
        appendLine("- A rejected message is reported back to you with the reason; read it and")
        appendLine("  correct that specific value rather than resending the same surface.")
        appendLine("- Send `createSurface` once per surface, then `updateComponents` for the rest.")
        appendLine("- Populate data with `updateData` rather than pasting values into components.")
    }

    private fun renderComponent(schema: A2uiComponentSchema): String = buildString {
        append("- ${schema.name}: ${schema.description}")
        if (schema.properties.isEmpty()) {
            append(" (no properties)")
        }
        for (property in schema.properties) {
            append("\n    ")
            append(property.name)
            append(": ")
            append(typeName(property))
            // Requiredness is spelled out on both branches. Leaving it to the absence of
            // "(optional)" is how a model ends up sending a button with no label and no way to
            // find out that the label was the problem.
            if (property.required) {
                append(", required")
            } else {
                append(", optional")
            }
            val default: JsonElement? = property.default
            if (default != null) append(" (default ")
            if (default != null) append(default.toString())
            if (default != null) append(")")
        }
        val parents: Set<String>? = schema.allowedParents
        if (parents != null) {
            append("\n    only inside: ")
            append(parents.sorted().joinToString(", "))
        }
    }

    private fun typeName(property: A2uiProperty): String {
        val base: String = when (property.type) {
            A2uiType.STRING -> "string"
            A2uiType.INT -> "integer"
            A2uiType.BOOL -> "boolean"
            A2uiType.NODE_REF -> "component id"
            A2uiType.NODE_REFS -> "list of component ids"
            A2uiType.TONE -> "tone"
            A2uiType.DIRECTION -> "direction"
            A2uiType.PATH -> "data path"
            A2uiType.JSON -> "value"
        }
        return if (property.enumValues.isEmpty()) {
            base
        } else {
            "$base — one of: ${property.enumValues.joinToString(", ")}"
        }
    }
}
