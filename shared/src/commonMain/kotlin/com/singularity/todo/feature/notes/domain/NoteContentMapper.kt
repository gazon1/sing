package com.singularity.todo.feature.notes.domain

import com.mohamedrejeb.richeditor.model.RichTextState
import com.singularity.todo.feature.notes.LinkRef
import com.singularity.todo.feature.notes.LinkSchemes
import com.singularity.todo.feature.notes.extractOutgoingLinks

/**
 * Pure HTML↔Markdown and outgoing-link conversions.
 * Lives outside the VM so HTML parsing and rich-editor construction don't pollute orchestration logic.
 * Tested in commonTest.
 */
internal object NoteContentMapper {

    /** Converts legacy markdown (from DB) to HTML using the rich-editor library. */
    fun toHtml(markdown: String): String = RichTextState().apply { setMarkdown(markdown) }.toHtml()

    /** Converts the editor's HTML back to markdown for storage. */
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
