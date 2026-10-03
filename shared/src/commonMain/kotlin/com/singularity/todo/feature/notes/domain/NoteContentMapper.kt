package com.singularity.todo.feature.notes.domain

import com.mohamedrejeb.richeditor.model.RichTextState
import com.singularity.todo.feature.notes.LinkRef
import com.singularity.todo.feature.notes.LinkSchemes
import com.singularity.todo.feature.notes.extractOutgoingLinks

/**
 * Pure HTML↔Markdown and outgoing-link conversions.
 * Lives outside the VM so HTML parsing and rich-editor construction don't pollute orchestration logic.
 * Tested in commonTest.
 *
 * ## Platform availability
 *
 * [toHtml] and [toMarkdown] use `com.mohamedrejeb:richeditor` (RichTextState), which is
 * available on Android and Desktop Compose. For the AI tool path (JVM), the rich-editor
 * artifact is loaded via `rich-editor-compose` on Desktop and via the Compose classpath on
 * Android (tools are JVM-only in practice — Koog runs on the server/JVM, not on Android).
 * If a platform lacks rich-editor, use [markdownToHtmlSimple] as a fallback.
 */
object NoteContentMapper {

    /**
     * Converts markdown to canonical HTML using the rich-editor library.
     * Used by [com.singularity.todo.feature.ai.tools.CreateNoteTool] to store notes
     * with the canonical `bodyHtml` representation.
     */
    fun toHtml(markdown: String): String = RichTextState().apply { setMarkdown(markdown) }.toHtml()

    /**
     * Converts the editor's HTML back to markdown for storage.
     * Used when migrating legacy notes or when the markdown representation is needed.
     */
    fun toMarkdown(html: String): String = RichTextState().apply { setHtml(html) }.toMarkdown()

    /**
     * Extracts `note://...` / `task://...` URLs from the editor HTML,
     * ready for [com.singularity.todo.feature.notes.domain.port.NotesRepository.setOutgoingLinks].
     */
    fun outgoingLinkUrls(html: String): List<String> = extractOutgoingLinks(html).map { ref ->
        when (ref) {
            is LinkRef.Note -> "${LinkSchemes.NOTE_PREFIX}${ref.noteId}"
            is LinkRef.Task -> "${LinkSchemes.TASK_PREFIX}${ref.taskId}"
        }
    }
}
