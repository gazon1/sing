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
     * The word a brief's provenance lives under.
     *
     * The heading itself is written as Markdown by the agent, as HTML by the editor and
     * as whatever the rich-text round trip produces — so the check is on the word, not
     * on one markup for it. A note that says "Sources" as a section is one; a note that
     * merely mentions the word in prose is a note, and the extraction writes a heading.
     */
    private const val SOURCES_HEADING = "Sources"

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
        // Both representations are read, because neither is guaranteed to survive the
        // other: a proposal writes HTML, the editor keeps markdown next to it, and the
        // heading may render as `## Sources` in one form and as an HTML heading in the
        // other. Asking only one would make a note a brief or not depending on which
        // field happened to be filled.
        val markdown = note.bodyMarkdown.orEmpty()
        val fromHtml =
            note.bodyHtml
                ?.takeIf { it.isNotBlank() }
                ?.let { NoteContentMapper.toMarkdown(it) }
                .orEmpty()
        val text = markdown + "\n" + fromHtml
        return text.contains(SOURCES_HEADING)
    }
}
