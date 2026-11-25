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
 * The text is matched after the body is **normalized**, not after its whitespace is
 * deleted: a run of whitespace becomes one space, and the spaces that sit next to a
 * tag or a heading mark are dropped. So `<h2> Sources </h2>` and `<h2>Sources</h2>`
 * are the same heading, while `<h2>Source s</h2>` is not the heading `Sources` at
 * all — a rule that deleted the whitespace would call it one.
 *
 * The normalization is what keeps the two forms of the rule the same rule: the
 * statement and the code run the same steps, in the same order.
 */
object KnowledgeArticle {
    /**
     * The marker as the HTML body carries it, after the body is normalized.
     *
     * The closing tag is normalized too — a heading and a bold element end the same
     * way here — so the rule asks one question instead of three. The tag is matched
     * whole: a heading that ends in `</h2>` and a bold that ends in `</b>` become the
     * same `</b>`, while `</header>` and `</blockquote>` stay what they are, because
     * they are not a heading and not a bold element.
     */
    private const val HTML_MARKER = ">sources</b>"

    /** The marker as the Markdown body carries it, after the body is normalized. */
    private const val MARKDOWN_MARKER = "**sources**"

    /**
     * A run of two or more hashes — a heading of any level — collapsed to one.
     *
     * The statement cannot ask "does this line start with hashes and then say
     * Sources", so it collapses the run and asks for the marker instead.
     */
    private val HASH_RUN = Regex("#{2,}")

    /**
     * The HTML body, normalized so that the marker can be looked for in it.
     *
     * The steps, in order: lower case; tab, line feed and carriage return become
     * spaces; three passes collapse the runs of spaces; a space after a `>` and a
     * space before a `<` are dropped; the closing tags of a heading and of a bold
     * element both become `</b>`.
     */
    private const val SQL_HTML_BODY: String =
        "replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(" +
            "replace(replace(replace(replace(replace(" +
            "lower(COALESCE(body_html, '')), " +
            "char(9), ' '), char(10), ' '), char(13), ' '), " +
            "'  ', ' '), '  ', ' '), '  ', ' '), " +
            "'> ', '>'), ' <', '<'), " +
            "'</strong>', '</b>'), " +
            "'</h1>', '</b>'), '</h2>', '</b>'), '</h3>', '</b>'), " +
            "'</h4>', '</b>'), '</h5>', '</b>'), '</h6>', '</b>')"

    /**
     * The Markdown body, normalized so that the marker can be looked for in it, and
     * padded with line feeds so that a line at either end is a line like any other.
     *
     * The steps, in order: lower case; tab and carriage return become spaces; three
     * passes collapse the runs of spaces; a space next to a line feed is dropped; a
     * heading mark alone takes the line below it, which is the heading whose text
     * sits on the next line; a space after a heading mark is dropped; the heading
     * runs collapse to one; the heading form becomes the bold form.
     */
    private const val SQL_MARKDOWN_BODY: String =
        "replace(replace(replace(replace(replace(replace(replace(replace(replace(replace(" +
            "replace(replace(replace(replace(replace(" +
            "char(10) || lower(COALESCE(body_markdown, '')) || char(10), " +
            "char(9), ' '), char(13), ' '), " +
            "'  ', ' '), '  ', ' '), '  ', ' '), " +
            "char(10) || ' ', char(10)), ' ' || char(10), char(10)), " +
            "char(10) || '#' || char(10), char(10) || '#'), " +
            "'# ', '#'), " +
            "'######', '#'), '#####', '#'), '####', '#'), '###', '#'), '##', '#'), " +
            "'#sources', '" + MARKDOWN_MARKER + "')"

    /**
     * The classification as the statement states it.
     *
     * The predicate names the notes table's own columns, because it is used inside a
     * query over that table. It is the same rule as [carriesSources], in the dialect
     * SQLite speaks, and it runs the same normalization steps in the same order:
     *
     * - the HTML body is lowercased, its whitespace runs collapse to one space, the
     *   spaces next to a tag are dropped, and its closing tags are made alike — so
     *   the marker is one string;
     * - the Markdown body is lowercased and padded with line feeds, its whitespace
     *   runs collapse to one space, the spaces next to a line feed are dropped, a
     *   heading mark alone takes the line below it, the heading runs collapse, and
     *   the heading form becomes the bold form — so the marker is one string too.
     */
    const val SQL_MARKER: String =
        "(instr(" + SQL_HTML_BODY + ", '" + HTML_MARKER + "') > 0" +
            " OR instr(" + SQL_MARKDOWN_BODY + ", char(10) || '" + MARKDOWN_MARKER + "' || char(10)) > 0)"

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
     * The body is normalized the way the statement normalizes it — lower case, the
     * whitespace runs collapsed, the spaces next to a tag dropped, the closing tags
     * made alike — and the marker is then looked for in it.
     */
    private fun htmlCarriesSources(html: String): Boolean =
        html
            .lowercase()
            .replace('\t', ' ')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .collapseSpaces()
            .replace("> ", ">")
            .replace(" <", "<")
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
     * The body is normalized the way the statement normalizes it — lower case, the
     * whitespace runs collapsed, the spaces next to a line feed dropped, a heading
     * mark alone taking the line below it, the heading runs collapsed and the
     * heading form made the bold form — and the marker is then looked for as a line
     * of its own.
     */
    private fun markdownCarriesSources(markdown: String): Boolean =
        ("\n" + markdown.lowercase().replace('\t', ' ').replace('\r', ' ') + "\n")
            .collapseSpaces()
            .replace("\n ", "\n")
            .replace(" \n", "\n")
            .replace("\n#\n", "\n#")
            .replace("# ", "#")
            .replace(HASH_RUN, "#")
            .replace("#sources", MARKDOWN_MARKER)
            .split("\n")
            .any { it == MARKDOWN_MARKER }

    /**
     * The runs of spaces collapsed to one space.
     *
     * Three passes, because the statement does three passes: a run of sixteen
     * spaces would survive two of them, and the two forms of the rule have to fold
     * the body the same way.
     */
    private fun String.collapseSpaces(): String = replace("  ", " ").replace("  ", " ").replace("  ", " ")

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
