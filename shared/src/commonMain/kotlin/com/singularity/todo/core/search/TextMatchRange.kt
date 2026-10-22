package com.singularity.todo.core.search

/**
 * The surface of a record the index found the word in.
 *
 * The searchable text of a kind is a choice of surfaces — a task is searched by
 * its title and its description, a note by its title, a project and a tag by
 * their name (F3 of the search grammar). The index says which surface matched,
 * and a screen that draws one of them knows which answer it is waiting for.
 */
enum class SearchColumn {

    /** The title of a task or a note. */
    TITLE,

    /** The description of a task. */
    DESCRIPTION,

    /** The name of a project or a tag. */
    NAME,
}

/**
 * Where in a record's searchable text the index found the word.
 *
 * The range is measured in **characters** — the units a Kotlin string is
 * indexed by — and the end is exclusive, so `text.substring(start, end)` is the
 * matched text.
 *
 * The index reports the position in *bytes*: SQLite counts the bytes of the
 * UTF-8 text, and a Kotlin string counts UTF-16 code units. The two differ for
 * every character outside ASCII, so the conversion is not a detail of the
 * reader — it is the reader's job, and it is where a highlight silently lands
 * on the wrong word. See `FtsOffsets`.
 *
 * @param column the surface of the record the match is in.
 * @param start the first character of the match.
 * @param end the character after the last one of the match.
 */
data class TextMatchRange(val column: SearchColumn, val start: Int, val end: Int) {

    /** The length of the match, in characters. */
    val length: Int get() = end - start
}

/**
 * The ranges that fall in [column].
 *
 * A record is searched by more than one surface, and a screen draws one of
 * them. The screen asks for the ranges of the text it is about to draw, so a
 * record that matched in its description is not highlighted in its title.
 */
fun List<TextMatchRange>.inColumn(column: SearchColumn): List<TextMatchRange> = filter { it.column == column }
