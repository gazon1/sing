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
 * The section is a **label whose text is exactly `Sources`**:
 *
 * - HTML — the text `Sources` inside a heading element (`<h2>Sources</h2>`) or
 *   inside a bold element (`<strong>Sources</strong>`).
 * - Markdown — a line that is a heading `Sources` (`## Sources`, at any level) or
 *   the bold form `**Sources**`.
 *
 * A note whose prose merely mentions sources carries none of them, so it stays an
 * ordinary note. The rule is not a heading parser — SQLite has no regular
 * expressions, and a rule the statement cannot evaluate is a rule the statement
 * cannot use. It looks for the marker's text instead, anchored so that it can only
 * match a label: the HTML text sits between a `>` and a whole closing tag, and the
 * Markdown text has to be a line of its own — `**Sources**` in the middle of a
 * sentence is prose, while a line of its own is a label.
 *
 * ## The word has to be one word
 *
 * The text is matched after the body is **stripped of its whitespace**, so the
 * whitespace around the label is padding of any length: `<h2> Sources </h2>` and a
 * heading with a thousand spaces in it are the same heading, and there is no run of
 * spaces the statement cannot fold.
 *
 * Stripping alone would answer the question wrongly — `<h2>Source s</h2>` stripped
 * is `Sources` — so the word is **marked before the body is stripped**: the word
 * that is already one word is pinned with a mark, and the mark is what the rule
 * looks for. A body whose `Sources` is split by a space carries no mark and is
 * prose.
 *
 * The Markdown body keeps its line feeds, because a line is what makes a label a
 * label there; only the whitespace *inside* a line is dropped. The HTML body has
 * no line structure the rule needs, so all of its whitespace goes.
 *
 * The marking is what keeps the two forms of the rule the same rule: the statement
 * and the code run the same steps, in the same order.
 */
object KnowledgeArticle {
    /**
     * The word that says what a note is.
     *
     * It is spelled once, and the statement's SQL spells it from this constant, so
     * the two forms of the rule cannot drift apart on the word itself.
     */
    private const val WORD = "sources"

    /**
     * The mark that pins the word in place while the body is stripped.
     *
     * It is a control character: the body of a note is text, and a text mark would
     * collide with it.
     */
    private const val MARK = "\u0001"

    /** The marker as the HTML body carries it, after the body is stripped. */
    private const val HTML_MARKER = ">" + MARK + "</b>"

    /** The marker as a Markdown heading carries it, after the body is stripped. */
    private const val MARKDOWN_HEADING_MARKER = "#" + MARK

    /** The marker as a Markdown bold line carries it, after the body is stripped. */
    private const val MARKDOWN_MARKER = "**" + MARK + "**"

    /**
     * The HTML body, marked and stripped so that the marker can be looked for in it.
     *
     * The steps, in order: lower case; the word is marked; the whitespace is
     * stripped; the closing tags of a heading and of a bold element both become
     * `</b>`.
     */
    private const val SQL_HTML_BODY: String =
        "replace(replace(replace(replace(replace(replace(" +
            "replace(replace(replace(replace(replace(replace(" +
            "lower(COALESCE(body_html, '')), " +
            "'" + WORD + "', char(1)), " +
            "' ', ''), char(9), ''), char(10), ''), char(13), ''), " +
            "'</strong>', '</b>'), " +
            "'</h1>', '</b>'), '</h2>', '</b>'), '</h3>', '</b>'), " +
            "'</h4>', '</b>'), '</h5>', '</b>'), '</h6>', '</b>')"

    /**
     * The Markdown body, marked and stripped so that the marker can be looked for
     * in it, and padded with line feeds so that a line at either end is a line like
     * any other.
     *
     * The steps, in order: lower case; the word is marked; the whitespace inside a
     * line is stripped, the line feed is kept; the body is padded with line feeds;
     * the tabs and the carriage returns are stripped; a heading mark alone takes the
     * line below it, which is the heading whose text sits on the next line; the
     * heading runs collapse to one.
     */
    private const val SQL_MARKDOWN_BODY: String =
        "replace(replace(replace(replace(replace(replace(replace(replace(" +
            "char(10) || replace(replace(lower(COALESCE(body_markdown, '')), " +
            "'" + WORD + "', char(1)), ' ', '') || char(10), " +
            "char(9), ''), char(13), ''), " +
            "char(10) || '#' || char(10), char(10) || '#'), " +
            "'######', '#'), '#####', '#'), '####', '#'), '###', '#'), '##', '#')"

    /**
     * The classification as the statement states it.
     *
     * The predicate names the notes table's own columns, because it is used inside a
     * query over that table. It is the same rule as [carriesSources], in the dialect
     * SQLite speaks, and it runs the same steps in the same order:
     *
     * - the HTML body is lowercased, the word is marked, its whitespace is stripped,
     *   and its closing tags are made alike — so the marker is one string;
     * - the Markdown body is lowercased and marked, the whitespace inside a line is
     *   stripped, a heading mark alone takes the line below it, the heading runs
     *   collapse — so the marker is a line of its own.
     */
    const val SQL_MARKER: String =
        "(instr(" + SQL_HTML_BODY + ", '>' || char(1) || '</b>') > 0" +
            " OR instr(" + SQL_MARKDOWN_BODY + ", char(10) || '#' || char(1) || char(10)) > 0" +
            " OR instr(" + SQL_MARKDOWN_BODY + ", char(10) || '**' || char(1) || '**' || char(10)) > 0)"

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
        bodyHtml?.let { html -> htmlCarriesSources(html) } == true ||
            bodyMarkdown?.let { markdown -> markdownCarriesSources(markdown) } == true

    /** Whether [note] carries the Sources section. */
    fun carriesSources(note: Note): Boolean = carriesSources(note.bodyHtml, note.bodyMarkdown)

    /**
     * Whether the HTML body carries the Sources label.
     *
     * The body is handled the way the statement handles it — lower case, the word
     * marked, the whitespace stripped, the closing tags made alike — and the marker
     * is then looked for in it.
     */
    private fun htmlCarriesSources(html: String): Boolean =
        html
            .lowercase()
            .replace(WORD, MARK)
            .withoutWhitespace()
            .replace("</strong>", "</b>")
            .replace("</h1>", "</b>")
            .replace("</h2>", "</b>")
            .replace("</h3>", "</b>")
            .replace("</h4>", "</b>")
            .replace("</h5>", "</b>")
            .replace("</h6>", "</b>")
            .contains(HTML_MARKER)

    /**
     * Whether the Markdown body carries the Sources label.
     *
     * The body is handled the way the statement handles it — lower case, the word
     * marked, the whitespace inside a line stripped, a heading mark alone taking the
     * line below it, the heading runs collapsed — and the marker is then looked for
     * as a line of its own.
     */
    private fun markdownCarriesSources(markdown: String): Boolean {
        val stripped = "\n" + markdown.lowercase().replace(WORD, MARK).withoutInlineWhitespace() + "\n"
        val absorbed = stripped.replace("\n#\n", "\n#")
        val collapsed =
            absorbed
                .replace("######", "#")
                .replace("#####", "#")
                .replace("####", "#")
                .replace("###", "#")
                .replace("##", "#")
        return collapsed.contains("\n$MARKDOWN_HEADING_MARKER\n") ||
            collapsed.contains("\n$MARKDOWN_MARKER\n")
    }

    /**
     * The body without any of its whitespace.
     *
     * The space, the tab, the line feed and the carriage return — the HTML body
     * carries no line structure the rule needs, so all of it goes.
     */
    private fun String.withoutWhitespace(): String = filterNot { it == ' ' || it == '\t' || it == '\n' || it == '\r' }

    /**
     * The body without the whitespace that sits inside a line.
     *
     * The space, the tab and the carriage return — the line feed is kept, because a
     * line is what makes a Markdown label a label.
     */
    private fun String.withoutInlineWhitespace(): String = filterNot { it == ' ' || it == '\t' || it == '\r' }

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
