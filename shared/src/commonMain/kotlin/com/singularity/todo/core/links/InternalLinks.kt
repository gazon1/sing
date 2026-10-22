package com.singularity.todo.core.links

/**
 * The app's internal link vocabulary.
 *
 * An internal link is a reference from one note or task to another, written as
 * `scheme://id`. This object is the only place that names the schemes the app owns
 * and the only place that says what an identifier may look like.
 *
 * ## Why this lives in `core` and not in a feature
 *
 * Three features read and write these links: notes (the editor produces them, the
 * body stores them), tasks (a description may contain one), and the navigation layer
 * (a tap on one opens the other). The knowledge was spread over five files in two
 * features, with two copies of the extractor and two types for the same concept.
 * A vocabulary that three features depend on cannot live in any of them.
 *
 * @see InternalLinks — how a link is built and read.
 */
object InternalLinkSchemes {

    /** Scheme of a link that points at a note. */
    const val NOTE = "note"

    /** Scheme of a link that points at a task. */
    const val TASK = "task"

    /** The separator between a scheme and an identifier. */
    const val SEPARATOR = "://"

    /**
     * The one rule for what an identifier may contain: letters, digits, a hyphen
     * and an underscore.
     *
     * Identifiers are generated as UUIDs today, which are a subset of this. The
     * rule is stated as a rule because the *readers* of a link — the queries that
     * find backlinks, the handlers that open a tap — need to agree on it, and
     * "it happens to be a UUID" is not something a reader can rely on.
     */
    const val ID_PATTERN = "[A-Za-z0-9_-]+"

    /** `note://` — the prefix of a note link. */
    const val NOTE_PREFIX = NOTE + SEPARATOR

    /** `task://` — the prefix of a task link. */
    const val TASK_PREFIX = TASK + SEPARATOR

    /** Every scheme the app owns, in a stable order. */
    val ALL: List<String> = listOf(NOTE, TASK)
}

/**
 * A link the app owns: the scheme it uses and the identifier it points at.
 *
 * This is the app's single link-reference type. It replaces two identical types that
 * differed only in which package they were declared in.
 *
 * @property scheme one of [InternalLinkSchemes.ALL].
 * @property id the identifier of the target.
 */
data class InternalLinkTarget(val scheme: String, val id: String) {

    /** The `scheme://` prefix this target belongs to. */
    val prefix: String get() = scheme + InternalLinkSchemes.SEPARATOR

    /** The full URL of this target — `scheme://id`. */
    val url: String get() = prefix + id
}

/**
 * An internal link found in a piece of text: where it is and what it points at.
 *
 * @property range the half-open range of the matched text.
 * @property target what the link points at.
 */
data class InternalLinkMatch(val range: IntRange, val target: InternalLinkTarget)

/**
 * Reads and writes the app's internal links.
 *
 * Everything that knows what a link *means* lives elsewhere — `note://` means "open
 * that note" is the navigation layer's knowledge, not this file's. What lives here is
 * the *vocabulary*: which schemes exist, what an identifier looks like, and how a
 * string is split into the two.
 *
 * @see InternalLinkSchemes — the vocabulary itself.
 */
object InternalLinks {

    private val ID_REGEX = Regex("^${InternalLinkSchemes.ID_PATTERN}$")

    /**
     * A scheme and an identifier, in that order, followed by the separator.
     *
     * The scheme is matched loosely — letters, digits, `+`, `-`, `.` — and checked
     * against [InternalLinkSchemes.ALL] afterwards. Matching it loosely is what
     * makes the word boundary work: in `x-note://id` the whole `x-note` is the
     * scheme, so the app never mistakes the tail of a foreign scheme for its own.
     */
    private const val SCHEME_SOURCE = "[A-Za-z][A-Za-z0-9+.-]*"

    /**
     * The identifier, from the shared rule.
     *
     * The lookbehind is what keeps a foreign scheme from contributing: the scheme
     * has to start where a word starts.
     */
    private val LINK_REGEX = Regex(
        "(?<![A-Za-z0-9+.-])($SCHEME_SOURCE)" +
            "${InternalLinkSchemes.SEPARATOR}(${InternalLinkSchemes.ID_PATTERN})",
    )

    /**
     * Builds the URL of a link.
     *
     * @param scheme one of [InternalLinkSchemes.ALL].
     * @param id the identifier of the target.
     */
    fun urlFor(scheme: String, id: String): String = scheme + InternalLinkSchemes.SEPARATOR + id

    /** True when [scheme] is one the app owns. */
    fun isInternalScheme(scheme: String): Boolean = scheme in InternalLinkSchemes.ALL

    /**
     * Reads a link.
     *
     * @param url the candidate URL, for example `note://abc123`.
     * @return the target, or null when the scheme is not ours or the identifier does
     *   not follow [InternalLinkSchemes.ID_PATTERN].
     */
    fun parse(url: String): InternalLinkTarget? {
        val separatorAt = url.indexOf(InternalLinkSchemes.SEPARATOR)
        if (separatorAt <= 0) return null
        val scheme = url.substring(0, separatorAt)
        if (!isInternalScheme(scheme)) return null
        val id = url.substring(separatorAt + InternalLinkSchemes.SEPARATOR.length)
        if (!ID_REGEX.matches(id)) return null
        return InternalLinkTarget(scheme, id)
    }

    /**
     * Finds every internal link in a piece of plain text.
     *
     * The links come back in the order they appear, and they never overlap: the
     * scheme and the identifier are consumed together.
     *
     * @param text the text to scan.
     */
    fun findIn(text: String): List<InternalLinkMatch> =
        LINK_REGEX.findAll(text).mapNotNull { match ->
            val target = parse(match.value) ?: return@mapNotNull null
            InternalLinkMatch(match.range, target)
        }.toList()
}
