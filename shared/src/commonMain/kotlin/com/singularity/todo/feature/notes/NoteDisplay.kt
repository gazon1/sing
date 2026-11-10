package com.singularity.todo.feature.notes

import com.singularity.todo.feature.notes.domain.model.NoteBody

private const val TITLE_MAX_CHARS = 80
private const val SNIPPET_MAX_CHARS = 120

/**
 * Presentation-only title and snippet derivation for notes.
 *
 * Derived text is never persisted to [Note.title]. Explicit titles always win.
 * The readable-text projection itself lives on [NoteBody] — this file only decides
 * which block becomes the title and what remains for the snippet.
 */
fun Note.displayTitle(untitled: String = "Untitled"): String {
    val explicit = title.trim()
    if (explicit.isNotEmpty()) return title
    if (isFolder) return untitled
    val firstBlock = readableBody()?.readableBlocks?.firstOrNull() ?: return untitled
    return truncate(firstBlock, TITLE_MAX_CHARS)
}

/**
 * Readable body snippet for list/preview surfaces.
 *
 * When the title is derived from the body, the first block already appears as the
 * title: only the remainder of a single long block is shown, and with several blocks
 * the first one is omitted entirely.
 */
fun Note.displaySnippet(maxChars: Int = SNIPPET_MAX_CHARS): String {
    val blocks = readableBody()?.readableBlocks.orEmpty()
    val derived = title.trim().isEmpty() && !isFolder
    val source =
        when {
            blocks.isEmpty() -> {
                ""
            }

            !derived -> {
                blocks.joinToString(" ")
            }

            blocks.size > 1 -> {
                blocks.drop(1).joinToString(" ")
            }

            else -> {
                val first = blocks.first()
                first.removePrefix(first.take(TITLE_MAX_CHARS).trimEnd())
            }
        }
    return truncate(source, maxChars)
}

private fun Note.readableBody(): NoteBody? =
    bodyHtml?.takeIf { it.isNotBlank() }?.let(::NoteBody)
        ?: bodyMarkdown
            ?.takeIf { it.isNotBlank() }
            ?.let { runCatching { NoteBody.fromMarkdown(it) }.getOrNull() }

private fun truncate(
    text: String,
    maxChars: Int,
): String {
    val collapsed = text.replace(Regex("\\s+"), " ").trim()
    if (maxChars <= 0 || collapsed.isEmpty()) return ""
    if (collapsed.length <= maxChars) return collapsed
    return collapsed.take(maxChars).trimEnd() + "…"
}
