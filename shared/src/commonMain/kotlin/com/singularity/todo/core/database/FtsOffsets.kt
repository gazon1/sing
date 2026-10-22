package com.singularity.todo.core.database

import com.singularity.todo.core.search.SearchColumn
import com.singularity.todo.core.search.TextMatchRange

/**
 * An indexed column, and the text it holds.
 *
 * FTS4 counts its columns from zero, in the order the virtual table declares
 * them. A reader that wants to say "the title matched" has to know which of
 * those columns is the title — and it has to hold the text of the column,
 * because the position the index reports is a position *in that text*.
 *
 * @param column the surface the column stands for.
 * @param text the text of the column, as it is stored.
 */
data class FtsColumn(val column: SearchColumn, val text: String)

/**
 * The position of a match, as FTS4 reports it.
 *
 * ## What `offsets()` gives
 *
 * FTS4 can say where a match is, not only that there is one. The `offsets()`
 * function answers with a flat list of integers, four per matched token:
 *
 * ```
 * SELECT offsets(tasks_fts) FROM tasks_fts WHERE tasks_fts MATCH 'arch*';
 * -- 0 0 0 12   ← column 0, term 0, byte 0, 12 bytes long
 * -- 1 0 4 12   ← column 1, term 0, byte 4, 12 bytes long
 * ```
 *
 * The four numbers are: the column the token is in, the number of the term
 * inside the phrase, the **byte** offset of the token in that column's text,
 * and the **byte** length of the token there. One group per matched token, so
 * a phrase of two words that matches in two places reports four groups.
 *
 * The length is the length of the token *in the original text* — not of the
 * folded token the index stores. A search for "cafe" finds "Café au lait" and
 * reports five bytes, because "Café" is five bytes in the text.
 *
 * ## The bytes are not the characters
 *
 * SQLite counts bytes; a Kotlin string counts UTF-16 code units. For ASCII the
 * two agree, and the difference is invisible until a title carries an accent —
 * and then a highlight lands on the wrong word, or on half of one. The
 * conversion walks the text the way SQLite wrote it: byte by byte, counting
 * the characters it passes.
 *
 * The tokenizer records the offsets at the boundaries of a token, so a
 * reported offset always lands on a character boundary.
 */
object FtsOffsets {

    /**
     * The ranges the index reported, in the characters of the column's text.
     *
     * @param raw the value of `offsets(<the index>)`.
     * @param columns the indexed columns, in the order of the index.
     * @return the ranges of every matched token, empty when the index
     *   reported nothing.
     */
    fun ranges(raw: String?, columns: List<FtsColumn>): List<TextMatchRange> {
        val numbers = parse(raw)
        val ranges = mutableListOf<TextMatchRange>()
        var index = 0
        while (index + 3 < numbers.size) {
            val column = columns.getOrNull(numbers[index])
            val byteOffset = numbers[index + 2]
            val byteLength = numbers[index + 3]
            index += 4
            if (column == null) continue
            val start = characterIndexAt(column.text, byteOffset)
            val end = characterIndexAt(column.text, byteOffset + byteLength)
            // A range that came out empty is a range the text cannot show —
            // the offsets ran past the end of the column. It is dropped rather
            // than drawn as an empty span.
            if (end > start) ranges += TextMatchRange(column.column, start, end)
        }
        return ranges
    }

    /**
     * The character index at which [byteOffset] lands in [text].
     *
     * The answer is an index into the same string a Compose `AnnotatedString`
     * is built from, so it counts UTF-16 code units: a character outside the
     * Basic Multilingual Plane — an emoji — is two of them.
     *
     * @param text the text the offset was measured in.
     * @param byteOffset the offset, in UTF-8 bytes.
     */
    fun characterIndexAt(text: String, byteOffset: Int): Int {
        if (byteOffset <= 0) return 0
        val bytes = text.encodeToByteArray()
        var byteIndex = 0
        var charIndex = 0
        while (byteIndex < bytes.size && byteIndex < byteOffset) {
            val length = sequenceLength(bytes[byteIndex].toInt() and 0xFF)
            byteIndex += length
            charIndex += if (length == FOUR_BYTE_SEQUENCE) 2 else 1
        }
        return charIndex
    }

    /**
     * The numbers of the `offsets()` answer.
     *
     * A value that is not a list of numbers is not an answer — and a range
     * read out of half a group is worse than no range at all.
     */
    private fun parse(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(' ', '\t', '\n')
            .filter { it.isNotBlank() }
            .mapNotNull { it.toIntOrNull() }
    }

    /** The length of the UTF-8 sequence that starts with [firstByte]. */
    private fun sequenceLength(firstByte: Int): Int = when {
        firstByte and 0x80 == 0 -> 1
        firstByte and 0xE0 == 0xC0 -> 2
        firstByte and 0xF0 == 0xE0 -> 3
        firstByte and 0xF8 == 0xF0 -> FOUR_BYTE_SEQUENCE
        else -> 1
    }

    /** A UTF-8 sequence of four bytes is one code point and two UTF-16 units. */
    private const val FOUR_BYTE_SEQUENCE = 4
}
