package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.search.TextMatch
import com.singularity.todo.core.search.TextMatchQuery
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Tag
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Kotlin matcher and the real index answer the same question.
 *
 * ## The gap this closes
 *
 * The search has two answers to one question. The index answers "which records
 * carry this word" from a dictionary of words; the rule in memory answers "which
 * records match this term" by tokenising the text in Kotlin. The two
 * implementations were written separately, and nothing compared them.
 *
 * The two ways they can disagree are not symmetric:
 *
 * - **The index finds less than the rule** — a record is silently lost. The
 *   index produced the candidates, the rule accepted one, and the search did
 *   not return it. Nothing reports this: the user sees fewer results and
 *   concludes the record is gone.
 * - **The index finds more than the rule** — the search returns a record the
 *   rule would have rejected.
 *
 * The invariant this test holds is the one that matters: **every record the
 * rule accepts is among the records the index returns.** A search is then
 * correct whatever the tokenizer does, because the index answers the positive
 * terms — the rule no longer asks the index's question a second time.
 *
 * ## Why a real SQLite
 *
 * The fakes cannot say anything about this. The whole question is what SQLite's
 * `unicode61` tokenizer does with a character the Kotlin matcher has never
 * seen, and the only honest answer comes from SQLite itself. A test against the
 * fakes would compare the matcher with itself.
 *
 * ## What the corpus found
 *
 * `İ` (U+0130, Latin capital letter I with dot above) is the one that came
 * out. Kotlin's `String.lowercase()` maps it to two characters — `i` and a
 * combining dot — and the combining dot is not a letter, so the Kotlin
 * tokenizer split "İstanbul" into two tokens while SQLite folded it into one.
 * A search for "stanbul" then matched the rule and not the index: a record
 * lost, silently. The matcher folds per character now, which is what
 * `unicode61` does, and the corpus is what keeps it that way.
 *
 * The other difference the corpus found was the diacritics: the index removed
 * them and the matcher did not, so "cafe" found "café latte" in the index and
 * not in the matcher. The matcher folds them now, the way the index does, and
 * the difference is asserted on its own below.
 */
@Tag("slow")
@Tag("slow:data")
class FtsMatcherAgreementTest {

    private lateinit var tempDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-fts-agreement-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    /**
     * The texts the corpus is built from.
     *
     * Between them they hold the cases the two tokenizers could plausibly
     * disagree about: a word that merely contains a term, a word that is
     * written in another script, an underscore between two words, a digit
     * that starts a word, a letter with a dot above it, a letter with an
     * accent, and a letter that has no upper-case form of its own.
     */
    private val texts = listOf(
        "architecture" to "t-architecture",
        "airport" to "t-airport",
        "Write the quarterly report" to "t-quarterly",
        "quarterly annual report" to "t-quarterly-apart",
        "Купить молоко" to "t-milk",
        "snake_case identifier" to "t-snake",
        "version 2 release" to "t-version",
        "a_b" to "t-underscore",
        "x_1" to "t-digit",
        "İstanbul plan" to "t-istanbul",
        "notebook" to "t-notebook",
        "straße name" to "t-strasse",
        "café latte" to "t-cafe",
    )

    /**
     * The terms the corpus asks about.
     *
     * A term is either one word (the index and the rule have to agree exactly)
     * or several (a quoted phrase — the index asks for the phrase, and the two
     * have to agree exactly there too).
     */
    private val singleWordTerms = listOf(
        "arch",
        "port",
        "quarter",
        "report",
        "write",
        "мол",
        "Куп",
        "snake",
        "case",
        "identifier",
        "version",
        "2",
        "release",
        "a",
        "b",
        "istanbul",
        "stanbul",
        "café",
        "cafe",
        "straße",
        "strasse",
        // "cafe" is here because the index and the matcher fold the diacritics
        // the same way: both find "café latte" for it. The fold is asserted on
        // its own below.
        // A word the query language would read as an operator. The index is
        // asked for it through a phrase, so it is a word and not an operator.
        "not",
        "or",
        "and",
    )

    /** Terms of more than one word — a quoted phrase in the query language. */
    private val phraseTerms = listOf(
        "quarterly report",
        "snake case",
        "a b",
    )

    @Test
    fun `the index never throws away a record the rule accepts`() = runTest {
        seed()

        for (term in singleWordTerms + phraseTerms) {
            val byRule = texts.filter { (text, _) -> TextMatch.matches(text, term) }.map { it.second }
            val byIndex = indexIds(term)

            val lost = byRule - byIndex.toSet()
            assertTrue(
                lost.isEmpty(),
                "the rule accepts $lost for the term \"$term\", and the index does not return " +
                    "them. The index answers the positive terms, so a record the rule accepts " +
                    "and the index does not offer is a record the search silently loses.",
            )
        }
    }

    @Test
    fun `the index and the rule answer a single word the same way`() = runTest {
        seed()

        for (term in singleWordTerms) {
            val byRule = texts.filter { (text, _) -> TextMatch.matches(text, term) }.map { it.second }
            val byIndex = indexIds(term)

            assertEquals(
                byRule.sorted(),
                byIndex.sorted(),
                "the index and the rule disagree about the term \"$term\". A record the index " +
                    "returns and the rule rejects is a candidate filtered out — harmless — " +
                    "but a record the rule accepts and the index does not offer is a record " +
                    "the search loses.",
            )
        }
    }

    @Test
    fun `the index and the rule answer a phrase the same way`() = runTest {
        seed()

        for (term in phraseTerms) {
            val byRule = texts.filter { (text, _) -> TextMatch.matches(text, term) }.map { it.second }
            val byIndex = indexIds(term)

            assertEquals(
                byRule.sorted(),
                byIndex.sorted(),
                "the index and the rule disagree about the phrase \"$term\". The rule asks for " +
                    "the words next to each other, and so must the index: an index that asks " +
                    "for the words anywhere offers candidates the rule throws away.",
            )
        }
    }

    @Test
    fun `a phrase does not match the same words further apart`() = runTest {
        seed()

        // "quarterly report" is a phrase: the words stand next to each other.
        // "Write the quarterly annual report" carries both words and is not a
        // match — and the index used to say it was, because it asked for the two
        // words as a conjunction.
        val found = indexIds("quarterly report")

        assertEquals(
            listOf("t-quarterly"),
            found.sorted(),
            "the index must ask for the phrase and not for the two words apart",
        )
    }

    @Test
    fun `the index and the rule fold the diacritics the same way`() = runTest {
        seed()

        // The index folds "café" to "cafe" and finds the record for the term
        // "cafe". The rule folds it the same way, so the two halves of one rule
        // answer the same question the same way: "cafe" finds the record, and
        // "NOT cafe" rules out exactly the records "cafe" finds.
        val byIndex = indexIds("cafe")

        assertTrue(
            byIndex.contains("t-cafe"),
            "the index removes the diacritics, so it finds the record for the term " +
                "\"cafe\". The index says: $byIndex",
        )
        assertTrue(
            TextMatch.matches("café latte", "cafe"),
            "the rule folds the diacritics the way the index folds them, so the term " +
                "\"cafe\" matches \"café latte\". If this ever becomes false, the two " +
                "halves of a rule answer the same question two ways.",
        )
        assertTrue(
            TextMatch.matches("Café au lait", "cafe"),
            "the fold does not depend on the case of the text either.",
        )
    }

    @Test
    fun `a term that is a keyword of the query language is a word and not an operator`() = runTest {
        seed()

        val found = indexIds("not")

        assertTrue(
            found.isNotEmpty(),
            "a term the query language would read as a negation must reach the index as a " +
                "word. The expression is built from quoted phrases, so it is.",
        )
    }

    @Test
    fun `a term with no letters matches nothing at all`() = runTest {
        seed()

        assertTrue(
            indexIds("!!!").isEmpty(),
            "a term that cannot be a word cannot match a word",
        )
    }

    // ── the corpus ──────────────────────────────────────────────────────────

    private suspend fun seed() {
        texts.forEach { (text, id) ->
            db.taskDao().upsert(
                TaskEntity(
                    id = id,
                    title = text,
                    description = null,
                    projectId = null,
                    dueDate = null,
                    dueTime = null,
                    startDate = null,
                    startTime = null,
                    endDate = null,
                    endTime = null,
                    accentColor = null,
                    emoji = null,
                    completedAt = null,
                    archivedAt = null,
                    createdAt = 1_000L,
                    updatedAt = 1_000L,
                    userId = "u1",
                ),
            )
        }
    }

    /**
     * The contains mode loses nothing the index found.
     *
     * The corpus above compares the *word* rule with the index — the strict
     * rule, and the rule the index answers. The contains mode is the other
     * rule: the term matches wherever it occurs. It is the weaker rule, so
     * everything the index finds, it has to find as well — and that is worth
     * asserting against the real index rather than against the rule alone,
     * because the two rules fold the diacritics differently when nobody
     * watches: "cafe" found "café latte" in the index and not in the matcher,
     * and a search that lost a record because of a fold nobody tested is a
     * record that is simply gone.
     *
     * The contains mode answers more than the index — "port" finds "airport"
     * — and that is the escape hatch working, not a disagreement. So the
     * property is one-sided: the index's records are a subset of the contains
     * mode's.
     *
     * The property holds for the single words, and the phrases are left out
     * on purpose: a phrase is a question about tokens that stand next to
     * each other, while the contains mode asks whether the phrase occurs
     * *in a row* in the text. "snake_case identifier" holds the tokens
     * "snake" and "case" next to each other, and it does not hold the words
     * "snake case" in a row — two different questions, and the corpus says
     * so rather than pretending they are one.
     */
    @Test
    fun `the contains mode loses nothing the index found`() = runTest {
        seed()

        for (term in singleWordTerms) {
            val byIndex = indexIds(term)
            val byContains = texts
                .filter { (text, _) -> TextMatch.contains(text, term) }
                .map { it.second }

            val lost = byIndex - byContains.toSet()
            assertTrue(
                lost.isEmpty(),
                "the index returns $lost for the term \"$term\" and the contains mode does not. " +
                    "The contains mode is the weaker rule — it matches wherever the term occurs — " +
                    "so a record the index finds and it does not is a record the search loses.",
            )
        }
    }

    /**
     * The contains mode folds the diacritics the way the index folds them.
     *
     * The corpus folds the diacritics per character, the way `unicode61`
     * does; the contains mode folds the same way, or a search in the
     * contains mode would answer about a different text than the index does.
     *
     * The index is asked too: the claim is about the two folding *the same
     * way*, and a test that asks only the contains mode proves half of it.
     */
    @Test
    fun `the contains mode folds the diacritics the way the index folds them`() = runTest {
        seed()

        assertTrue(
            indexIds("cafe").contains("t-cafe"),
            "the index folds the diacritics: \"cafe\" finds \"café latte\"",
        )
        assertTrue(
            texts.filter { (text, _) -> TextMatch.contains(text, "cafe") }.any { it.second == "t-cafe" },
            "the contains mode folds the diacritics the same way the index does",
        )
        assertTrue(TextMatch.contains("Café Latte", "CAFE"))
        assertFalse(TextMatch.contains("café latte", "caffe"))
        assertTrue(
            TextMatch.contains("İstanbul plan", "istanbul"),
            "the index folds İ into i, and so does the contains mode — one character in, one out",
        )
        assertFalse(TextMatch.contains("straße name", "strasse"))
    }

    private suspend fun indexIds(term: String): List<String> =
        db.ftsDao().watchTasks("u1", TextMatchQuery(listOf(term))).first().map { it.task.id }
}
