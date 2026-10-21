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
 * - **The index finds more than the rule** — harmless. The rule has the last
 *   word, so an extra candidate is filtered out and the answer is unchanged.
 * - **The rule finds more than the index** — a record is silently lost. The
 *   index produced the candidates, the rule accepted one, and the search did
 *   not return it. Nothing reports this: the user sees fewer results and
 *   concludes the record is gone.
 *
 * So the invariant this test holds is the one that matters: **every record the
 * rule accepts is among the records the index returns.** A search is then
 * correct whatever the tokenizer does, because the rule decides among the
 * candidates — provided the candidates contain all of the answers.
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
 * The other difference the corpus records is the direction that is safe: the
 * index removes diacritics and the matcher does not, so "cafe" finds "café"
 * in the index and not in the rule. The extra candidate is filtered out, and
 * the answer is the same either way.
 */
@Tag("slow")
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
     * or several (the index asks for every word as a prefix and does not insist
     * on their order, so the index is expected to find more — the rule is
     * stricter, and the invariant still holds).
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
        "straße",
        "strasse",
        // "cafe" is deliberately not here: the index removes the diacritics and
        // finds "café latte" for it, and the matcher does not. That difference
        // is asserted on its own below — and it is in the safe direction, so
        // the term cannot lose a record.
        // A word the query language would read as an operator. The index is
        // asked for it through a prefix, so it is a term and not a negation.
        "not",
        "or",
        "and",
    )

    /** Terms of more than one word — the index is a superset here, by construction. */
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
                    "them. A record the rule accepts and the index does not offer is a record " +
                    "the search silently loses.",
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
    fun `the rule applied to the candidates answers the same as the rule applied to every row`() =
        runTest {
            seed()

            for (term in singleWordTerms + phraseTerms) {
                val byRule = texts.filter { (text, _) -> TextMatch.matches(text, term) }.map { it.second }
                val byIndex = indexIds(term)
                val answered = byIndex.filter { id ->
                    val text = texts.first { it.second == id }.first
                    TextMatch.matches(text, term)
                }

                assertEquals(
                    byRule.sorted(),
                    answered.sorted(),
                    "the search answers differently depending on whether the rows were read " +
                        "through the index or read one by one, for the term \"$term\"",
                )
            }
        }

    @Test
    fun `the index removes the diacritics and the rule does not, and that is the safe way round`() =
        runTest {
            seed()

            // The index folds "café" to "cafe" and finds the record for the term
            // "cafe". The rule keeps the accent and does not. The difference is
            // in the safe direction: the index offers a candidate the rule
            // rejects, and the search answers the same either way.
            val byIndex = indexIds("cafe")

            assertTrue(
                byIndex.contains("t-cafe"),
                "the index removes the diacritics, so it finds the record for the term " +
                    "\"cafe\". The index says: $byIndex",
            )
            assertTrue(
                !TextMatch.matches("café latte", "cafe"),
                "the rule keeps the accent, so it does not match the term \"cafe\" against " +
                    "\"café\". If this ever becomes true, the two agree about this case " +
                    "too, and it can move into the group that has to answer identically.",
            )
        }

    @Test
    fun `a term that is a keyword of the query language is a word and not an operator`() = runTest {
        seed()

        val found = indexIds("not")

        assertTrue(
            found.isNotEmpty(),
            "a term the query language would read as a negation must reach the index as a " +
                "word. The expression is built from prefixed tokens, so it is.",
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

    private suspend fun indexIds(term: String): List<String> =
        db.ftsDao().watchTasks("u1", TextMatchQuery(listOf(term))).first().map { it.id }
}
