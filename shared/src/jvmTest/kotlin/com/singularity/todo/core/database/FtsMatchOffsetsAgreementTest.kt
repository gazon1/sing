package com.singularity.todo.core.database

import com.singularity.todo.core.database.contract.createSqlDriver
import com.singularity.todo.core.search.SearchColumn
import com.singularity.todo.core.search.TextMatch
import com.singularity.todo.core.search.TextMatchQuery
import com.singularity.todo.core.search.TextMatchRange
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
import kotlin.test.fail

/**
 * The range the contains mode computes and the range the index reports name the
 * same characters.
 *
 * ## The two answers to one question
 *
 * A record found by the index carries the position the index reported. A record
 * found by reading the rows carries the position [TextMatch.containsSpan]
 * computed in Kotlin, by folding the text and the term the way the tokenizer
 * folds them. The fold replaces every character with exactly one character, so a
 * position in the folded text is the position in the original one — and nothing
 * asserted that. The corpus in [FtsMatcherAgreementTest] compares the two
 * matchers' yes-or-no answer; it says nothing about *where* the answer is.
 *
 * A fold that stopped being length-preserving would not change the yes-or-no
 * answer at all: "cafe" would still find "Café au lait". It would move the span,
 * and the emphasis would land on the wrong characters — which is exactly the
 * defect [FtsMatchOffsetsTest] exists for, arriving through the other door.
 *
 * ## Why the position is read back out of the text
 *
 * The test does not compare two pairs of numbers. It compares the characters the
 * two answers cover: the rule's span is turned back into a substring, and the
 * substring is asserted against the text the user sees. A position that drifted
 * would still produce *a* substring, and the substring is what fails.
 *
 * ## Why a real SQLite
 *
 * Same reason as everywhere else the position is asserted: `unicode61` and
 * `offsets()` are the answer, and a fake would answer with a rule of its own.
 *
 * ## The one case the index cannot answer
 *
 * "port" in "Airport transfer" is the term in the middle of a word, and the index
 * of words does not look there — that is what the contains mode is for. The case
 * stays in the corpus because the rule's answer still has to be in the characters
 * of the text; what is asserted about the index for it is that it finds nothing,
 * which is the difference between the two rules and not a drift between them.
 */
@Tag("slow")
@Tag("slow:data")
class FtsMatchOffsetsAgreementTest {
    private lateinit var tempDir: File
    private lateinit var db: AppDatabase

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("singularity-fts-position-agreement-").toFile()
        db = AppDatabaseFactory.build(createSqlDriver(), File(tempDir, "singularity.db").absolutePath)
    }

    @AfterTest
    fun tearDown() {
        db.close()
        tempDir.deleteRecursively()
    }

    /**
     * One question the corpus asks, and the characters the answer covers.
     *
     * @param id the row the case is seeded as.
     * @param text the text the case is written in.
     * @param term the term the contains rule is asked about.
     * @param expected the characters the rule's span has to cover — the text as
     *   the user sees it, accents and case included.
     * @param why what the case is in the corpus for.
     */
    private data class Case(
        val id: String,
        val text: String,
        val term: String,
        val expected: String,
        val why: String,
    )

    /**
     * The cases the two answers have to agree about.
     *
     * The four the brief names: a diacritic, an accented character, a term of
     * several words, and a term in the middle of a word. The first two are the
     * ones where the fold and the byte offsets both have something to get wrong;
     * the third is the one where the span runs across a separator; the last is
     * the one where the index has no answer at all.
     */
    private val corpus =
        listOf(
            Case(
                id = "t-cafe-capital",
                text = "Café au lait",
                term = "cafe",
                expected = "Café",
                why = "a capital letter with an accent, at the first character of the text",
            ),
            Case(
                id = "t-cafe-middle",
                text = "the café on the corner",
                term = "cafe",
                expected = "café",
                why = "a diacritic after an ASCII prefix — the byte offset and the character index part company here",
            ),
            Case(
                id = "t-quarterly",
                text = "Write the quarterly report",
                term = "quarterly report",
                expected = "quarterly report",
                why = "a term of several words: the rule's span runs from the first word to the last, separator included",
            ),
            Case(
                id = "t-airport",
                text = "Airport transfer",
                term = "port",
                expected = "port",
                why = "a term in the middle of a word — the index cannot answer this one, and the rule can",
            ),
        )

    @Test
    fun `the rule's span covers exactly the characters the index reports`() =
        runTest {
            seed()

            for (case in corpus) {
                val span =
                    TextMatch.containsSpan(case.text, case.term)
                        ?: fail(
                            "${case.why}: the rule reports no span for \"${case.term}\" in " +
                                "\"${case.text}\". The contains rule says the text carries the " +
                                "term, so it has to say where.",
                        )

                // The characters, not the numbers: a fold that changed the length
                // of the text would move the span and leave these two different.
                assertEquals(
                    case.expected,
                    case.text.substring(span.first, span.last + 1),
                    "${case.why}: the rule's span must land on the characters the user " +
                        "sees. The position is asserted through the text it covers, so a fold " +
                        "that stopped being length-preserving fails here rather than being " +
                        "assumed away.",
                )

                val reported = indexRanges(case)

                if (reported == null) {
                    assertFalse(
                        TextMatch.matches(case.text, case.term),
                        "${case.why}: the index found nothing, so the word rule must find " +
                            "nothing either — the two rules are two questions, not two answers " +
                            "to one.",
                    )
                    continue
                }

                assertTrue(
                    reported.isNotEmpty(),
                    "${case.why}: the index returned the row without a position, and the rule " +
                        "has one. A match the screen cannot draw is drawn as no match.",
                )

                for (range in reported) {
                    assertTrue(
                        range.start >= span.first && range.end <= span.last + 1,
                        "${case.why}: the index reports ${range.start}..${range.end} and the " +
                            "rule reports ${span.first}..${span.last}. The rule's span has to " +
                            "cover the characters the index named — a span beside them draws " +
                            "the emphasis on the wrong word.",
                    )
                }
            }
        }

    /**
     * The ranges the index reports for [case]'s row, or `null` when the index
     * does not return the row at all.
     *
     * The two are different findings: a row with no range is a match the search
     * cannot draw, and no row is a question the index cannot answer. The test
     * says different things about them, so the helper does not collapse them.
     */
    private suspend fun indexRanges(case: Case): List<TextMatchRange>? {
        val row =
            db
                .ftsDao()
                .watchTasks("u1", TextMatchQuery(listOf(case.term)))
                .first()
                .firstOrNull { it.task.id == case.id }
                ?: return null
        return FtsOffsets
            .ranges(row.offsets, listOf(FtsColumn(SearchColumn.TITLE, row.task.title)))
            .filter { it.column == SearchColumn.TITLE }
    }

    private suspend fun seed() {
        corpus.forEach { case ->
            db.taskDao().upsert(
                TaskEntity(
                    id = case.id,
                    title = case.text,
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
}
