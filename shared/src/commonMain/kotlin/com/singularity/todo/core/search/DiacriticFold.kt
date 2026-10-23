package com.singularity.todo.core.search

/**
 * The diacritic folding of SQLite's `unicode61` tokenizer, in Kotlin.
 *
 * ## What this is
 *
 * The index folds a word before it stores it: "Café au lait" is indexed as
 * "cafe au lait", so a search for "cafe" finds it. The rule in memory has to
 * fold the same way, or the two halves of one rule answer the same question
 * two ways — and the way a person notices is a query that contradicts itself:
 * "cafe" finds the record, and "NOT cafe" does not rule it out.
 *
 * ## Why a table and not a decomposition
 *
 * The obvious implementation is to decompose the text and drop the combining
 * marks. It is not the one, for two reasons.
 *
 * **The index does not decompose.** `unicode61` folds a character to a single
 * base letter through a table of its own, and the table is not the Unicode
 * decomposition: "æ" is not folded to "ae" and "ß" is not folded to "ss",
 * because `unicode61` folds a character to *one* letter or not at all. A
 * decomposition would answer questions the index answers differently.
 *
 * **The length has to hold.** The index reports where it found the word, and
 * the offsets are positions in the original text. A fold that changes the
 * length of a string moves every position after it. This one does not: every
 * folded character is replaced by exactly one character, so a token that starts
 * at position 7 in the folded text starts at position 7 in the original.
 *
 * ## Where the table comes from
 *
 * From the tokenizer itself: every character in the Latin-1 Supplement, Latin
 * Extended-A, Latin Extended-B and Latin Extended Additional blocks was put
 * into a real FTS4 table with `tokenize=unicode61`, and the base letter was
 * read back from the index that answered for it. Nothing here is a guess about
 * what `unicode61` probably does.
 *
 * The arbiter is the corpus in `FtsMatcherAgreementTest`: it runs the matcher
 * and a real SQLite over the same texts and terms, and fails when the two
 * disagree. A character this table folds wrongly is a character the corpus
 * finds — on the day a term that uses it is added to the corpus.
 *
 * ## What is not folded
 *
 * The letters that have no single base letter — "æ", "œ", "ø", "đ", "ł", "ß" —
 * are left alone, because `unicode61` leaves them alone. A matcher that folded
 * them would answer a question the index answers differently, and the two
 * answers would have to disagree somewhere.
 */
internal object DiacriticFold {

    /**
     * The folding of every character `unicode61` folds, as ranges.
     *
     * Each pair is the range of the characters and the base letter they fold
     * to. The characters outside these ranges are not folded at all.
     */
    private val RANGES: List<Pair<IntRange, Char>> = listOf(
        // Latin-1 Supplement
        0x00C0..0x00C5 to 'a', 0x00C7..0x00C7 to 'c',
        0x00C8..0x00CB to 'e', 0x00CC..0x00CF to 'i',
        0x00D1..0x00D1 to 'n', 0x00D2..0x00D6 to 'o',
        0x00D9..0x00DC to 'u', 0x00DD..0x00DD to 'y',
        0x00E0..0x00E5 to 'a', 0x00E7..0x00E7 to 'c',
        0x00E8..0x00EB to 'e', 0x00EC..0x00EF to 'i',
        0x00F1..0x00F1 to 'n', 0x00F2..0x00F6 to 'o',
        0x00F9..0x00FC to 'u', 0x00FD..0x00FD to 'y',
        0x00FF..0x00FF to 'y',
        // Latin Extended-A
        0x0100..0x0105 to 'a', 0x0106..0x010D to 'c',
        0x010E..0x010F to 'd', 0x0112..0x011B to 'e',
        0x011C..0x0123 to 'g', 0x0124..0x0125 to 'h',
        0x0128..0x0130 to 'i', 0x0134..0x0135 to 'j',
        0x0136..0x0137 to 'k', 0x0139..0x013E to 'l',
        0x0143..0x0148 to 'n', 0x014C..0x0151 to 'o',
        0x0154..0x0159 to 'r', 0x015A..0x0161 to 's',
        0x0162..0x0165 to 't', 0x0168..0x0173 to 'u',
        0x0174..0x0175 to 'w', 0x0176..0x0178 to 'y',
        0x0179..0x017E to 'z', 0x017F..0x017F to 's',
        // Latin Extended-B and the letters around it
        0x01A0..0x01A1 to 'o', 0x01AF..0x01B0 to 'u',
        0x01CD..0x01CE to 'a', 0x01CF..0x01D0 to 'i',
        0x01D1..0x01D2 to 'o', 0x01D3..0x01D4 to 'u',
        0x01E6..0x01E7 to 'g', 0x01E8..0x01E9 to 'k',
        0x01EA..0x01EB to 'o', 0x01F0..0x01F0 to 'j',
        0x01F4..0x01F5 to 'g', 0x01F8..0x01F9 to 'n',
        0x0200..0x0203 to 'a', 0x0204..0x0207 to 'e',
        0x0208..0x020B to 'i', 0x020C..0x020F to 'o',
        0x0210..0x0213 to 'r', 0x0214..0x0217 to 'u',
        0x0218..0x0219 to 's', 0x021A..0x021B to 't',
        0x021E..0x021F to 'h', 0x0226..0x0227 to 'a',
        0x0228..0x0229 to 'e', 0x022E..0x022F to 'o',
        0x0232..0x0233 to 'y',
        // Latin Extended Additional
        0x1E00..0x1E01 to 'a', 0x1E02..0x1E07 to 'b',
        0x1E0A..0x1E13 to 'd', 0x1E18..0x1E1B to 'e',
        0x1E1E..0x1E1F to 'f', 0x1E20..0x1E21 to 'g',
        0x1E22..0x1E2B to 'h', 0x1E2C..0x1E2D to 'i',
        0x1E30..0x1E35 to 'k', 0x1E36..0x1E37 to 'l',
        0x1E3A..0x1E3D to 'l', 0x1E3E..0x1E43 to 'm',
        0x1E44..0x1E4B to 'n', 0x1E54..0x1E57 to 'p',
        0x1E58..0x1E5B to 'r', 0x1E5E..0x1E5F to 'r',
        0x1E60..0x1E63 to 's', 0x1E6A..0x1E71 to 't',
        0x1E72..0x1E77 to 'u', 0x1E7C..0x1E7F to 'v',
        0x1E80..0x1E89 to 'w', 0x1E8A..0x1E8D to 'x',
        0x1E8E..0x1E8F to 'y', 0x1E90..0x1E95 to 'z',
        0x1E96..0x1E96 to 'h', 0x1E97..0x1E97 to 't',
        0x1E98..0x1E98 to 'w', 0x1E99..0x1E99 to 'y',
        0x1E9B..0x1E9B to 's', 0x1EA0..0x1EA3 to 'a',
        0x1EB8..0x1EBD to 'e', 0x1EC8..0x1ECB to 'i',
        0x1ECC..0x1ECF to 'o', 0x1EE4..0x1EE7 to 'u',
        0x1EF2..0x1EF9 to 'y',
    )

    /** Every folded character, and the base letter it folds to. */
    private val FOLD: Map<Char, Char> = buildMap {
        for ((range, base) in RANGES) {
            for (code in range) {
                put(code.toChar(), base)
            }
        }
    }

    /**
     * The base letter [character] folds to, or the character itself.
     *
     * @param character the character to fold.
     */
    fun fold(character: Char): Char = FOLD[character] ?: character

    /**
     * The text with every character folded to its base letter.
     *
     * The length of the text does not change: a folded character is replaced
     * by exactly one character, so a position in the folded text is the same
     * position in the original.
     *
     * @param text the text to fold.
     */
    fun fold(text: String): String =
        buildString(text.length) {
            text.forEach { character -> append(fold(character)) }
        }
}
