package com.singularity.todo.feature.notes

/**
 * What makes a note a Knowledge article.
 *
 * ## The article is a note
 *
 * There is no article table. An article is a note attached to exactly one project or
 * task, and the attachment is the `project_id` / `task_id` column — the same column a
 * brief has used since the brief existed. One owner may own many articles; each is
 * addressed by its own note id.
 *
 * ## The marker is the Sources section
 *
 * The attachment is not the identity: a task's logbook is made of plain notes
 * attached to the task, and a project's daily note is a `Daily` note carrying the
 * same `project_id`. So the section is what says what a note is — the `## Sources`
 * heading, the one part of the brief shape that carries the provenance. A note
 * without it is an ordinary note, and an ordinary note is never an article.
 *
 * ## The rule is written twice, on purpose
 *
 * The classification has to happen **inside the statement**, before the page is
 * cut: a read that filtered after its `LIMIT` would hide an older article behind
 * newer ordinary notes. SQL cannot ask a Kotlin object whether a body carries the
 * heading, so the rule is written twice — [SQL_MARKER] for the statement and
 * [carriesSources] for the code that holds a note in memory — and the two are held
 * to each other by a test that runs both over the same bodies.
 *
 * ## What the rule accepts
 *
 * The section is a **heading whose text is exactly `Sources`**:
 *
 * - HTML — the text `Sources` between a `>` and a closing heading tag
 *   (`<h2>Sources</h2>`). The heading may carry attributes: the rule looks at the
 *   text and at the closing tag, not at the opening one.
 * - Markdown — a line that is a heading `Sources` (`## Sources`, at any level) or
 *   the bold form `**Sources**`.
 *
 * A line is a heading because it starts with `#`; the rule accepts a run of them, so
 * every level is one spelling. The body is matched with the spaces and tabs taken
 * out, so `##Sources` and `## Sources` are the same heading.
 *
 * A note whose prose merely mentions sources carries none of them, so it stays an
 * ordinary note. The rule is not a heading parser — SQLite has no regular
 * expressions, and a rule the statement cannot evaluate is a rule the statement
 * cannot use. It looks for the marker's text instead, anchored so that it can only
 * match a heading: the HTML text sits between a `>` and a closing heading tag, and
 * the Markdown text has to be a line of its own — `**Sources**` in the middle of a
 * sentence is prose, while a line of its own is a heading.
 */
object KnowledgeArticle {
    /**
     * The Sources heading as an HTML heading: the text `Sources` between a `>` and a
     * closing heading tag.
     *
     * Lower case, because the HTML the editor writes is matched case-insensitively —
     * the same way the Kotlin side lowercases the body before looking.
     */
    private const val HTML_MARKER = ">sources</h"

    /** The Sources heading as a Markdown heading, with the hashes collapsed to one. */
    private const val MARKDOWN_HEADING_MARKER = "#Sources"

    /** The Sources heading as bold Markdown. */
    private const val MARKDOWN_BOLD_MARKER = "**Sources**"

    /**
     * A run of two or more hashes — a heading of any level — collapsed to one.
     *
     * The statement cannot ask "does this line start with hashes and then say
     * Sources", so it collapses the run and asks for the marker instead.
     */
    private val HASH_RUN = Regex("#{2,}")

    /**
     * The classification as the statement states it.
     *
     * The predicate names the notes table's own columns, because it is used inside a
     * query over that table. It is the same rule as [carriesSources], in the dialect
     * SQLite speaks:
     *
     * - the HTML body is lowercased and stripped of whitespace, and looked for the
     *   heading text;
     * - the Markdown body is stripped of spaces, tabs and carriage returns, padded
     *   with line feeds so every line is bounded, and its heading runs are
     *   collapsed — so a line has to *be* the heading, not merely contain it.
     */
    const val SQL_MARKER: String =
        "(instr(" +
            "replace(replace(replace(replace(lower(COALESCE(body_html, '')), ' ', ''), " +
            "char(9), ''), char(10), ''), char(13), ''), " +
            "'>sources</h') > 0" +
            " OR instr(" +
            "replace(replace(replace(replace(replace(" +
            "char(10) || replace(replace(replace(COALESCE(body_markdown, ''), ' ', ''), " +
            "char(9), ''), char(13), '') || char(10), " +
            "'######', '#'), '#####', '#'), '####', '#'), '###', '#'), '##', '#'), " +
            "char(10) || '#Sources' || char(10)) > 0" +
            " OR instr(" +
            "replace(replace(replace(replace(replace(" +
            "char(10) || replace(replace(replace(COALESCE(body_markdown, ''), ' ', ''), " +
            "char(9), ''), char(13), '') || char(10), " +
            "'######', '#'), '#####', '#'), '####', '#'), '###', '#'), '##', '#'), " +
            "char(10) || '**Sources**' || char(10)) > 0)"

    /**
     * Whether the body carries the Sources section.
     *
     * The HTML body is the canonical one, but the Markdown body is checked as well:
     * a note written before the body became HTML carries only the Markdown, and a
     * brief that stops being readable because of a storage migration is a brief lost.
     */
    fun carriesSources(
        bodyHtml: String?,
        bodyMarkdown: String?,
    ): Boolean =
        bodyHtml?.let { html ->
            html.lowercase().filterNot { it.isWhitespace() }.contains(HTML_MARKER)
        } == true ||
            bodyMarkdown?.let { markdown -> markdownCarriesSources(markdown) } == true

    /** Whether [note] carries the Sources section. */
    fun carriesSources(note: Note): Boolean = carriesSources(note.bodyHtml, note.bodyMarkdown)

    /**
     * Whether a Markdown body carries the Sources heading.
     *
     * The body is stripped of spaces, tabs and carriage returns, split into lines and
     * every line is compared with the marker — so the heading has to *be* a line, and
     * a mention of sources in the middle of a sentence is prose.
     */
    private fun markdownCarriesSources(markdown: String): Boolean =
        markdown
            .replace(" ", "")
            .replace("\t", "")
            .replace("\r", "")
            .split("\n")
            .any { line ->
                val collapsed = HASH_RUN.replace(line, "#")
                collapsed == MARKDOWN_HEADING_MARKER || collapsed == MARKDOWN_BOLD_MARKER
            }

    /**
     * Whether [note] is a Knowledge article.
     *
     * An article is a plain, non-folder note attached to an owner, carrying the
     * Sources section. The archive and deletion filters are not here: they belong to
     * the statement that reads the articles, which applies them before the page is
     * cut.
     */
    fun isArticle(note: Note): Boolean =
        !note.isFolder &&
            note.kind == NoteKind.Plain &&
            (note.projectId != null || note.taskId != null) &&
            carriesSources(note)
}
