package com.singularity.todo.core.search

import org.junit.jupiter.api.Tag
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The diacritic folding — the one the full-text index does.
 *
 * The table is the tokenizer's own: every character was put into a real FTS4
 * table and the base letter was read back from the index that answered for it.
 * What this test holds is the property the table has to have for the matcher to
 * be able to answer the same question the index answers.
 */
@Tag("fast")
class DiacriticFoldTest {

    @Test
    fun `a letter with a diacritic folds to its base letter`() {
        assertEquals('e', DiacriticFold.fold('é'))
        assertEquals('e', DiacriticFold.fold('É'))
        assertEquals('e', DiacriticFold.fold('è'))
        assertEquals('a', DiacriticFold.fold('à'))
        assertEquals('n', DiacriticFold.fold('ñ'))
        assertEquals('u', DiacriticFold.fold('ü'))
    }

    @Test
    fun `a letter without a single base letter is left alone`() {
        // The index folds a character to one letter or not at all: "ß" is not
        // "ss", "æ" is not "ae", and "ø" is not "o".
        assertEquals('ß', DiacriticFold.fold('ß'))
        assertEquals('æ', DiacriticFold.fold('æ'))
        assertEquals('ø', DiacriticFold.fold('ø'))
        assertEquals('ł', DiacriticFold.fold('ł'))
    }

    @Test
    fun `a letter without a diacritic is left alone`() {
        assertEquals('a', DiacriticFold.fold('a'))
        assertEquals('Z', DiacriticFold.fold('Z'))
        assertEquals('7', DiacriticFold.fold('7'))
    }

    @Test
    fun `the fold does not change the length of the text`() {
        // The index reports where it found the word, and those positions are
        // positions in the original text. A fold that changed the length would
        // move every position after it.
        val text = "Café au lait, s'il vous plaît"

        assertEquals(text.length, DiacriticFold.fold(text).length)
    }

    @Test
    fun `a letter outside the folded blocks is left alone`() {
        // The Cyrillic and the Greek are not folded by the index, so the rule
        // must not fold them either.
        assertEquals('К', DiacriticFold.fold('К'))
        assertEquals('я', DiacriticFold.fold('я'))
    }
}
