package com.singularity.todo.feature.search.query

/**
 * Writes a [SimpleFilter] back into the query text language.
 *
 * A filter that has been applied *is* the query the screen runs, and a saved
 * search is made of query text — so a filter has to be sayable in the text
 * language, or a saved search cannot carry what the screen is doing.
 *
 * The text is written so that reading it again says the same thing:
 *
 * - a free-text term keeps its own words, and is quoted when a bare word
 *   would be read as something else — `pinned` is a condition, `"pinned"` is
 *   a word;
 * - a due date is written with the relation the filter has, so `due:=today`
 *   stays "on that day" and does not become "on or before that day";
 * - the sort is written the way the language reads it, last.
 *
 * Returns `null` when the filter says something the text language cannot say:
 * a custom date range, or a tag or a project whose name the language has no
 * spelling for. The caller keeps what it had rather than saving a text that
 * says something else.
 */
fun SimpleFilter.toQueryText(): String? = queryTextParts()?.joinToString(" ")

/**
 * Collects the parts of the text, and remembers whether they could all be
 * spelled.
 *
 * A part that cannot be spelled is a name the language has no form for, and a
 * date range the language has no syntax for. The whole text is then nothing —
 * half a query would say something else than the filter does.
 */
private class QueryTextBuilder {
    private val parts = mutableListOf<String>()
    private var spellable = true

    /** Adds a part, or remembers that the filter cannot be said in the language. */
    fun add(part: String?) {
        if (part == null) spellable = false else parts += part
    }

    /** The parts, or `null` when one of them could not be spelled. */
    fun build(): List<String>? = if (spellable) parts else null
}

/**
 * The parts of the text, or `null` when one of them cannot be spelled.
 */
private fun SimpleFilter.queryTextParts(): List<String>? {
    val text = QueryTextBuilder()

    // The free text first: it is what the user asked for by words, and it is
    // the part of the query the filter sheet does not own.
    freeTextTerms.forEach { text.add(it.asQueryTextTerm()) }

    states?.forEach { text.add("state:${it.wireName}") }

    priorities.forEach { text.add("priority:${it.wireName}") }

    addTags(text)

    projectName?.let { name -> text.add(name.asQueryTextName()?.let { "project:$it" }) }

    when (due) {
        SimpleFilter.DueCondition.NONE -> Unit

        // The language has no syntax for a date range.
        SimpleFilter.DueCondition.CUSTOM -> return null

        SimpleFilter.DueCondition.TODAY -> text.add("due:=today")

        SimpleFilter.DueCondition.TOMORROW -> text.add("due:=tomorrow")

        SimpleFilter.DueCondition.THIS_WEEK -> text.add("due:=7d")

        SimpleFilter.DueCondition.OVERDUE -> text.add("due:=-1d")
    }

    if (hasDescription == true) text.add("has:description")
    if (pinned == true) text.add("pinned")

    addSort(text)

    return text.build()
}

/** The tag conditions, the way the language accumulates them. */
private fun SimpleFilter.addTags(text: QueryTextBuilder) {
    if (matchAllTags && tagNames.size > 1) {
        // One condition for all of them, the way the language accumulates tags.
        val names = tagNames.map { it.asQueryTextName() }
        if (names.all { it != null }) {
            text.add("tags:${names.filterNotNull().joinToString(",")}")
        } else {
            text.add(null)
        }
    } else {
        tagNames.forEach { name -> text.add(name.asQueryTextName()?.let { "tag:$it" }) }
    }
}

/** The sort, the way the language reads it. */
private fun SimpleFilter.addSort(text: QueryTextBuilder) {
    if (sortOrder != SortOrder.DUE || sortDescending) {
        text.add("sort:${sortOrder.name.lowercase()}")
        if (sortDescending) text.add("desc")
    }
}

/**
 * The term as the language spells it.
 *
 * A term is a bare word when the language reads it as free text: a word that
 * carries no punctuation, and that is not one of the words the language gives
 * a meaning of its own. Every other term is quoted, because a quoted string
 * is always free text.
 */
private fun String.asQueryTextTerm(): String? =
    when {
        isEmpty() -> null
        isPlainQueryWord() -> this
        else -> "\"${escapeForQueryText()}\""
    }

/**
 * The name as the language spells it.
 *
 * A name is read as one word, so it cannot hold whitespace or a parenthesis.
 * A name the language cannot spell has no text — and a filter that holds one
 * cannot be said in the language at all.
 */
private fun String.asQueryTextName(): String? = if (NAME_PATTERN.matches(this)) this else null

/** A word the language reads as free text. */
private fun String.isPlainQueryWord(): Boolean = PLAIN_WORD_PATTERN.matches(this) && lowercase() !in RESERVED_WORDS

/** The escapes the language resolves inside a quoted string. */
private fun String.escapeForQueryText(): String = replace("\\", "\\\\").replace("\"", "\\\"")

private val PLAIN_WORD_PATTERN = Regex("^[A-Za-z0-9_]+$")

private val NAME_PATTERN = Regex("^[^\\s()]+$")

/**
 * The words the language gives a meaning of its own.
 *
 * `and`, `or` and `not` are operators, `desc` is the sort flag, and `pinned`
 * and `archived` are conditions. A term that is one of them has to be quoted
 * to stay a word.
 */
private val RESERVED_WORDS = setOf("and", "or", "not", "desc", "pinned", "archived")
