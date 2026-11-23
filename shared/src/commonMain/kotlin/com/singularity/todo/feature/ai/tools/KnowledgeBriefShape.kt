package com.singularity.todo.feature.ai.tools

import com.singularity.todo.feature.notes.Note
import com.singularity.todo.feature.notes.domain.NoteContentMapper

/**
 * What makes a note a knowledge brief.
 *
 * ## Why the attachment is not enough
 *
 * A task's logbook is made of notes attached to the task, and they are plain, non-folder
 * notes of the same shape a brief has. So "attached to a task" says where a note hangs,
 * not what it is: read as a brief, an ordinary logbook entry would be handed to an agent
 * as the thing to summarise over, and the user's own writing would be replaced by a
 * research summary.
 *
 * ## The marker
 *
 * A brief is a note whose body carries the `## Sources` section — the one part of the
 * shape `docs/architecture/knowledge-briefs.md` already makes mandatory, because a brief
 * without provenance is not a brief. The check is on the rendered text, so it does not
 * care whether the body arrived as HTML or as markdown.
 *
 * A note the user writes by hand and happens to title "Knowledge brief" is not one: the
 * section is what the reader asks for, and the section is what the extraction writes.
 */
internal object KnowledgeBriefShape {
    /**
     * The heading a brief's provenance lives under, as a line of its own.
     *
     * The heading is written as Markdown by the agent and as HTML by the editor, so both
     * forms are recognised — but only as a **heading**. A note whose prose mentions the
     * word ("potential sources include…") is an ordinary note, and treating it as a
     * brief is the data-loss scenario this check exists to prevent.
     */
    private val SOURCES_HEADING = Regex("""(?m)^\s*(?:#{1,6}\s*Sources|\*\*Sources\*\*)\s*$""")

    /** The same heading, as the HTML an editor writes it in. */
    private val SOURCES_HEADING_HTML = Regex("""(?i)<h[1-6][^>]*>\s*Sources\s*</h[1-6]>""")

    /**
     * Whether [note] is a knowledge brief: attached to an owner, plain, and carrying the
     * provenance section.
     */
    fun isBrief(note: Note): Boolean =
        !note.isFolder &&
            note.kind == com.singularity.todo.feature.notes.NoteKind.Plain &&
            (note.projectId != null || note.taskId != null) &&
            carriesSources(note)

    /** Whether the note's body carries the provenance section. */
    fun carriesSources(note: Note): Boolean {
        // HTML first, because that is the canonical form and a heading the editor wrote
        // is an HTML heading; the markdown projection is the fallback for a note that has
        // no HTML yet. Either way the section has to be a heading, not a mention.
        note.bodyHtml?.takeIf { it.isNotBlank() }?.let { html ->
            if (SOURCES_HEADING_HTML.containsMatchIn(html)) return true
        }
        val markdown = note.bodyMarkdown.orEmpty()
        if (SOURCES_HEADING.containsMatchIn(markdown)) return true
        // An HTML body whose heading rounded-tripped into markdown text.
        val fromHtml =
            note.bodyHtml
                ?.takeIf { it.isNotBlank() }
                ?.let { NoteContentMapper.toMarkdown(it) }
                .orEmpty()
        return SOURCES_HEADING.containsMatchIn(fromHtml)
    }
}
